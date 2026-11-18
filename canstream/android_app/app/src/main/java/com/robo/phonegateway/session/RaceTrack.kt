package com.robo.phonegateway.session

import android.content.Context

/** The track picked for race mode on the Drive tab; session_start carries its id. */
object RaceTrack {
    private const val PREFS = "sessions"

    fun get(context: Context): Pair<Int, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt("race_track_id", 0)
        return if (id == 0) null else id to (p.getString("race_track_name", null) ?: "track $id")
    }

    fun set(context: Context, track: Pair<Int, String>?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (track == null) remove("race_track_id").remove("race_track_name")
            else putInt("race_track_id", track.first).putString("race_track_name", track.second)
        }.apply()
    }
}
