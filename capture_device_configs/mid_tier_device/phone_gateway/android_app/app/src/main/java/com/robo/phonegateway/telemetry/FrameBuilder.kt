package com.robo.phonegateway.telemetry

import android.location.Location
import com.robo.phonegateway.ble.CanFrame
import com.robo.phonegateway.ble.ImuSample
import racelogger.v1.TelemetryFrame
import racelogger.v1.TelemetryFrame.GpsSection
import racelogger.v1.TelemetryFrame.ImuSection
import racelogger.v1.TelemetryFrame.CanSection
import racelogger.v1.TelemetryFrame.CanMessage
import kotlin.math.roundToInt

/**
 * Assembles one TelemetryFrame per wall-clock second from buffered dongle IMU/CAN
 * samples and phone GPS fixes.
 *
 * Clock alignment v1: dongle timestamps are mapped to phone wall-clock on arrival
 * (BLE latency included) — fine for pipeline testing; tighter alignment later.
 * base_timestamp_us is the phone's epoch time at frame start; CAN carries per-
 * message dt_us; IMU uses the implicit grid (sample_period_us = 1s / count);
 * GPS populates the new per-fix dt_us because Android fixes are uneven.
 *
 * Scaling (decode = raw / scale, see telemetry.proto):
 *   accel milli-g, gyro centi-deg/s, quat int16-normalized (/32767),
 *   lat/lon deg*1e7, alt mm, speed cm/s, heading centi-deg.
 */
class FrameBuilder(private val deviceId: String) {

    private val imu = mutableListOf<ImuSample>()
    private val can = mutableListOf<CanFrame>()
    private val gps = mutableListOf<Location>()

    @Synchronized
    fun addImu(s: ImuSample) { imu.add(s) }

    @Synchronized
    fun addCan(f: CanFrame, allowlist: Set<Int>) {
        if (allowlist.isEmpty() || f.id in allowlist) can.add(f)
    }

    @Synchronized
    fun addGps(l: Location) { gps.add(l) }

    /**
     * Build the frame covering (baseMs, baseMs + 1000] and clear consumed samples.
     * Returns null if there is nothing to send.
     */
    @Synchronized
    fun buildFrame(baseMs: Long): TelemetryFrame? {
        val baseUs = baseMs * 1000L
        val endMs = baseMs + 1000L

        val imuWin = imu.filter { it.timestampMs in baseMs + 1..endMs }
        val canWin = can.filter { it.timestampMs in baseMs + 1..endMs }
        val gpsWin = gps.filter { it.time in baseMs + 1..endMs }
        imu.removeAll(imuWin.toSet())
        can.removeAll(canWin.toSet())
        gps.removeAll(gpsWin.toSet())

        if (imuWin.isEmpty() && canWin.isEmpty() && gpsWin.isEmpty()) return null

        val frame = TelemetryFrame.newBuilder()
            .setVersion(1)
            .setDeviceId(deviceId)
            .setBaseTimestampUs(baseUs)

        if (imuWin.isNotEmpty()) {
            val n = imuWin.size
            val periodUs = 1_000_000L / n
            val imuSec = ImuSection.newBuilder()
                .setCount(n)
                .setSamplePeriodUs(periodUs.toInt())
            // raw gyro/accel run at the full rate
            for (s in imuWin) {
                imuSec.addAccelX(mg(s.ax)); imuSec.addAccelY(mg(s.ay)); imuSec.addAccelZ(mg(s.az))
                imuSec.addGyroX(centiDegPerSec(s.gx))
                imuSec.addGyroY(centiDegPerSec(s.gy))
                imuSec.addGyroZ(centiDegPerSec(s.gz))
            }
            // quaternion at its own (slower) cadence: every n-th sample, 10 Hz nominal
            val quatEvery = if (n >= 40) 5 else 1 // 50 Hz raw → 10 Hz quat
            imuSec.quatPeriodUs = (periodUs * quatEvery).toInt()
            imuWin.filterIndexed { i, _ -> i % quatEvery == 0 }.forEach { s ->
                imuSec.addQuatW(quat16(s.qw)); imuSec.addQuatX(quat16(s.qx))
                imuSec.addQuatY(quat16(s.qy)); imuSec.addQuatZ(quat16(s.qz))
            }
            frame.imu = imuSec.build()
        }

        if (gpsWin.isNotEmpty()) {
            val gpsSec = GpsSection.newBuilder()
                .setCount(gpsWin.size)
                .setSamplePeriodUs(100_000) // nominal 10 Hz grid; dt_us carries the truth
            for (fix in gpsWin) {
                val dtUs = ((fix.time - baseMs) * 1000L).toInt().coerceAtLeast(0)
                gpsSec.addDtUs(dtUs)
                gpsSec.addLat1E7((fix.latitude * 1e7).roundToInt())
                gpsSec.addLon1E7((fix.longitude * 1e7).roundToInt())
                gpsSec.addAltMm((fix.altitude * 1000.0).roundToInt())
                if (fix.hasSpeed()) gpsSec.addSpeedCms((fix.speed * 100f).roundToInt().toUInt().toInt())
                else gpsSec.addSpeedCms(0)
                if (fix.hasBearing()) gpsSec.addHeadingCdeg((fix.bearing * 100f).roundToInt().toUInt().toInt())
                else gpsSec.addHeadingCdeg(0)
            }
            frame.gps = gpsSec.build()
        }

        if (canWin.isNotEmpty()) {
            val canSec = CanSection.newBuilder()
            for (f in canWin) {
                val dtUs = ((f.timestampMs - baseMs) * 1000L).toInt().coerceAtLeast(0)
                canSec.addMessages(
                    CanMessage.newBuilder()
                        .setDtUs(dtUs)
                        .setCanId(f.id)
                        .setData(com.google.protobuf.ByteString.copyFrom(f.data))
                )
            }
            frame.can = canSec.build()
        }

        return frame.build()
    }

    private fun mg(mps2: Float): Int = (mps2 / 9.80665f * 1000f).roundToInt()
    private fun centiDegPerSec(radPerSec: Float): Int =
        (radPerSec * 57.29578f * 100f).roundToInt()
    private fun quat16(q: Float): Int = (q * 32767f).roundToInt().coerceIn(-32768, 32767)
}
