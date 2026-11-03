package com.robo.phonegateway.session

import com.robo.phonegateway.log.FrameLog
import racelogger.v1.Telemetry.TelemetryFrame
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

        /**
         * session_end for a session the app never closed: frames counted from the log
         * files on or after its start day; the loss counters it kept in memory are gone.
         */
        fun recover(marker: Marker, frameDir: File, nowMs: Long): SessionEvents.End {
            val startDay = DAY.format(Instant.ofEpochMilli(marker.sessionId))
            var frames = 0L
            var first: Long? = null
            var last: Long? = null
            val files = (frameDir.listFiles { f -> f.name.startsWith("frames-") && f.name.endsWith(".bin") } ?: emptyArray())
                .filter { it.name.removePrefix("frames-").removeSuffix(".bin") >= startDay }
                .sortedBy { it.name }
            for (file in files) {
                for (bytes in FrameLog.read(file)) {
                    val frame = runCatching { TelemetryFrame.parseFrom(bytes) }.getOrNull() ?: continue
                    if (frame.sessionId != marker.sessionId) continue
                    frames++
                    val t = frame.baseTimestampUs
                    first = minOf(first ?: t, t)
                    last = maxOf(last ?: t, t)
                }
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
