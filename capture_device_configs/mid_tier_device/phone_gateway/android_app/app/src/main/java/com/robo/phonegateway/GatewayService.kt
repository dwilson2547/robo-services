package com.robo.phonegateway

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.robo.phonegateway.auth.OidcAuth
import com.robo.phonegateway.ble.DongleBleClient
import com.robo.phonegateway.log.FrameLog
import com.robo.phonegateway.mqtt.MqttPublisher
import com.robo.phonegateway.telemetry.FrameBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service tying it together: BLE dongle → FrameBuilder → FrameLog
 * (always, before publish) → MqttPublisher (QoS 1, OAuth token as password).
 * One TelemetryFrame per wall-clock second.
 */
class GatewayService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var settings: Settings
    private lateinit var auth: OidcAuth
    private lateinit var ble: DongleBleClient
    private lateinit var builder: FrameBuilder
    private lateinit var frameLog: FrameLog
    private lateinit var mqtt: MqttPublisher
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var gpsCallback: LocationCallback? = null
    private var frameJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        auth = OidcAuth(this)
        ble = DongleBleClient(this)
        builder = FrameBuilder(settings.deviceId)
        frameLog = FrameLog(File(filesDir, "frames"))
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)

        mqtt = MqttPublisher(
            host = settings.brokerHost,
            port = settings.brokerPort,
            topic = settings.topic,
            clientId = settings.deviceId,
            tokenProvider = {
                val token = auth.validAccessToken()
                val user = auth.username()
                if (token != null && user != null) user to token else null
            },
            onAuthRejected = { auth.refresh() }, // CONNACK 5 → silent refresh → retry
        )

        ble.listener = object : DongleBleClient.Listener {
            override fun onConnectionChanged(connected: Boolean, deviceName: String) {}
            override fun onCanFrame(frame: com.robo.phonegateway.ble.CanFrame) {
                builder.addCan(frame, settings.canAllowlist)
            }
            override fun onImuSample(sample: com.robo.phonegateway.ble.ImuSample) {
                builder.addImu(sample)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        scope.launch { auth.discover() }
        ble.startScan()
        startGps()
        mqtt.start()
        startFrameLoop()
        return START_STICKY
    }

    private fun startGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { builder.addGps(it) }
            }
        }
        gpsCallback = cb
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(250L)
            .build()
        fusedLocation.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    private fun startFrameLoop() {
        frameJob = scope.launch {
            while (isActive) {
                val nowMs = System.currentTimeMillis()
                val baseMs = nowMs - (nowMs % 1000L) // align to wall-clock second
                val sleepMs = baseMs + 1000L - System.currentTimeMillis()
                if (sleepMs > 0) delay(sleepMs)
                val frame = builder.buildFrame(baseMs) ?: continue
                val bytes = frame.toByteArray()
                frameLog.append(bytes) // local log first: no holes in replay data
                mqtt.publish(bytes)
            }
        }
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Phone Gateway", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Gateway")
            .setContentText("capturing → publishing telemetry")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        frameJob?.cancel()
        gpsCallback?.let { fusedLocation.removeLocationUpdates(it) }
        mqtt.stop()
        ble.disconnect()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "phone_gateway"
    }
}
