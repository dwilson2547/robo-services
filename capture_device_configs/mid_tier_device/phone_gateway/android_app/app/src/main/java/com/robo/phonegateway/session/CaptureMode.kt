package com.robo.phonegateway.session

import android.content.Context

/** Trip or race, chosen on the Drive tab before Start; the session_start carries it. */
object CaptureMode {
    const val TRIP = "trip"
    const val RACE = "race"
    private const val PREFS = "sessions"
    private const val KEY = "mode"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, TRIP) ?: TRIP

    fun set(context: Context, mode: String) {
        require(mode == TRIP || mode == RACE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }
}
