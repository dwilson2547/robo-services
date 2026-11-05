package com.robo.phonegateway.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.robo.phonegateway.MainActivity
import com.robo.phonegateway.Notices

/**
 * The background catch-up's notification: a progress bar while it sends a drive, then
 * a summary that clears itself. Nothing is shown when a run has nothing to send.
 */
class CatchUpNotice(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW))
    }

    fun progress(sessionId: Long, sent: Int, total: Int) = show(
        builder().setContentText(Notices.catchUpProgress(sessionId, sent, total))
            .setProgress(total, sent, false).setOngoing(true).build(),
    )

    fun done(seconds: Int, drives: Int) = show(
        builder().setContentText(Notices.catchUpDone(seconds, drives))
            .setAutoCancel(true).setTimeoutAfter(DONE_SHOWN_MS).build(),
    )

    fun clear() = manager.cancel(ID)

    private fun show(n: Notification) {
        if (manager.areNotificationsEnabled()) manager.notify(ID, n)
    }

    private fun builder() = Notification.Builder(context, CHANNEL)
        .setContentTitle("Phone Gateway")
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )

    companion object {
        private const val CHANNEL = "uploads"
        private const val ID = 2
        private const val DONE_SHOWN_MS = 5 * 60_000L
    }
}
