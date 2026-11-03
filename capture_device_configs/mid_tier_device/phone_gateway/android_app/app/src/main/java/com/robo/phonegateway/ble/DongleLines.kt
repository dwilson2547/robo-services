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

class ParsedNotification(val frames: List<CanFrame>, val imu: List<ImuSample>, val badLines: Int)

/**
 * Parses one NUS notification from the dbc-forge firmware
 * (embedded/dbc-forge/firmware/esp32_can_ble_bridge):
 *
 *   STD,0x1E5,8,82,00,00,80,00,00,00,AB,<ts>
 *   EXT,0x10242040,1,01,<ts>
 *   IMU,qw,qx,qy,qz,ax,ay,az,gx,gy,gz,<ts>
 *
 * `<ts>` is the dongle's millis() % 65536 at capture. The latest entry in the
 * notification is taken to have been captured at [receivedAtMs]; the others are
 * placed before it by their capture-counter difference. Differences are taken
 * circularly, because the counter wraps every 65.536 s and a notification can
 * straddle the wrap (every entry in one notification is far less than 32 s apart).
 */
object DongleLines {

    fun parse(payload: String, receivedAtMs: Long): ParsedNotification {
        val frames = mutableListOf<Pending<RawFrame>>()
        val imu = mutableListOf<Pending<RawImu>>()
        var bad = 0
        for (line in payload.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val ok = if (trimmed.startsWith("IMU,")) {
                parseImu(trimmed)?.let { imu.add(it) } != null
            } else {
                parseFrame(trimmed)?.let { frames.add(it) } != null
            }
            if (!ok) bad++
        }

        val counters = frames.mapNotNull { it.counter } + imu.mapNotNull { it.counter }
        val pivot = counters.firstOrNull() ?: 0
        val latest = counters.maxOfOrNull { signedOffset(it, pivot) } ?: 0L
        fun ageOf(counter: Int?): Long =
            if (counter == null) 0L else (latest - signedOffset(counter, pivot)).coerceAtLeast(0L)

        return ParsedNotification(
            frames = frames.map { (raw, c) -> CanFrame(receivedAtMs - ageOf(c), raw.id, raw.extended, raw.data) },
            imu = imu.map { (r, c) ->
                ImuSample(receivedAtMs - ageOf(c), r.v[0], r.v[1], r.v[2], r.v[3], r.v[4], r.v[5], r.v[6], r.v[7], r.v[8], r.v[9])
            },
            badLines = bad,
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

    private fun parseImu(line: String): Pending<RawImu>? = runCatching {
        val parts = line.split(",")
        if (parts.size < 11) return null
        Pending(RawImu(FloatArray(10) { parts[1 + it].toFloat() }), parts.getOrNull(11)?.toIntOrNull())
    }.getOrNull()
}
