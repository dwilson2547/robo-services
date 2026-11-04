package com.robo.phonegateway.upload

import org.json.JSONObject
import java.io.File

/**
 * The decisions of the background catch-up (docs/plans/2026-11-04-reliable-uploads.md §1),
 * kept free of Android so they can be tested.
 *
 * The registry is the source of truth for what reached the pipeline: it keeps one
 * row per frame per session. A session's frame log is compared with that, and only
 * the frames the pipeline lacks are sent again. Re-sent frames that did arrive after
 * all are harmless: session-tracker counts a frame once, and vehicle-state ignores a
 * second it has already written.
 */
object CatchUp {

    /** Gives up on a session after this many sends that did not reduce what is missing. */
    const val MAX_FRUITLESS_SENDS = 5

    private val SESSION_LOG = Regex("""session-(\d+)\.bin""")

    /** The session id of a frame log, or null for anything else (old day logs included). */
    fun sessionIdOf(file: File): Long? = SESSION_LOG.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()

    /** Logged frames the pipeline does not have, oldest first, each second once. */
    fun <T> missing(logged: List<Pair<Long, T>>, have: Set<Long>): List<Pair<Long, T>> =
        logged.filter { it.first !in have }.distinctBy { it.first }.sortedBy { it.first }

    enum class Action { DONE, SEND, WAIT, GIVE_UP }

    /**
     * What to do with a session this run.
     * - DONE: the pipeline has every logged frame and the session has ended there.
     * - SEND: frames are missing.
     * - WAIT: nothing is missing but the end hasn't arrived (it goes from the outbox).
     * - GIVE_UP: [MAX_FRUITLESS_SENDS] sends in a row left as much missing as before.
     */
    fun decide(missing: Int, ended: Boolean, state: State): Action = when {
        missing == 0 && ended -> Action.DONE
        state.fruitless >= MAX_FRUITLESS_SENDS -> Action.GIVE_UP
        missing == 0 -> Action.WAIT
        else -> Action.SEND
    }

    /** Per-session progress, kept in a small JSON file between runs. */
    data class State(val done: Boolean = false, val lastMissing: Int? = null, val fruitless: Int = 0) {
        /** After sending [missing] frames: a send that left no fewer missing than last time is fruitless. */
        fun afterSend(missing: Int): State =
            copy(lastMissing = missing, fruitless = if (lastMissing != null && missing >= lastMissing) fruitless + 1 else 0)

        fun toJson(): String = JSONObject().put("done", done).put("fruitless", fruitless)
            .apply { lastMissing?.let { put("last_missing", it) } }.toString()

        companion object {
            fun fromJson(text: String): State = runCatching {
                val o = JSONObject(text)
                State(o.optBoolean("done"), if (o.has("last_missing")) o.getInt("last_missing") else null, o.optInt("fruitless"))
            }.getOrDefault(State())
        }
    }
}
