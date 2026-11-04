package com.robo.phonegateway.registry

import org.json.JSONArray
import org.json.JSONObject

/** A track's outline: its GeoJSON as lines of (lat, lon). */
object Outline {
    fun lines(geometry: JSONObject?): List<List<Pair<Double, Double>>> {
        val g = geometry?.optJSONObject("geometry") ?: geometry ?: return emptyList()
        val c = g.optJSONArray("coordinates") ?: return emptyList()
        return when (g.optString("type")) {
            "LineString" -> listOf(line(c))
            "MultiLineString", "Polygon" -> (0 until c.length()).map { line(c.getJSONArray(it)) }
            "MultiPolygon" -> (0 until c.length()).flatMap { i -> c.getJSONArray(i).let { p -> (0 until p.length()).map { line(p.getJSONArray(it)) } } }
            else -> emptyList()
        }.filter { it.size >= 2 }
    }

    private fun line(a: JSONArray) = (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(1) to p.getDouble(0) } }
}

/** A track as the phone lists it (registry GET /api/app/tracks). */
data class AppTrack(
    val id: Int,
    val name: String,
    val center: Pair<Double, Double>?,
    val distanceKm: Double?,
    val hasStartLine: Boolean,
    val addedByMe: Boolean,
    val geometry: JSONObject?,
    val startLine: JSONObject? = null,
) {
    val outline: List<List<Pair<Double, Double>>> by lazy { Outline.lines(geometry) }

    companion object {
        fun listFromJson(text: String): List<AppTrack> = JSONArray(text).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }

        fun fromJson(o: JSONObject) = AppTrack(
            id = o.getInt("id"),
            name = o.getString("name"),
            center = o.optJSONArray("center")?.let { it.getDouble(0) to it.getDouble(1) },
            distanceKm = if (o.isNull("distance_km")) null else o.getDouble("distance_km"),
            hasStartLine = o.optBoolean("has_start_line"),
            addedByMe = o.optBoolean("added_by_me"),
            geometry = o.optJSONObject("geometry"),
            startLine = o.optJSONObject("start_line"),
        )
    }
}

/** An OSM track found by a search, not yet (necessarily) a registry track. */
data class TrackCandidate(
    val index: Int,
    val name: String,
    val distanceKm: Double?,
    val alreadyImported: Boolean,
    val geometry: JSONObject?,
) {
    val outline: List<List<Pair<Double, Double>>> by lazy { Outline.lines(geometry) }
}

/** A search's results: the token ingest needs, where it searched, and what it found. */
data class TrackSearch(val token: String, val originLabel: String, val candidates: List<TrackCandidate>) {
    companion object {
        fun fromJson(text: String): TrackSearch {
            val o = JSONObject(text)
            val c = o.getJSONArray("candidates")
            return TrackSearch(
                token = o.getString("token"),
                originLabel = o.optString("origin_label"),
                candidates = (0 until c.length()).map { i ->
                    val x = c.getJSONObject(i)
                    TrackCandidate(
                        index = x.getInt("index"), name = x.getString("name"),
                        distanceKm = if (x.isNull("distance_km")) null else x.getDouble("distance_km"),
                        alreadyImported = x.optBoolean("already_imported"), geometry = x.optJSONObject("geometry"),
                    )
                },
            )
        }
    }
}
