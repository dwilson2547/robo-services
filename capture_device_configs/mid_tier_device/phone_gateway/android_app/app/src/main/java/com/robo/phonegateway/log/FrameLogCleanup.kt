package com.robo.phonegateway.log

import android.content.Context
import android.util.Log
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.registry.RegistryClient
import java.io.File

/**
 * Deletes frame logs the pipeline no longer needs (decided 2026-11-03): a session's
 * log is kept until the registry says the session is complete, then 30 days more.
 * Incomplete, unterminated and unknown sessions keep theirs (a re-send may need
 * them) until FrameLog.trim's 5 GB backstop.
 */
object FrameLogCleanup {
    const val KEEP_DAYS = 30L
    private const val TAG = "FrameLogCleanup"
    private const val PAGE = 200
    private val NAME = Regex("""session-(\d+)\.bin""")

    data class LogFile(val sessionId: Long, val bytes: Long)

    /** What the registry says about a session. */
    data class Known(val state: String, val endedAtMs: Long?)

    data class Result(val deleted: Int, val deletedBytes: Long, val kept: Int, val keptBytes: Long)

    fun dir(context: Context) = File(context.filesDir, "frames")

    fun list(dir: File): List<LogFile> = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
        NAME.matchEntire(f.name)?.groupValues?.get(1)?.toLongOrNull()?.let { LogFile(it, f.length()) }
    }.sortedBy { it.sessionId }

    /** The logs to delete: complete sessions that ended KEEP_DAYS or more ago, never one being recorded. */
    fun toDelete(logs: List<LogFile>, known: Map<Long, Known>, nowMs: Long, recording: Set<Long>): List<LogFile> =
        logs.filter { log ->
            val k = known[log.sessionId] ?: return@filter false
            log.sessionId !in recording && k.state == "complete" && k.endedAtMs != null &&
                nowMs - k.endedAtMs >= KEEP_DAYS * 86_400_000L
        }

    /** Look the logs' sessions up in the registry and delete what toDelete says. */
    suspend fun run(context: Context, registry: RegistryClient, nowMs: Long = System.currentTimeMillis()): Result {
        val dir = dir(context)
        val logs = list(dir)
        if (logs.isEmpty()) return Result(0, 0, 0, 0)
        val oldest = logs.first().sessionId
        val known = mutableMapOf<Long, Known>()
        var offset = 0
        while (true) {
            val page = registry.sessions(PAGE, offset)
            page.forEach { known[it.sessionId] = Known(it.state, it.endedAt?.toEpochMilli()) }
            // newest first: stop once the page reaches back past the oldest log
            if (page.size < PAGE || page.last().sessionId <= oldest) break
            offset += PAGE
        }
        val status = GatewayStatus.snapshot.value
        val recording = if (status.running) setOf(status.sessionId) else emptySet()
        val delete = toDelete(logs, known, nowMs, recording)
        delete.forEach { log ->
            if (File(dir, FrameLog.fileName(log.sessionId)).delete()) {
                Log.i(TAG, "deleted frame log of session ${log.sessionId} (${log.bytes} bytes): complete, ended over $KEEP_DAYS days ago")
            }
        }
        val kept = logs - delete.toSet()
        return Result(delete.size, delete.sumOf { it.bytes }, kept.size, kept.sumOf { it.bytes })
    }
}
