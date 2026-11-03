package com.robo.phonegateway

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.ble.DongleClock
import com.robo.phonegateway.ble.DongleLines
import com.robo.phonegateway.ble.ImuGapTracker
import com.robo.phonegateway.log.FrameLog
import com.robo.phonegateway.mqtt.Publisher
import com.robo.phonegateway.session.OpenSession
import com.robo.phonegateway.session.Outbox
import com.robo.phonegateway.session.SessionEvents
import com.robo.phonegateway.telemetry.AssembledFrame
import com.robo.phonegateway.telemetry.FrameAssembler
import com.robo.phonegateway.telemetry.GpsFix
import com.robo.phonegateway.telemetry.LapAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File

/**
 * Foreground service with two independent states:
 *
 *  - linked: connected to the dongle so the app can show whether it is there.
 *    Held while MainActivity is visible ([link] / [unlink]); nothing is logged
 *    or published.
 *  - capturing: one recording session. phone GPS + dongle -> FrameAssembler ->
 *    FrameLog (always) -> Publisher (QoS 1, buffered while offline), and lap
 *    anchors on request. Start capture sends session_start, Stop capture drains
 *    the open seconds and sends session_end (docs/plans/2026-11-03-recording-sessions.md).
 *
 * The service stops when it is neither (and no session is still finishing),
 * which releases the dongle for other apps (dbc-forge) as soon as the gateway is
 * closed without capturing.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dongle: DongleClient? = null
    private val clock = DongleClock()
    private val imuGaps = ImuGapTracker()
    private var gapLogWindowStart = 0L
    private var gapLogsInWindow = 0
    private var linkWanted = false
    private var capture: Capture? = null
    /** The last capture's stop: final frames and session_end. The service outlives it. */
    private var finishing: Job? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val outbox by lazy { Outbox(File(filesDir, "outbox")) }
    private val openSession by lazy { OpenSession(File(filesDir, "session-open")) }
    private val frameDir by lazy { File(filesDir, "frames") }

    /** Everything that exists only while capturing. */
    private class Capture(
        val sessionId: Long,
        val deviceId: String,
        val assembler: FrameAssembler,
        val frameLog: FrameLog,
        val publisher: Publisher,
        val location: LocationCallback,
        val wakeLock: PowerManager.WakeLock,
    ) {
        var loop: Job? = null
        // written by the frame loop, then by the stop job after the loop has ended
        @Volatile var logged = 0L
        @Volatile var imuIncomplete = 0L
        @Volatile var imuMissing = 0L
        @Volatile var firstFrameUs: Long? = null
        @Volatile var lastFrameUs: Long? = null
        // summed from the dongle's 5 s STAT lines
        @Volatile var dongleCanDrops = 0L
        @Volatile var dongleImuDrops = 0L
    }

    @Volatile private var lastFix: Location? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LINK -> {
                linkWanted = true
                ensureForeground()
                ensureDongle()
            }
            ACTION_UNLINK -> {
                linkWanted = false
                stopIfIdle()
            }
            ACTION_START_CAPTURE -> {
                ensureForeground(capturing = true)
                ensureDongle()
                if (capture == null) startCapture()
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                if (!stopIfIdle()) ensureForeground()
            }
            ACTION_MARK_START -> markStart()
            ACTION_APPLY_FILTER -> dongle?.sendFilter()
            ACTION_FORGET_DONGLE -> dongle?.let {
                it.forget()
                it.stop()
                it.start()
            }
            null -> {
                // restarted by the system after being killed: nothing to resume without the UI
                if (capture == null && !linkWanted) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle(): Boolean {
        if (capture != null || linkWanted || finishing?.isActive == true) return false
        stopSelf()
        return true
    }

    private fun ensureForeground(capturing: Boolean = capture != null) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        startForeground(NOTIFICATION_ID, notification(capturing), types)
    }

    private fun ensureDongle() {
        if (dongle != null) return
        dongle = DongleClient(
            context = this,
            onNotification = ::onDongleNotification,
            onState = { s ->
                if (s.startsWith("connecting")) { // a rebooted dongle restarts its counter
                    clock.reset()
                    imuGaps.reset()
                }
                GatewayStatus.update { it.copy(dongle = s, dongleConnected = s.startsWith("connected")) }
            },
            onMtu = { mtu -> GatewayStatus.update { it.copy(bleMtu = mtu) } },
            onPhy = { phy ->
                Log.i(TAG, "BLE tx PHY $phy")
                GatewayStatus.update { it.copy(blePhy = phy) }
            },
        ).also { it.start() }
    }

    // what reached the app between two dongle STAT lines, to compare with what it sent
    private var gotNotifies = 0L
    private var gotBytes = 0L
    private var gotLines = 0L

    private fun onDongleNotification(payload: String, at: Long) {
        val parsed = DongleLines.parse(payload, at, clock)
        GatewayStatus.update { it.copy(lastDongleDataMs = at) }
        gotNotifies++
        gotBytes += payload.length
        gotLines += parsed.frames.size + parsed.imu.size
        parsed.stats?.let { s ->
            Log.i(TAG, "dongle stats: $s | phone got $gotNotifies notif / $gotBytes B, $gotLines can+imu lines")
            gotNotifies = 0
            gotBytes = 0
            gotLines = 0
            GatewayStatus.update { it.copy(dongleStats = s.toString()) }
            capture?.let { c ->
                c.dongleCanDrops += s.canDropped
                c.dongleImuDrops += s.imuDropped
            }
        }
        logImuGaps(imuGaps.add(parsed.imu), at)
        val c = capture ?: return
        c.assembler.addCan(parsed.frames)
        c.assembler.addImu(parsed.imu)
        if (parsed.badLines > 0 || parsed.truncated) {
            GatewayStatus.update {
                it.copy(
                    badLines = it.badLines + parsed.badLines,
                    truncatedNotifications = it.truncatedNotifications + if (parsed.truncated) 1 else 0,
                )
            }
        }
    }

    private fun startCapture() {
        val deviceId = deviceId(this)
        val sessionId = System.currentTimeMillis()
        // 5 s grace: under load BLE delivers in bursts seconds late, and a sample
        // that misses its second's grace is dropped
        val assembler = FrameAssembler(deviceId, graceMs = 5_000, sessionId = sessionId)
        val auth = Auth.get(this)
        val publisher = Publisher(
            scope = scope,
            serverUris = Config.BROKER_URIS,
            tlsName = Config.BROKER_TLS_NAME,
            clientId = deviceId,
            topic = Config.TOPIC,
            persistence = MqttDefaultFilePersistence(File(filesDir, "mqtt").absolutePath),
            credentials = { force -> auth.credentials(force) },
            onState = { s -> GatewayStatus.update { it.copy(broker = s) } },
        )
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonegateway:capture")
            .apply { acquire() }
        val location = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { assembler.addGps(it.toFix()) }
                result.lastLocation?.let { loc ->
                    lastFix = loc
                    GatewayStatus.update { it.copy(lastFixMs = loc.toFix().timeMs) }
                }
            }
        }

        // a marker still here is a capture the app never closed (killed mid-session)
        val unclosed = openSession.read()
        openSession.write(OpenSession.Marker(sessionId, deviceId))
        val start = SessionEvents.Start(
            deviceId = deviceId,
            sessionId = sessionId,
            mode = "race", // trip mode: later phase
            canFilter = dongle?.filterIds ?: emptyList(),
            dongle = dongle?.connectedDevice,
            appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?",
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        outbox.put(Outbox.startName(sessionId), start.toJson())

        val frameLog = FrameLog(frameDir, sessionId)
        val c = Capture(sessionId, deviceId, assembler, frameLog, publisher, location, wakeLock)
        capture = c
        GatewayStatus.update {
            it.copy(
                running = true, lastError = null, sessionId = sessionId, lastAnchor = null,
                framesLogged = 0, framesAcked = 0, framesNotSent = 0, imuIncomplete = 0,
                imuMissingPoints = 0, lateSamples = 0,
            )
        }
        startLocation(location)
        c.loop = scope.launch(Dispatchers.IO) {
            finishing?.join() // the previous session's end goes into the outbox first
            // backstop on the log's size; the session being recovered and this one stay
            val keep = setOfNotNull(frameLog.file, unclosed?.let { File(frameDir, FrameLog.fileName(it.sessionId)) })
            FrameLog.trim(frameDir, keep).forEach { Log.i(TAG, "frame log over size cap, deleted ${it.name}") }
            if (unclosed != null) {
                val end = OpenSession.recover(unclosed, frameDir, System.currentTimeMillis())
                outbox.put(Outbox.endName(unclosed.sessionId), end.toJson())
                Log.i(TAG, "recovered unclosed session ${unclosed.sessionId}: ${end.frames} frames")
            }
            publisher.start()
            // older sessions' undelivered events first, then this session's start
            for ((name, json) in outbox.pending()) {
                publisher.publishEvent(json) { outbox.remove(name) }
            }
            Log.i(TAG, "session $sessionId started")
            frameLoop(c)
        }
    }

    /**
     * End the session: stop sampling at once, then (off the main thread) build the
     * seconds still open, send session_end, wait briefly for its ack, and close the
     * publisher. An end that is not acked stays in the outbox for the next capture.
     */
    private fun stopCapture(wait: Boolean = false) {
        val c = capture
        if (c != null) {
            capture = null
            lastFix = null
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(c.location)
            GatewayStatus.update { it.copy(running = false, broker = "finishing session") }
            val job = scope.launch(Dispatchers.IO) { finish(c) }
            finishing = job
            job.invokeOnCompletion {
                main.post {
                    if (finishing === job) finishing = null
                    stopIfIdle()
                }
            }
        }
        if (wait) runBlocking { withTimeoutOrNull(FINISH_WAIT_MS) { finishing?.join() } }
    }

    private suspend fun finish(c: Capture) {
        c.loop?.cancelAndJoin()
        // everything received so far, without waiting out the grace period
        emit(c, c.assembler.drain(System.currentTimeMillis() + FINAL_DRAIN_AHEAD_MS))
        publishStatus(c)
        val end = SessionEvents.End(
            deviceId = c.deviceId,
            sessionId = c.sessionId,
            endedAtMs = System.currentTimeMillis(),
            frames = c.logged,
            firstFrameUs = c.firstFrameUs,
            lastFrameUs = c.lastFrameUs,
            imuSecondsDropped = c.imuIncomplete,
            imuPointsFilled = c.imuMissing,
            lateSamples = c.assembler.lateSamples,
            dongleCanDrops = c.dongleCanDrops,
            dongleImuDrops = c.dongleImuDrops,
            reason = "user_stop",
        )
        val name = Outbox.endName(c.sessionId)
        val json = end.toJson()
        outbox.put(name, json)
        openSession.clear()
        val acked = CompletableDeferred<Unit>()
        c.publisher.publishEvent(json) {
            outbox.remove(name)
            acked.complete(Unit)
        }
        val sent = withTimeoutOrNull(END_ACK_WAIT_MS) { acked.await() } != null
        Log.i(TAG, "session ${c.sessionId} ended: ${c.logged} frames, end ${if (sent) "acked" else "left in outbox"}")
        c.publisher.stop()
        c.frameLog.close()
        if (c.wakeLock.isHeld) c.wakeLock.release()
        GatewayStatus.update { it.copy(broker = "idle", lastFixMs = 0) }
    }

    private suspend fun frameLoop(c: Capture) {
        while (scope.isActive) {
            // wake just after each second boundary
            delay(FrameAssembler.WINDOW_MS - System.currentTimeMillis() % FrameAssembler.WINDOW_MS + 20)
            emit(c, c.assembler.drain(System.currentTimeMillis()))
            publishStatus(c)
        }
    }

    /** Log, publish and count closed seconds. */
    private fun emit(c: Capture, frames: List<AssembledFrame>) {
        for (frame in frames) {
            val bytes = frame.frame.toByteArray()
            try {
                c.frameLog.append(bytes)
                c.logged++
                val t = frame.frame.baseTimestampUs
                if (c.firstFrameUs == null) c.firstFrameUs = t
                c.lastFrameUs = t
            } catch (e: Exception) {
                GatewayStatus.update { it.copy(lastError = "frame log: ${e.message}") }
            }
            c.publisher.publish(bytes)
            if (frame.imuIncomplete) c.imuIncomplete++
            c.imuMissing += frame.imuMissingPoints
            GatewayStatus.update { it.copy(lastImuSamples = frame.imuSamples, lastImuMaxGapMs = frame.imuMaxGapMs) }
        }
    }

    private fun publishStatus(c: Capture) = GatewayStatus.update {
        it.copy(
            framesLogged = c.logged,
            framesAcked = c.publisher.acked.get(),
            framesNotSent = c.publisher.notSent.get(),
            imuIncomplete = c.imuIncomplete,
            imuMissingPoints = c.imuMissing,
            lateSamples = c.assembler.lateSamples,
        )
    }

    /** Log IMU gap events (at most [GAP_LOGS_PER_MINUTE] a minute) and update the counts. */
    private fun logImuGaps(events: List<ImuGapTracker.Event>, at: Long) {
        if (events.isEmpty()) return
        if (at - gapLogWindowStart > 60_000) {
            gapLogWindowStart = at
            gapLogsInWindow = 0
        }
        for (e in events) {
            if (gapLogsInWindow++ >= GAP_LOGS_PER_MINUTE) break
            Log.i(GAP_TAG, "$e received=$at")
        }
        GatewayStatus.update { it.copy(imuDongleGaps = imuGaps.dongleGaps, imuClockJumps = imuGaps.clockJumps) }
    }

    /** Send a lap_anchor at the latest fix, for the lap-segmentation pipeline. */
    private fun markStart() {
        val c = capture
        val fix = lastFix
        val fixAgeMs = fix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        if (c == null || fix == null || fixAgeMs == null || fixAgeMs > LapAnchor.MAX_FIX_AGE_MS) {
            GatewayStatus.update { it.copy(lastAnchor = "not sent: no GPS fix in the last ${LapAnchor.MAX_FIX_AGE_MS / 1000} s") }
            return
        }
        val anchor = LapAnchor(
            deviceId = deviceId(this),
            sessionId = c.sessionId,
            capturedAtMs = System.currentTimeMillis(),
            latDeg = fix.latitude,
            lonDeg = fix.longitude,
            accuracyM = if (fix.hasAccuracy()) fix.accuracy else null,
        )
        val json = anchor.toJson()
        c.publisher.publish(json.toByteArray(Charsets.UTF_8))
        try {
            File(frameDir, "events.jsonl").appendText(json + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "events log", e)
        }
        Log.i(TAG, "lap anchor: $json")
        GatewayStatus.update { it.copy(lastAnchor = anchor.describe()) }
    }

    @SuppressLint("MissingPermission") // MainActivity only starts capture once location is granted
    private fun startLocation(callback: LocationCallback) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(100)
            .build()
        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    override fun onDestroy() {
        stopCapture(wait = true)
        dongle?.stop()
        dongle = null
        scope.cancel()
        GatewayStatus.update {
            it.copy(running = false, dongle = "idle", dongleConnected = false, broker = "idle")
        }
        super.onDestroy()
    }

    private fun notification(capturing: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Capture", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Phone Gateway")
            .setContentText(if (capturing) "Capturing telemetry" else "Connected to the dongle")
            .setSmallIcon(if (capturing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PhoneGateway"
        private const val GAP_TAG = "PhoneGatewayImu"
        private const val GAP_LOGS_PER_MINUTE = 30
        /** How long Stop waits for the broker to ack session_end before leaving it in the outbox. */
        private const val END_ACK_WAIT_MS = 5_000L
        private const val FINISH_WAIT_MS = 8_000L
        private const val FINAL_DRAIN_AHEAD_MS = 3_600_000L
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LINK = "com.robo.phonegateway.LINK"
        private const val ACTION_UNLINK = "com.robo.phonegateway.UNLINK"
        private const val ACTION_START_CAPTURE = "com.robo.phonegateway.START_CAPTURE"
        private const val ACTION_STOP_CAPTURE = "com.robo.phonegateway.STOP_CAPTURE"
        private const val ACTION_MARK_START = "com.robo.phonegateway.MARK_START"
        private const val ACTION_APPLY_FILTER = "com.robo.phonegateway.APPLY_FILTER"
        private const val ACTION_FORGET_DONGLE = "com.robo.phonegateway.FORGET_DONGLE"

        private fun send(context: Context, action: String, foreground: Boolean = false) {
            val intent = Intent(context, GatewayService::class.java).setAction(action)
            try {
                if (foreground) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: IllegalStateException) {
                // background start refused: only happens with no service running, so nothing to tell it
                Log.w(TAG, "could not send $action", e)
            }
        }

        /** Connect to the dongle while the UI is visible. */
        fun link(context: Context) = send(context, ACTION_LINK, foreground = true)
        fun unlink(context: Context) = send(context, ACTION_UNLINK)
        fun startCapture(context: Context) = send(context, ACTION_START_CAPTURE, foreground = true)
        fun stopCapture(context: Context) = send(context, ACTION_STOP_CAPTURE)
        fun markStart(context: Context) = send(context, ACTION_MARK_START)
        fun applyFilter(context: Context) = send(context, ACTION_APPLY_FILTER)
        fun forgetDongle(context: Context) = send(context, ACTION_FORGET_DONGLE)

        /** Stable per install; also the MQTT client id. */
        @SuppressLint("HardwareIds")
        fun deviceId(context: Context): String =
            "phone-" + Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        /** Location fix time is GPS/UTC time; map it onto this phone's wall clock like the dongle samples. */
        private fun Location.toFix(): GpsFix {
            val ageMs = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
            return GpsFix(
                timeMs = System.currentTimeMillis() - ageMs,
                latDeg = latitude,
                lonDeg = longitude,
                altM = altitude,
                speedMps = if (hasSpeed()) speed else null,
                bearingDeg = if (hasBearing()) bearing else null,
            )
        }
    }
}
