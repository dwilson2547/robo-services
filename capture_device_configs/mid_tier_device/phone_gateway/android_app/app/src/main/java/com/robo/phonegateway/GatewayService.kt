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
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.ble.DongleLines
import com.robo.phonegateway.log.FrameLog
import com.robo.phonegateway.mqtt.Publisher
import com.robo.phonegateway.telemetry.FrameAssembler
import com.robo.phonegateway.telemetry.GpsFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File

/**
 * Foreground service that does the capture: dongle (BLE) and phone GPS ->
 * FrameAssembler -> FrameLog (always) -> Publisher (QoS 1, buffered while
 * offline). Started and stopped from MainActivity.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var assembler: FrameAssembler
    private lateinit var frameLog: FrameLog
    private lateinit var publisher: Publisher
    private lateinit var dongle: DongleClient
    private var location: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (!started) {
            started = true
            startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val deviceId = deviceId(this)
        assembler = FrameAssembler(deviceId)
        frameLog = FrameLog(File(filesDir, "frames"))
        val auth = Auth.get(this)
        publisher = Publisher(
            scope = scope,
            serverUris = Config.BROKER_URIS,
            tlsName = Config.BROKER_TLS_NAME,
            clientId = deviceId,
            topic = Config.TOPIC,
            persistence = MqttDefaultFilePersistence(File(filesDir, "mqtt").absolutePath),
            credentials = { force -> auth.credentials(force) },
            onState = { s -> GatewayStatus.update { it.copy(broker = s) } },
        )
        dongle = DongleClient(
            context = this,
            onNotification = { payload, at ->
                val parsed = DongleLines.parse(payload, at)
                assembler.addCan(parsed.frames)
                assembler.addImu(parsed.imu)
                if (parsed.badLines > 0 || parsed.truncated) {
                    GatewayStatus.update {
                        it.copy(
                            badLines = it.badLines + parsed.badLines,
                            truncatedNotifications = it.truncatedNotifications + if (parsed.truncated) 1 else 0,
                        )
                    }
                }
            },
            onState = { s -> GatewayStatus.update { it.copy(dongle = s) } },
            onMtu = { mtu -> GatewayStatus.update { it.copy(bleMtu = mtu) } },
        )

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonegateway:capture")
            .apply { acquire() }
        GatewayStatus.update { it.copy(running = true, lastError = null) }
        dongle.start()
        startLocation()
        publisher.start()
        scope.launch { frameLoop() }
    }

    private suspend fun frameLoop() {
        var logged = 0L
        var imuIncomplete = 0L
        while (scope.isActive) {
            // wake just after each second boundary
            delay(FrameAssembler.WINDOW_MS - System.currentTimeMillis() % FrameAssembler.WINDOW_MS + 20)
            for (frame in assembler.drain(System.currentTimeMillis())) {
                val bytes = frame.frame.toByteArray()
                try {
                    frameLog.append(frame.windowStartMs, bytes)
                    logged++
                } catch (e: Exception) {
                    GatewayStatus.update { it.copy(lastError = "frame log: ${e.message}") }
                }
                publisher.publish(bytes)
                if (frame.imuIncomplete) imuIncomplete++
                GatewayStatus.update { it.copy(lastImuSamples = frame.imuSamples, lastImuMaxGapMs = frame.imuMaxGapMs) }
            }
            GatewayStatus.update {
                it.copy(
                    framesLogged = logged,
                    framesAcked = publisher.acked.get(),
                    framesNotSent = publisher.notSent.get(),
                    imuIncomplete = imuIncomplete,
                    lateSamples = assembler.lateSamples,
                )
            }
        }
    }

    @SuppressLint("MissingPermission") // MainActivity only starts the service once location is granted
    private fun startLocation() {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { assembler.addGps(it.toFix()) }
            }
        }
        location = callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
            .setMinUpdateIntervalMillis(100)
            .build()
        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    override fun onDestroy() {
        if (started) {
            location?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
            dongle.stop()
            runBlocking { publisher.stop() }
            frameLog.close()
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        scope.cancel()
        GatewayStatus.update { it.copy(running = false, dongle = "idle", broker = "idle") }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Capture", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Phone Gateway")
            .setContentText("Capturing telemetry")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.robo.phonegateway.STOP"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, GatewayService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, GatewayService::class.java).setAction(ACTION_STOP))

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
