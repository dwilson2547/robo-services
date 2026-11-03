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
import com.robo.phonegateway.telemetry.FrameAssembler
import com.robo.phonegateway.telemetry.GpsFix
import com.robo.phonegateway.telemetry.LapAnchor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File

/**
 * Foreground service with two independent states:
 *
 *  - linked: connected to the dongle so the app can show whether it is there.
 *    Held while MainActivity is visible ([link] / [unlink]); nothing is logged
 *    or published.
 *  - capturing: phone GPS + dongle -> FrameAssembler -> FrameLog (always) ->
 *    Publisher (QoS 1, buffered while offline), and lap anchors on request.
 *
 * The service stops when it is neither, which releases the dongle for other
 * apps (dbc-forge) as soon as the gateway is closed without capturing.
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

    /** Everything that exists only while capturing. */
    private class Capture(
        val assembler: FrameAssembler,
        val frameLog: FrameLog,
        val publisher: Publisher,
        val location: LocationCallback,
        val wakeLock: PowerManager.WakeLock,
    ) {
        var loop: Job? = null
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
        if (capture != null || linkWanted) return false
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
        // 5 s grace: under load BLE delivers in bursts seconds late, and a sample
        // that misses its second's grace is dropped
        val assembler = FrameAssembler(deviceId, graceMs = 5_000)
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
        val c = Capture(assembler, FrameLog(File(filesDir, "frames")), publisher, location, wakeLock)
        c.loop = scope.launch { frameLoop(c) }
        capture = c
        GatewayStatus.update { it.copy(running = true, lastError = null) }
        startLocation(location)
        publisher.start()
    }

    private fun stopCapture() {
        val c = capture ?: return
        capture = null
        c.loop?.cancel()
        lastFix = null
        LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(c.location)
        runBlocking { c.publisher.stop() }
        c.frameLog.close()
        if (c.wakeLock.isHeld) c.wakeLock.release()
        GatewayStatus.update { it.copy(running = false, broker = "idle", lastFixMs = 0) }
    }

    private suspend fun frameLoop(c: Capture) {
        var logged = 0L
        var imuIncomplete = 0L
        var imuMissing = 0L
        while (scope.isActive) {
            // wake just after each second boundary
            delay(FrameAssembler.WINDOW_MS - System.currentTimeMillis() % FrameAssembler.WINDOW_MS + 20)
            for (frame in c.assembler.drain(System.currentTimeMillis())) {
                val bytes = frame.frame.toByteArray()
                try {
                    c.frameLog.append(frame.windowStartMs, bytes)
                    logged++
                } catch (e: Exception) {
                    GatewayStatus.update { it.copy(lastError = "frame log: ${e.message}") }
                }
                c.publisher.publish(bytes)
                if (frame.imuIncomplete) imuIncomplete++
                imuMissing += frame.imuMissingPoints
                GatewayStatus.update { it.copy(lastImuSamples = frame.imuSamples, lastImuMaxGapMs = frame.imuMaxGapMs) }
            }
            GatewayStatus.update {
                it.copy(
                    framesLogged = logged,
                    framesAcked = c.publisher.acked.get(),
                    framesNotSent = c.publisher.notSent.get(),
                    imuIncomplete = imuIncomplete,
                    imuMissingPoints = imuMissing,
                    lateSamples = c.assembler.lateSamples,
                )
            }
        }
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
            capturedAtMs = System.currentTimeMillis(),
            latDeg = fix.latitude,
            lonDeg = fix.longitude,
            accuracyM = if (fix.hasAccuracy()) fix.accuracy else null,
        )
        val json = anchor.toJson()
        c.publisher.publish(json.toByteArray(Charsets.UTF_8))
        try {
            File(File(filesDir, "frames"), "events.jsonl").appendText(json + "\n")
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
        stopCapture()
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
