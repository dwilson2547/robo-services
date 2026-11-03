package com.robo.phonegateway.log

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Append-only local log of every frame the app attempts to publish, written
 * BEFORE the publish is attempted so cellular/auth outages never create holes
 * in the replay data. Format: repeated [4-byte big-endian length][serialized
 * TelemetryFrame]. Backfill tooling reads this with RandomAccessFile.
 */
class FrameLog(dir: File) {

    private val file: File

    init {
        dir.mkdirs()
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        file = File(dir, "frames-$today.bin")
    }

    @Synchronized
    fun append(frameBytes: ByteArray) {
        FileOutputStream(file, true).use { out ->
            val len = frameBytes.size
            out.write(byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(),
                (len ushr 8).toByte(), len.toByte(),
            ))
            out.write(frameBytes)
        }
    }

    companion object {
        /** Read all frames back (backfill / replay). */
        fun readAll(file: File): List<ByteArray> {
            val frames = mutableListOf<ByteArray>()
            RandomAccessFile(file, "r").use { raf ->
                while (raf.filePointer < raf.length()) {
                    val len = raf.readInt()
                    if (len <= 0 || len > 4 * 1024 * 1024) break
                    val buf = ByteArray(len)
                    raf.readFully(buf)
                    frames.add(buf)
                }
            }
            return frames
        }
    }
}
