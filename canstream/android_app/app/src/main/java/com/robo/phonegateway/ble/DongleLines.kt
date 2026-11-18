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
    /** The line's raw millis%65536 capture counter, for gap diagnostics. */
    val counter: Int? = null,
)

/**
 * One fix from the dongle's MAX-M10S (firmware 1.8.0+), in phone wall-clock
 * milliseconds (from the line's millis%65536 counter, like CAN and IMU).
 * [fixType] is 0 (none, or not gnssFixOK), 2 (2D) or 3 (3D); a line with no
 * fix still parses, so the app can show that the receiver is there.
 */
class DongleGpsFix(
    val timeMs: Long,
    val fixType: Int,
    val numSats: Int,
    val latDeg: Double,
    val lonDeg: Double,
    /** Above mean sea level. */
    val altM: Double,
    val speedMps: Float,
    val headingDeg: Float,
    /** u-blox horizontal accuracy estimate. */
    val haccM: Float,
)

/**
 * The dongle's 5-second diagnostics (firmware 1.6.2+): IMU lines emitted and
 * dropped, BNO085 events read and resets, CAN frames forwarded and dropped,
 * notifications the BLE stack refused, and the longest main-loop pass.
 */
data class DongleStats(
    val imuLines: Long, val imuDropped: Long, val imuEvents: Long, val imuResets: Long, val imuReady: Boolean,
    val canFrames: Long, val canDropped: Long, val notifyFails: Long, val maxPassMs: Long,
    /** Notifications and payload bytes sent (firmware 1.6.3+; -1 before). */
    val notifies: Long = -1, val notifyBytes: Long = -1,
    /**
     * Firmware 1.7.0+ (-1 before): CAN mode (0 listen, 1 acknowledging, 2 listen after
     * acknowledging hit errors), frames identical to the one just before (resends of
     * unacknowledged frames), and bus errors in the window.
     */
    val canMode: Long = -1, val canRepeats: Long = -1, val busErrors: Long = -1,
) {
    override fun toString() =
        "imu $imuLines (drop $imuDropped, events $imuEvents, resets $imuResets${if (imuReady) "" else ", NOT READY"}) " +
            "can $canFrames (drop $canDropped) notify-fail $notifyFails max-pass ${maxPassMs}ms" +
            (if (notifies >= 0) " sent $notifies notif / $notifyBytes B" else "") +
            if (canMode >= 0) " can-mode ${CAN_MODES.getOrElse(canMode.toInt()) { "$canMode" }} repeats $canRepeats bus-errors $busErrors" else ""

    companion object {
        private val CAN_MODES = listOf("listen", "ack", "listen(fallback)")
    }
}

class ParsedNotification(
    val frames: List<CanFrame>,
    val imu: List<ImuSample>,
    val badLines: Int,
    /** The notification was cut short (no closing newline); its last line was dropped. */
    val truncated: Boolean,
    val stats: DongleStats? = null,
    val gps: List<DongleGpsFix> = emptyList(),
)

/**
 * Parses one NUS notification from the dbc-forge firmware
 * (embedded/dbc-forge/firmware/esp32_can_ble_bridge):
 *
 *   STD,0x1E5,8,82,00,00,80,00,00,00,AB,<ts>
 *   EXT,0x10242040,1,01,<ts>
 *   IMU,qw,qx,qy,qz,ax,ay,az,gx,gy,gz,<ts>
 *   GPS,fix,sats,lat,lon,alt_m,speed_mps,heading_deg,hacc_m,utc_ms,pps_ts,<ts>
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
        val gps = mutableListOf<Pending<RawGps>>()
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
            } else if (trimmed.startsWith("GPS,")) {
                parseGps(trimmed)?.let { gps.add(it) } != null
            } else {
                parseFrame(trimmed)?.let { frames.add(it) } != null
            }
            if (!ok) bad++
        }

        // CAN, IMU then GPS counters, not line order: the clock's signed steps
        // place them correctly either way
        val ordered = frames.map { it.counter } + imu.map { it.counter } + gps.map { it.counter }
        val placed = clock.place(ordered.filterNotNull(), receivedAtMs).iterator()
        val times = ordered.map { c -> if (c == null) receivedAtMs else placed.next() }
        val frameTimes = times.subList(0, frames.size)
        val imuTimes = times.subList(frames.size, frames.size + imu.size)
        val gpsTimes = times.subList(frames.size + imu.size, times.size)

        return ParsedNotification(
            frames = frames.mapIndexed { i, (raw, _) -> CanFrame(frameTimes[i], raw.id, raw.extended, raw.data) },
            imu = imu.mapIndexed { i, (r, c) ->
                ImuSample(imuTimes[i], r.v[0], r.v[1], r.v[2], r.v[3], r.v[4], r.v[5], r.v[6], r.v[7], r.v[8], r.v[9], c)
            },
            badLines = bad,
            truncated = truncated,
            stats = stats,
            gps = gps.mapIndexed { i, (g, _) ->
                DongleGpsFix(gpsTimes[i], g.fix, g.sats, g.lat, g.lon, g.alt, g.speed, g.heading, g.hacc)
            },
        )
    }

    /** Offset of [counter] from [pivot] on the 16-bit ring, in [-32768, 32767] ms. */
    internal fun signedOffset(counter: Int, pivot: Int): Long =
        ((((counter - pivot) % 65536 + 65536 + 32768) % 65536) - 32768).toLong()

    private data class Pending<T>(val value: T, val counter: Int?)
    private class RawFrame(val id: Int, val extended: Boolean, val data: ByteArray)
    private class RawImu(val v: FloatArray)
    private class RawGps(
        val fix: Int, val sats: Int, val lat: Double, val lon: Double, val alt: Double,
        val speed: Float, val heading: Float, val hacc: Float,
    )

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
        DongleStats(
            v[0], v[1], v[2], v[3], v[4] != 0L, v[5], v[6], v[7], v[8],
            v.getOrElse(9) { -1 }, v.getOrElse(10) { -1 },
            v.getOrElse(11) { -1 }, v.getOrElse(12) { -1 }, v.getOrElse(13) { -1 },
        )
    }.getOrNull()

    private fun parseImu(line: String): Pending<RawImu>? = runCatching {
        val parts = line.split(",")
        if (parts.size < 11) return null
        Pending(RawImu(FloatArray(10) { parts[1 + it].toFloat() }), parts.getOrNull(11)?.toIntOrNull())
    }.getOrNull()

    private fun parseGps(line: String): Pending<RawGps>? = runCatching {
        val p = line.split(",")
        if (p.size < 12) return null
        val raw = RawGps(
            p[1].toInt(), p[2].toInt(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(),
            p[6].toFloat(), p[7].toFloat(), p[8].toFloat(),
        )
        Pending(raw, p[11].toIntOrNull())
    }.getOrNull()
}
