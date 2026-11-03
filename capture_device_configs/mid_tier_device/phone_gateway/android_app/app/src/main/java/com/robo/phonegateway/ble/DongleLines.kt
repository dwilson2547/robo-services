package com.robo.phonegateway.ble

/** One CAN frame from the dongle, timestamped in phone wall-clock milliseconds. */
class CanFrame(val timeMs: Long, val id: Int, val extended: Boolean, val data: ByteArray)

/**
 * One BNO085 sample from the dongle, in phone wall-clock milliseconds.
 * Accel is gravity-removed linear acceleration (m/s²), gyro is calibrated rate
 * (rad/s); the quaternion is the latest game rotation vector (10 Hz report,
 * repeated on every 50 Hz line).
 */
class ImuSample(
    val timeMs: Long,
    val qw: Float, val qx: Float, val qy: Float, val qz: Float,
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
)

/**
 * The dongle's 5-second diagnostics (firmware 1.6.2+): IMU lines emitted and
 * dropped, BNO085 events read and resets, CAN frames forwarded and dropped,
 * notifications the BLE stack refused, and the longest main-loop pass.
 */
data class DongleStats(
    val imuLines: Long, val imuDropped: Long, val imuEvents: Long, val imuResets: Long, val imuReady: Boolean,
    val canFrames: Long, val canDropped: Long, val notifyFails: Long, val maxPassMs: Long,
) {
    override fun toString() =
        "imu $imuLines (drop $imuDropped, events $imuEvents, resets $imuResets${if (imuReady) "" else ", NOT READY"}) " +
            "can $canFrames (drop $canDropped) notify-fail $notifyFails max-pass ${maxPassMs}ms"
}

class ParsedNotification(
    val frames: List<CanFrame>,
    val imu: List<ImuSample>,
    val badLines: Int,
    /** The notification was cut short (no closing newline); its last line was dropped. */
    val truncated: Boolean,
    val stats: DongleStats? = null,
)

/**
 * Parses one NUS notification from the dbc-forge firmware
 * (embedded/dbc-forge/firmware/esp32_can_ble_bridge):
 *
 *   STD,0x1E5,8,82,00,00,80,00,00,00,AB,<ts>
 *   EXT,0x10242040,1,01,<ts>
 *   IMU,qw,qx,qy,qz,ax,ay,az,gx,gy,gz,<ts>
 *
 * `<ts>` is the dongle's millis() % 65536 at capture; [DongleClock] turns it into
 * phone wall-clock time (pass the same clock for every notification of a
 * connection). Lines without one take [receivedAtMs].
 *
 * Every line the firmware writes ends in a newline, so text after the last newline
 * is a line the BLE stack cut off (a notification longer than the negotiated MTU
 * allows). It is dropped rather than parsed: a cut line can still parse, with a
 * shortened timestamp counter that would place it seconds away from its real time.
 */
object DongleLines {

    fun parse(payload: String, receivedAtMs: Long, clock: DongleClock = DongleClock()): ParsedNotification {
        val frames = mutableListOf<Pending<RawFrame>>()
        val imu = mutableListOf<Pending<RawImu>>()
        var bad = 0
        var stats: DongleStats? = null
        val end = payload.lastIndexOf('\n')
        val complete = if (end < 0) "" else payload.substring(0, end)
        val truncated = payload.substring(end + 1).isNotBlank()
        for (line in complete.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val ok = if (trimmed.startsWith("STAT,")) {
                parseStats(trimmed)?.also { stats = it } != null
            } else if (trimmed.startsWith("IMU,")) {
                parseImu(trimmed)?.let { imu.add(it) } != null
            } else {
                parseFrame(trimmed)?.let { frames.add(it) } != null
            }
            if (!ok) bad++
        }

        // line order, so the clock unwraps the counter in capture order
        val ordered = (frames.map { it.counter } + imu.map { it.counter })
        val placed = clock.place(ordered.filterNotNull(), receivedAtMs).iterator()
        val times = ordered.map { c -> if (c == null) receivedAtMs else placed.next() }
        val frameTimes = times.subList(0, frames.size)
        val imuTimes = times.subList(frames.size, times.size)

        return ParsedNotification(
            frames = frames.mapIndexed { i, (raw, _) -> CanFrame(frameTimes[i], raw.id, raw.extended, raw.data) },
            imu = imu.mapIndexed { i, (r, _) ->
                ImuSample(imuTimes[i], r.v[0], r.v[1], r.v[2], r.v[3], r.v[4], r.v[5], r.v[6], r.v[7], r.v[8], r.v[9])
            },
            badLines = bad,
            truncated = truncated,
            stats = stats,
        )
    }

    /** Offset of [counter] from [pivot] on the 16-bit ring, in [-32768, 32767] ms. */
    internal fun signedOffset(counter: Int, pivot: Int): Long =
        ((((counter - pivot) % 65536 + 65536 + 32768) % 65536) - 32768).toLong()

    private data class Pending<T>(val value: T, val counter: Int?)
    private class RawFrame(val id: Int, val extended: Boolean, val data: ByteArray)
    private class RawImu(val v: FloatArray)

    private fun parseFrame(line: String): Pending<RawFrame>? = runCatching {
        val parts = line.split(",")
        val extended = when (parts[0]) {
            "STD" -> false
            "EXT" -> true
            else -> return null
        }
        val id = parts[1].removePrefix("0x").toLong(16).toInt()
        val dlc = parts[2].toInt()
        if (dlc !in 0..8 || parts.size < 3 + dlc) return null
        val data = ByteArray(dlc) { i -> parts[3 + i].toInt(16).toByte() }
        Pending(RawFrame(id, extended, data), parts.getOrNull(3 + dlc)?.toIntOrNull())
    }.getOrNull()

    private fun parseStats(line: String): DongleStats? = runCatching {
        val v = line.split(",").drop(1).map { it.toLong() }
        if (v.size < 9) return null
        DongleStats(v[0], v[1], v[2], v[3], v[4] != 0L, v[5], v[6], v[7], v[8])
    }.getOrNull()

    private fun parseImu(line: String): Pending<RawImu>? = runCatching {
        val parts = line.split(",")
        if (parts.size < 11) return null
        Pending(RawImu(FloatArray(10) { parts[1 + it].toFloat() }), parts.getOrNull(11)?.toIntOrNull())
    }.getOrNull()
}
