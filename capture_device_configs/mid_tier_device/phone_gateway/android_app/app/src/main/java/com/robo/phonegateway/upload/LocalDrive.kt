package com.robo.phonegateway.upload

import android.content.Context
import com.robo.phonegateway.log.FrameLog
import com.robo.phonegateway.registry.TrackCache
import com.robo.phonegateway.session.Outbox
import java.io.File

/** What the phone keeps of a drive, and removing it once the drive is deleted. */
object LocalDrive {
    fun frameDir(context: Context) = File(context.filesDir, "frames")

    /**
     * Forget a deleted drive on this phone: its frame log (else the background catch-up
     * would find it missing from the registry and upload it all again), its catch-up
     * progress, its queued session events and its cached route.
     */
    fun forget(context: Context, sessionId: Long) {
        File(frameDir(context), FrameLog.fileName(sessionId)).delete()
        File(File(context.filesDir, "catchup"), "$sessionId.json").delete()
        Outbox(File(context.filesDir, "outbox")).apply {
            remove(Outbox.startName(sessionId))
            remove(Outbox.endName(sessionId))
        }
        TrackCache.forget(context, sessionId)
    }
}
