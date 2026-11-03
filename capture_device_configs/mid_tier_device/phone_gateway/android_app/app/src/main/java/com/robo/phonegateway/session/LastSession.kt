package com.robo.phonegateway.session

import android.content.Context

/** The last capture that finished, so its drive summary can be shown after a restart. */
object LastSession {
    private const val PREFS = "sessions"
    private const val KEY = "last_finished"

    fun get(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0)

    fun set(context: Context, sessionId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, sessionId).apply()
    }
}
