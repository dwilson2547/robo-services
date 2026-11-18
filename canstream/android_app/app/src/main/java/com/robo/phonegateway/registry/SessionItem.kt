package com.robo.phonegateway.registry

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One of the user's sessions in the history list (registry GET /api/app/sessions). */
data class SessionItem(
    val sessionId: Long,
    val startedAt: Instant?,
    val endedAt: Instant? = null,
    val state: String,
    val completenessPct: Double?,
    val vehicleName: String?,
    val distanceKm: Double?,
    val durationS: Double?,
    val avgMovingKph: Double?,
) {
    val hasSummary: Boolean get() = distanceKm != null || durationS != null

    fun title(zone: ZoneId = ZoneId.systemDefault()): String =
        (startedAt?.let { TITLE.withZone(zone).format(it) } ?: "session $sessionId") +
            (vehicleName?.let { " · $it" } ?: "")

    /** The line under the title: the summary headline, or why there is none. */
    fun subtitle(units: Units = Units.IMPERIAL): String = if (hasSummary) {
        listOfNotNull(
            distanceKm?.let { units.distance(it) },
            durationS?.let { DriveSummary.duration(it) },
            avgMovingKph?.let { "avg ${units.speed(it)}" },
        ).joinToString(" · ")
    } else {
        when (state) {
            "open" -> "recording"
            "complete", "incomplete" -> "no summary" + (completenessPct?.let { " · ${it.toInt()}% arrived" } ?: "")
            else -> state
        }
    }

    companion object {
        private val TITLE = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

        fun listFromJson(text: String): List<SessionItem> {
            val arr = JSONArray(text)
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }

        fun fromJson(o: JSONObject): SessionItem {
            val s = o.optJSONObject("summary")
            return SessionItem(
                sessionId = o.getString("source_session").toLong(),
                startedAt = o.optString("started_at").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) },
                endedAt = o.optString("ended_at").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) },
                state = o.getString("state"),
                completenessPct = o.num("completeness_pct"),
                vehicleName = o.optString("vehicle_name").takeIf { !o.isNull("vehicle_name") && it.isNotEmpty() },
                distanceKm = s?.num("distance_km"),
                durationS = s?.num("duration_s"),
                avgMovingKph = s?.num("avg_moving_kph"),
            )
        }

        private fun JSONObject.num(key: String): Double? =
            if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
    }
}
