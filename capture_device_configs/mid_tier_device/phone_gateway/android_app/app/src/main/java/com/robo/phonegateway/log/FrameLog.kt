package com.robo.phonegateway.log

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Append-only log of every frame the app builds, written before publishing is
 * attempted, so a broker or network outage never loses data.
 *
 * One file per UTC day of capture, `frames-YYYYMMDD.bin`, each a sequence of
 * [4-byte big-endian length][serialized TelemetryFrame]. A frame that was also
 * published live appears in both places; replay is idempotent on
 * (device_id, base_timestamp_us).
 */
class FrameLog(private val dir: File) {

    private var openDay: String? = null
    private var out: DataOutputStream? = null

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun append(captureTimeMs: Long, frame: ByteArray) {
        val day = DAY.format(Instant.ofEpochMilli(captureTimeMs))
        if (day != openDay) {
            out?.close()
            out = DataOutputStream(FileOutputStream(File(dir, "frames-$day.bin"), true).buffered())
            openDay = day
        }
        val stream = out!!
        stream.writeInt(frame.size)
        stream.write(frame)
        stream.flush()
    }

    @Synchronized
    fun close() {
        out?.close()
        out = null
        openDay = null
    }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

        /** Read a log file back. A truncated final record (crash mid-write) is ignored. */
        fun read(file: File): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            DataInputStream(file.inputStream().buffered()).use { input ->
                while (true) {
                    val len = try { input.readInt() } catch (_: EOFException) { break }
                    if (len < 0 || len > MAX_FRAME_BYTES) break
                    val buf = ByteArray(len)
                    try { input.readFully(buf) } catch (_: EOFException) { break }
                    frames.add(buf)
                }
            }
            return frames
        }

        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
