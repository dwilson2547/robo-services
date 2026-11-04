package com.robo.phonegateway.registry

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

/**
 * A session's route (registry GET /api/app/sessions/{id}/track): one point per
 * snapshot interval with a GPS fix, with the vehicle's role values. Pure, so the
 * map's GeoJSON, colouring and tap lookup can be tested.
 */
data class RouteTrack(val points: List<Point>) {

    data class Point(
        val tS: Double, val lat: Double, val lon: Double,
        val kph: Double? = null, val rpm: Double? = null, val throttlePct: Double? = null,
        val brakeOn: Boolean? = null, val gear: Double? = null, val prndl: String? = null,
        val brakeKpa: Double? = null,
    )

    /** One colour class: its colour and legend label. */
    data class Band(val color: String, val label: String)

    /** What the route is coloured by. */
    enum class ColorBy(val label: String) {
        SPEED("Speed"), THROTTLE("Throttle"), BRAKE("Brake"), GEAR("Gear"), RPM("RPM");

        /** The legend, in order; GEAR lists the values this track has. */
        fun legend(units: Units, track: RouteTrack? = null): List<Band> = when (this) {
            SPEED -> speedBands(units).map { it.second }
            THROTTLE -> THROTTLE_BANDS.map { it.second }
            BRAKE -> if (track == null || track.points.any { it.brakeKpa != null }) BRAKE_BANDS.map { it.second }
                else listOf(BRAKE_OFF, BRAKE_ON)
            GEAR -> track?.points?.mapNotNull { gearBand(it) }?.distinct()?.sortedBy { GEAR_ORDER.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
                ?: emptyList()
            RPM -> RPM_BANDS.map { it.second }
        }

        /** The band a point falls in, or null where the vehicle gives no value. */
        fun band(p: Point, units: Units): Band? = when (this) {
            SPEED -> p.kph?.let { kph -> speedBands(units).first { kph < it.first }.second }
            THROTTLE -> p.throttlePct?.let { v -> THROTTLE_BANDS.first { v < it.first }.second }
            BRAKE -> p.brakeKpa?.let { v -> BRAKE_BANDS.first { v < it.first }.second }
                ?: p.brakeOn?.let { if (it) BRAKE_ON else BRAKE_OFF }
            GEAR -> gearBand(p)
            RPM -> p.rpm?.let { v -> RPM_BANDS.first { v < it.first }.second }
        }
    }

    val isEmpty: Boolean get() = points.size < 2

    /** Whether any point has a value to colour by (Gear needs a vehicle that reports it, etc.). */
    fun has(by: ColorBy): Boolean = points.any { by.band(it, Units.IMPERIAL) != null }

    /**
     * GeoJSON for the map: one LineString per run of consecutive points in the same
     * band (split where fixes are more than MAX_GAP_S apart), each with its band's
     * colour, plus start and end Points. Points with no value are grey.
     */
    fun geoJson(by: ColorBy = ColorBy.SPEED, units: Units = Units.IMPERIAL): String {
        val features = JSONArray()
        var run = mutableListOf<Point>()
        var band: Band? = null
        fun flush() {
            if (run.size >= 2) features.put(line(run, band ?: NO_VALUE))
        }
        for ((i, p) in points.withIndex()) {
            val prev = points.getOrNull(i - 1)
            val b = by.band(p, units) ?: NO_VALUE
            when {
                prev == null -> { run = mutableListOf(p); band = b }
                p.tS - prev.tS > MAX_GAP_S -> { flush(); run = mutableListOf(p); band = b }
                b != band -> { run.add(p); flush(); run = mutableListOf(p); band = b }
                else -> run.add(p)
            }
        }
        flush()
        if (points.isNotEmpty()) {
            features.put(marker(points.first(), "start", "#3FB950"))
            features.put(marker(points.last(), "end", "#FF7B72"))
        }
        return collection(features)
    }

