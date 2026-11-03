package com.robo.phonegateway.telemetry

import com.google.protobuf.ByteString
import com.robo.phonegateway.ble.CanFrame
import com.robo.phonegateway.ble.ImuSample
import racelogger.v1.Telemetry.CanMessage
import racelogger.v1.Telemetry.CanSection
import racelogger.v1.Telemetry.GpsSection
import racelogger.v1.Telemetry.ImuSection
import racelogger.v1.Telemetry.TelemetryFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/** A phone location fix, timestamped in wall-clock milliseconds. */
class GpsFix(
    val timeMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val altM: Double,
    val speedMps: Float?,
    val bearingDeg: Float?,
)

class AssembledFrame(
    val windowStartMs: Long,
    val frame: TelemetryFrame,
    /** Window had IMU samples but not a complete 50 Hz run, so the section was left out. */
    val imuIncomplete: Boolean,
)

/**
 * Builds one TelemetryFrame (telemetry.proto) per wall-clock second.
 *
 * Samples are bucketed by the second they were captured in, and a second is
 * closed [graceMs] after it ends so late BLE batches still land in it. Anything
 * arriving for a second that is already closed is counted in [lateSamples] and
 * dropped.
 *
 * IMU uses the proto's implicit grid (sample i at base + i * 20 ms). Each grid
 * point takes the nearest sample. The section is only written when the samples
 * cover the whole second with no gap over [MAX_IMU_GAP_MS]; a partial second
 * (BLE connecting, dropping) gets no IMU section rather than invented samples.
 * The quaternion goes on a 10 Hz grid the same way.
 */
class FrameAssembler(private val deviceId: String, private val graceMs: Long = 1500) {

    private class Bucket {
        val can = mutableListOf<CanFrame>()
        val imu = mutableListOf<ImuSample>()
        val gps = mutableListOf<GpsFix>()
    }

    private val buckets = sortedMapOf<Long, Bucket>()
    private var closedBefore = Long.MIN_VALUE

    var lateSamples = 0L
        private set

    @Synchronized
    fun addCan(frames: List<CanFrame>) = frames.forEach { f -> bucketFor(f.timeMs)?.can?.add(f) }

    @Synchronized
    fun addImu(samples: List<ImuSample>) = samples.forEach { s -> bucketFor(s.timeMs)?.imu?.add(s) }

    @Synchronized
    fun addGps(fix: GpsFix) {
        bucketFor(fix.timeMs)?.gps?.add(fix)
    }

    /** Close and build every second that ended more than [graceMs] before [nowMs]. */
    @Synchronized
    fun drain(nowMs: Long): List<AssembledFrame> {
        // second W is closed once W + WINDOW_MS + graceMs <= now
        val closeBefore = windowStart(nowMs - graceMs - WINDOW_MS) + WINDOW_MS
        val out = mutableListOf<AssembledFrame>()
        while (buckets.isNotEmpty() && buckets.firstKey() < closeBefore) {
            val start = buckets.firstKey()
            build(start, buckets.remove(start)!!)?.let(out::add)
        }
        closedBefore = maxOf(closedBefore, closeBefore)
        return out
    }

    private fun bucketFor(timeMs: Long): Bucket? {
        val start = windowStart(timeMs)
        if (start < closedBefore) {
            lateSamples++
            return null
        }
        return buckets.getOrPut(start) { Bucket() }
    }

    private fun build(startMs: Long, b: Bucket): AssembledFrame? {
        if (b.can.isEmpty() && b.imu.isEmpty() && b.gps.isEmpty()) return null
        val frame = TelemetryFrame.newBuilder()
            .setVersion(1)
            .setDeviceId(deviceId)
            .setBaseTimestampUs(startMs * 1000)

        val imu = imuSection(startMs, b.imu)
        imu?.let { frame.setImu(it) }
        gpsSection(startMs, b.gps)?.let { frame.setGps(it) }
        if (b.can.isNotEmpty()) {
            val section = CanSection.newBuilder()
            b.can.sortedBy { it.timeMs }.forEach { f ->
                section.addMessages(
                    CanMessage.newBuilder()
                        .setDtUs(offsetUs(startMs, f.timeMs))
                        .setCanId(f.id)
                        .setData(ByteString.copyFrom(f.data)),
                )
            }
            frame.setCan(section)
        }
        return AssembledFrame(startMs, frame.build(), imuIncomplete = b.imu.isNotEmpty() && imu == null)
    }

