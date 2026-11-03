package com.robo.phonegateway.log

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

/**
 * Append-only log of every frame a session builds, written before publishing is
 * attempted, so a broker or network outage never loses data.
 *
 * One file per recording session, `session-<session id>.bin`, a sequence of
 * [4-byte big-endian length][serialized TelemetryFrame]. A frame that was also
 * published live appears in both places; replay is idempotent on
 * (device_id, base_timestamp_us). Files from before sessions are named
 * `frames-YYYYMMDD.bin` (one per UTC day) and have the same record format.
 */
class FrameLog(dir: File, sessionId: Long) {

    val file = File(dir, fileName(sessionId))
    private var out: DataOutputStream? = null

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun append(frame: ByteArray) {
        val stream = out ?: DataOutputStream(FileOutputStream(file, true).buffered()).also { out = it }
        stream.writeInt(frame.size)
        stream.write(frame)
        stream.flush()
    }

    @Synchronized
    fun close() {
        out?.close()
        out = null
    }

    companion object {
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        /** Default for [trim]: a backstop, not a retention policy. */
        const val MAX_TOTAL_BYTES = 5L * 1024 * 1024 * 1024

        fun fileName(sessionId: Long) = "session-$sessionId.bin"

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

        /**
         * Delete the oldest logs (by name, which sorts by time: day files, then
         * session files by start) until the total is at most [maxBytes]. Files in
         * [keep] are never deleted. Returns the deleted files.
         */
        fun trim(dir: File, keep: Set<File>, maxBytes: Long = MAX_TOTAL_BYTES): List<File> {
            val logs = (dir.listFiles { f -> f.name.endsWith(".bin") } ?: emptyArray()).sortedBy { it.name }
            var total = logs.sumOf { it.length() }
            val deleted = mutableListOf<File>()
            for (f in logs) {
                if (total <= maxBytes) break
                if (f in keep) continue
                val size = f.length()
                if (f.delete()) {
                    total -= size
                    deleted += f
                }
            }
            return deleted
        }
    }
}