    /** The point nearest (lat, lon), if within maxM metres. */
    fun nearest(lat: Double, lon: Double, maxM: Double): Point? {
        val k = cos(Math.toRadians(lat))
        fun dM(p: Point): Double {
            val dy = (p.lat - lat) * 111_195.0
            val dx = (p.lon - lon) * 111_195.0 * k
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        return points.minByOrNull(::dM)?.takeIf { dM(it) <= maxM }
    }

    /** South-west and north-east corners, or null with no points. */
    fun bounds(): Pair<Pair<Double, Double>, Pair<Double, Double>>? =
        if (points.isEmpty()) null
        else (points.minOf { it.lat } to points.minOf { it.lon }) to (points.maxOf { it.lat } to points.maxOf { it.lon })

    companion object {
        const val MAX_GAP_S = 5.0
        private val NO_VALUE = Band("#6E7681", "no value")
        private val BRAKE_OFF = Band("#2E7D32", "off")
        private val BRAKE_ON = Band("#E53935", "on")
        /**
         * Brake line pressure, kPa. The Impala rests at about 8 kPa with the pedal up and
         * brakes at 70–140 kPa (median 101) in the drive of 2026-11-03, so below 20 is off.
         */
        private val BRAKE_BANDS = listOf(
            20.0 to BRAKE_OFF, 70.0 to Band("#C0CA33", "light"), 110.0 to Band("#F9A825", "medium"),
            150.0 to Band("#EF6C00", "firm"), Double.MAX_VALUE to Band("#C62828", "hard"),
        )
        private val THROTTLE_BANDS = listOf(
            10.0 to Band("#1565C0", "<10%"), 30.0 to Band("#2E7D32", "10–30%"),
            60.0 to Band("#F9A825", "30–60%"), Double.MAX_VALUE to Band("#C62828", "60%+"),
        )
        private val RPM_BANDS = listOf(
            1500.0 to Band("#1565C0", "<1500"), 2500.0 to Band("#2E7D32", "1500–2500"),
            3500.0 to Band("#F9A825", "2500–3500"), Double.MAX_VALUE to Band("#C62828", "3500+"),
        )
        private val GEAR_ORDER = listOf("P", "R", "N", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10")
        private val GEAR_COLORS = mapOf(
            "P" to "#8B949E", "R" to "#E53935", "N" to "#D29922", "1" to "#1565C0", "2" to "#00897B",
            "3" to "#2E7D32", "4" to "#C0CA33", "5" to "#F9A825", "6" to "#EF6C00", "7" to "#AD1457", "8" to "#6A1B9A",
        )

        /** Upper bounds in km/h: 20/40/55 mph, or 30/60/90 km/h. */
        fun speedBands(units: Units): List<Pair<Double, Band>> = if (units == Units.IMPERIAL) listOf(
            20 * Units.KM_PER_MI to Band("#1565C0", "<20"), 40 * Units.KM_PER_MI to Band("#2E7D32", "20–40"),
            55 * Units.KM_PER_MI to Band("#F9A825", "40–55"), Double.MAX_VALUE to Band("#C62828", "55+"),
        ) else listOf(
            30.0 to Band("#1565C0", "<30"), 60.0 to Band("#2E7D32", "30–60"),
            90.0 to Band("#F9A825", "60–90"), Double.MAX_VALUE to Band("#C62828", "90+"),
        )

        /** P/R/N from the selector; in Drive (or with no selector) the gear number. */
        private fun gearBand(p: Point): Band? {
            val label = when {
                p.prndl != null && p.prndl != "D" -> p.prndl
                p.gear != null && p.gear in 1.0..10.0 -> p.gear.toInt().toString()
                else -> return null
            }
            return Band(GEAR_COLORS[label] ?: "#8B949E", label)
        }

        fun fromJson(text: String): RouteTrack {
            val o = JSONObject(text)
            val cols = o.getJSONArray("columns").let { c -> (0 until c.length()).map { c.getString(it) } }
            val rows = o.getJSONArray("rows")
            fun JSONArray.num(name: String): Double? = cols.indexOf(name).takeIf { it >= 0 && !isNull(it) }?.let { getDouble(it) }
            return RouteTrack((0 until rows.length()).map { i ->
                val r = rows.getJSONArray(i)
                Point(
                    tS = r.num("s")!!, lat = r.num("lat")!!, lon = r.num("lon")!!,
                    kph = r.num("speed_kph"), rpm = r.num("rpm"), throttlePct = r.num("throttle_pct"),
                    brakeOn = cols.indexOf("brake_on").takeIf { it >= 0 && !r.isNull(it) }?.let { r.getBoolean(it) },
                    gear = r.num("gear"),
                    prndl = cols.indexOf("prndl").takeIf { it >= 0 && !r.isNull(it) }?.let { r.getString(it) },
                    brakeKpa = r.num("brake_kpa"),
                )
            })
        }

        fun selectionJson(p: Point?): String =
            collection(JSONArray().apply { p?.let { put(marker(it, "selected", "#FFFFFF")) } })

        private fun collection(features: JSONArray) =
            JSONObject().put("type", "FeatureCollection").put("features", features).toString()

        private fun coords(p: Point) = JSONArray().put(p.lon).put(p.lat)

        private fun line(run: List<Point>, band: Band) = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("color", band.color).put("kind", "route"))
            .put("geometry", JSONObject().put("type", "LineString").put("coordinates", JSONArray(run.map { coords(it) })))

        private fun marker(p: Point, kind: String, color: String) = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject().put("kind", kind).put("color", color))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", coords(p)))
    }
}