    private fun imuSection(startMs: Long, raw: List<ImuSample>): ImuSection? {
        if (raw.isEmpty()) return null
        val samples = raw.sortedBy { it.timeMs }
        val times = samples.map { it.timeMs - startMs }
        val gaps = listOf(times.first()) + times.zipWithNext { a, b -> b - a } + (WINDOW_MS - times.last())
        if (gaps.any { it > MAX_IMU_GAP_MS }) return null

        val section = ImuSection.newBuilder()
            .setCount(IMU_POINTS)
            .setSamplePeriodUs((IMU_PERIOD_MS * 1000).toInt())
        for (i in 0 until IMU_POINTS) {
            val s = nearest(samples, startMs + i * IMU_PERIOD_MS)
            section.addAccelX(milliG(s.ax)).addAccelY(milliG(s.ay)).addAccelZ(milliG(s.az))
            section.addGyroX(centiDegPerSec(s.gx)).addGyroY(centiDegPerSec(s.gy)).addGyroZ(centiDegPerSec(s.gz))
        }
        section.setQuatPeriodUs((QUAT_PERIOD_MS * 1000).toInt())
        for (j in 0 until QUAT_POINTS) {
            val s = nearest(samples, startMs + j * QUAT_PERIOD_MS)
            section.addQuatW(unit16(s.qw)).addQuatX(unit16(s.qx)).addQuatY(unit16(s.qy)).addQuatZ(unit16(s.qz))
        }
        return section.build()
    }

    private fun gpsSection(startMs: Long, raw: List<GpsFix>): GpsSection? {
        if (raw.isEmpty()) return null
        val fixes = raw.sortedBy { it.timeMs }
        val section = GpsSection.newBuilder()
            .setCount(fixes.size)
            // Nominal grid for consumers that predate dt_us; dt_us carries the real times.
            .setSamplePeriodUs(1_000_000 / fixes.size)
        for (f in fixes) {
            section.addDtUs(offsetUs(startMs, f.timeMs))
            section.addLat1E7((f.latDeg * 1e7).roundToInt())
            section.addLon1E7((f.lonDeg * 1e7).roundToInt())
            section.addAltMm((f.altM * 1000).roundToInt())
            section.addSpeedCms(((f.speedMps ?: 0f) * 100).roundToInt().coerceIn(0, 65535))
            section.addHeadingCdeg(((f.bearingDeg ?: 0f) * 100).roundToInt().coerceIn(0, 35999))
        }
        return section.build()
    }

    companion object {
        const val WINDOW_MS = 1000L
        const val IMU_PERIOD_MS = 20L
        const val QUAT_PERIOD_MS = 100L
        const val MAX_IMU_GAP_MS = 60L
        private const val IMU_POINTS = (WINDOW_MS / IMU_PERIOD_MS).toInt()
        private const val QUAT_POINTS = (WINDOW_MS / QUAT_PERIOD_MS).toInt()
        private const val GRAVITY = 9.80665f

        fun windowStart(timeMs: Long): Long = Math.floorDiv(timeMs, WINDOW_MS) * WINDOW_MS

        private fun offsetUs(startMs: Long, timeMs: Long): Int = ((timeMs - startMs) * 1000).toInt()

        private fun nearest(sorted: List<ImuSample>, t: Long): ImuSample {
            var lo = 0
            var hi = sorted.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid].timeMs < t) lo = mid + 1 else hi = mid
            }
            val after = sorted[lo]
            val before = sorted.getOrNull(lo - 1) ?: return after
            return if (abs(before.timeMs - t) <= abs(after.timeMs - t)) before else after
        }

        private fun int16(v: Float): Int = v.roundToInt().coerceIn(-32768, 32767)
        private fun milliG(mps2: Float) = int16(mps2 / GRAVITY * 1000f)
        private fun centiDegPerSec(radPerSec: Float) = int16(Math.toDegrees(radPerSec.toDouble()).toFloat() * 100f)
        private fun unit16(q: Float) = int16(q * 32767f)
    }
}
