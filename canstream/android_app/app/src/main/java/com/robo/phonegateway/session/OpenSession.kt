package com.robo.phonegateway.session

import com.robo.phonegateway.log.FrameLog
import racelogger.v1.Telemetry.TelemetryFrame
import java.io.File

/**
 * Marker for the capture in progress: written at start, deleted once its
 * session_end is in the outbox. Still present at the next start means the app was
 * killed mid-capture, and [recover] rebuilds that session's end from the frame log.
 */
class OpenSession(private val file: File) {

    data class Marker(val sessionId: Long, val deviceId: String)

    fun write(marker: Marker) {
        file.parentFile?.mkdirs()
        file.writeText("${marker.sessionId}\n${marker.deviceId}\n")
    }

    fun read(): Marker? = runCatching {
        val (id, device) = file.readLines()
        Marker(id.trim().toLong(), device.trim())
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    companion object {
        /**
         * session_end for a session the app never closed: frames counted from its
         * log file; the loss counters it kept in memory are gone.
         */
        fun recover(marker: Marker, frameDir: File, nowMs: Long): SessionEvents.End {
            var frames = 0L
            var first: Long? = null
            var last: Long? = null
            val file = File(frameDir, FrameLog.fileName(marker.sessionId))
            val records = if (file.exists()) FrameLog.read(file) else emptyList()
            for (bytes in records) {
                val frame = runCatching { TelemetryFrame.parseFrom(bytes) }.getOrNull() ?: continue
                if (frame.sessionId != marker.sessionId) continue
                frames++
                val t = frame.baseTimestampUs
                first = minOf(first ?: t, t)
                last = maxOf(last ?: t, t)
            }
            return SessionEvents.End(
                deviceId = marker.deviceId,
                sessionId = marker.sessionId,
                endedAtMs = last?.let { it / 1000 + 1000 } ?: nowMs,
                frames = frames,
                firstFrameUs = first,
                lastFrameUs = last,
                imuSecondsDropped = null,
                imuPointsFilled = null,
                lateSamples = null,
                dongleCanDrops = null,
                dongleImuDrops = null,
                reason = "app_killed_recovered",
            )
        }
    }
}
