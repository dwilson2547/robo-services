package com.robo.phonegateway.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.robo.phonegateway.GatewayService
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.auth.UploadCredentials
import com.robo.phonegateway.log.FrameLog
import com.robo.phonegateway.registry.RegistryClient
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.session.Outbox
import racelogger.v1.Telemetry.TelemetryFrame
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background catch-up (docs/plans/2026-11-04-reliable-uploads.md §1): once there is a
 * network, with or without the app open, send the outbox's session events and every
 * logged frame the pipeline lacks. WorkManager keeps the job across reboots and
 * retries it with backoff until every session is done (or given up on).
 *
 * Scheduled by Stop and by the app's start; the session being recorded is left to
 * the live publisher.
 */
class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val auth = Auth.get(ctx)
        val uploads = UploadCredentials.get(ctx)
        if (uploads.subject() == null) {
            Log.i(TAG, "not signed in; trying again later")
            return Result.retry()
        }
        val frameDir = File(ctx.filesDir, "frames")
        val stateDir = File(ctx.filesDir, "catchup").apply { mkdirs() }
        val outbox = Outbox(File(ctx.filesDir, "outbox"))
        val recording = GatewayStatus.snapshot.value.let { if (it.running) it.sessionId else null }
        val sessions = (frameDir.listFiles() ?: emptyArray())
            .mapNotNull { f -> CatchUp.sessionIdOf(f)?.let { it to f } }
            .filter { (id, _) -> id != recording && !state(stateDir, id).done }
            .sortedBy { it.first }
        if (sessions.isEmpty() && outbox.pending().isEmpty()) return Result.success()

        val sender = CatchUpSender.connect(uploads, GatewayService.deviceId(ctx)) ?: run {
            Log.i(TAG, "broker unreachable or sign-in unavailable; trying again later")
            return Result.retry()
        }
        val registry = RegistryClient(auth, uploadToken = uploads::registryToken)
        val notice = CatchUpNotice(ctx)
        var unfinished = 0
        var sentSeconds = 0
        var sentDrives = 0
        try {
            // session events first: a session's end is what lets the registry call it done
            for ((name, json) in outbox.pending()) {
                sender.send(listOf(json.toByteArray(Charsets.UTF_8)))
                outbox.remove(name)
            }
            for ((id, file) in sessions) {
                val have = registry.sessionFrames(id) // null: the registry doesn't know it yet, so it has none
                val logged = FrameLog.read(file).mapNotNull { bytes ->
                    runCatching { TelemetryFrame.parseFrom(bytes).baseTimestampUs }.getOrNull()?.let { it to bytes }
                }
                val missing = CatchUp.missing(logged, have?.bases ?: emptySet())
                val before = state(stateDir, id)
                when (CatchUp.decide(missing.size, have?.ended == true, before)) {
                    CatchUp.Action.DONE -> {
                        save(stateDir, id, before.copy(done = true))
                        Log.i(TAG, "session $id: complete (${logged.size} frames)")
                    }
                    CatchUp.Action.GIVE_UP -> {
                        save(stateDir, id, before.copy(done = true))
                        Log.w(TAG, "session $id: giving up with ${missing.size} frames the pipeline never took")
                    }
                    CatchUp.Action.WAIT -> unfinished++
                    CatchUp.Action.SEND -> {
                        notice.progress(id, 0, missing.size)
                        sender.send(missing.map { it.second }) { n -> notice.progress(id, n, missing.size) }
                        sentSeconds += missing.size
                        sentDrives++
                        save(stateDir, id, before.afterSend(missing.size))
                        Log.i(TAG, "session $id: sent ${missing.size} of ${logged.size} frames")
                        unfinished++ // done once the registry shows them
                    }
                }
            }
        } catch (e: RegistryException) {
            Log.i(TAG, "registry: ${e.message}; trying again later")
            return Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "catch-up interrupted; trying again later", e)
            return Result.retry()
        } finally {
            sender.close()
            if (sentDrives > 0) notice.done(sentSeconds, sentDrives) else notice.clear()
        }
        return if (unfinished == 0) Result.success() else Result.retry()
    }

    private fun state(dir: File, id: Long): CatchUp.State =
        File(dir, "$id.json").takeIf { it.exists() }?.let { CatchUp.State.fromJson(it.readText()) } ?: CatchUp.State()

    private fun save(dir: File, id: Long, s: CatchUp.State) = File(dir, "$id.json").writeText(s.toJson())

    companion object {
        private const val TAG = "CatchUp"
        private const val WORK = "upload-catch-up"

        /**
         * Queue a run once there is a network. [delayMs] lets a just-stopped session's
         * last frames clear the live publisher first. Appended after a run in progress,
         * so a session stopped meanwhile is still seen.
         */
        fun schedule(context: Context, delayMs: Long = 0) {
            val request = OneTimeWorkRequestBuilder<CatchUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
