package com.robo.phonegateway.registry

import org.json.JSONArray
import org.json.JSONObject

/**
 * A session's route (registry GET /api/app/sessions/{id}/track): one point per
 * snapshot interval with a GPS fix. Pure, so the map's GeoJSON can be tested.
 */
data class RouteTrack(val points: List<Point>) {

    data class Point(val tS: Double, val lat: Double, val lon: Double, val kph: Double?)

    /** Speed bands the route is coloured by (mph, as the summary shows). */
    enum class Band(val maxMph: Double, val color: String, val label: String) {
        CRAWL(20.0, "#1565C0", "<20"),
        TOWN(40.0, "#2E7D32", "20–40"),
        ROAD(55.0, "#F9A825", "40–55"),
        FAST(Double.MAX_VALUE, "#C62828", "55+");

        companion object {
            fun of(kph: Double?): Band = entries.first { (kph ?: 0.0) / KM_PER_MI < it.maxMph }
        }
    }

    val isEmpty: Boolean get() = points.size < 2

    /**
     * GeoJSON for the map: one LineString per run of consecutive points in the same
     * speed band (split where fixes are more than MAX_GAP_S apart), each with its
     * band's colour, plus start and end Points.
     */
    fun geoJson(): String {
        val features = JSONArray()
        var run = mutableListOf<Point>()
        var band: Band? = null
        fun flush() {
            if (run.size >= 2) features.put(line(run, band!!))
        }
        for ((i, p) in points.withIndex()) {
            val prev = points.getOrNull(i - 1)
            val b = Band.of(p.kph)
            when {
                prev == null -> { run = mutableListOf(p); band = b }
                p.tS - prev.tS > MAX_GAP_S -> { flush(); run = mutableListOf(p); band = b }
                b != band -> { run.add(p); flush(); run = mutableListOf(p); band = b }
                else -> run.add(p)
            }
        }
        flush()
        if (points.isNotEmpty()) {
            features.put(marker(points.first(), "start"))
            features.put(marker(points.last(), "end"))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    /** South-west and north-east corners, or null with no points. */
    fun bounds(): Pair<Pair<Double, Double>, Pair<Double, Double>>? =
        if (points.isEmpty()) null
        else (points.minOf { it.lat } to points.minOf { it.lon }) to (points.maxOf { it.lat } to points.maxOf { it.lon })

    companion object {
        const val MAX_GAP_S = 5.0
        private const val KM_PER_MI = 1.609344

        fun fromJson(text: String): RouteTrack {
            val arr = JSONObject(text).getJSONArray("points")
            return RouteTrack((0 until arr.length()).map { i ->
                val p = arr.getJSONArray(i)
                Point(p.getDouble(0), p.getDouble(1), p.getDouble(2), if (p.isNull(3)) null else p.getDouble(3))
            })
        }

        private fun coords(p: Point) = JSONArray().put(p.lon).put(p.lat)

        private fun line(run: List<Point>, band: Band) = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("color", band.color).put("kind", "route"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray(run.map { coords(it) })))

        private fun marker(p: Point, kind: String) = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("kind", kind).put("color", if (kind == "start") "#2E7D32" else "#C62828"))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", coords(p)))
    }
}
