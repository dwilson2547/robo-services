package com.robo.phonegateway.registry

import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * A session's drive summary as the registry serves it (drive-summary metrics,
 * docs/plans/2026-11-03-trip-pipelines.md §4), read leniently: a metric the
 * vehicle can't provide is null and its line is left out. Shown in mi, mph, °F.
 */
data class DriveSummary(
    val sessionId: String,
    val version: Int,
    val framesUsed: Int,
    val framesReceived: Int,
    val durationS: Double?,
    val movingS: Double?,
    val stops: Int?,
    val gpsKm: Double?,
    val canKm: Double?,
    val odometerKm: Double?,
    val avgMovingKph: Double?,
    val maxKph: Double?,
    val maxRpm: Double?,
    val selectorS: Map<String, Double>,
    val gearsS: Map<String, Double>,
    val shifts: Int?,
    val brakeApplications: Int?,
    val hardBraking: Int?,
    val hardAcceleration: Int?,
    val sharpCornering: Int?,
    val peakBrakingG: Double?,
    val peakAccelerationG: Double?,
    val peakLateralG: Double?,
    val coolantStartC: Double?,
    val coolantMaxC: Double?,
    val coolantEndC: Double?,
    val completenessPct: Double?,
    val uploadLag: Lag? = null,
    val pipelineLag: Lag? = null,
    val summaryAfterEndS: Double? = null,
) {
    /** Seconds behind real time over a drive's frames (docs/plans/2026-11-03-processing-lag.md §2). */
    data class Lag(val p50: Double, val p95: Double, val max: Double) {
        fun text() = "median ${secs(p50)}, 95% ${secs(p95)}, max ${secs(max)}"

        private fun secs(x: Double) = if (x < 60) "${String.format(java.util.Locale.US, "%.1f", x)} s" else duration(x)
    }

    /** The headline numbers as (value, label) tiles; "–" where the vehicle can't say. */
    fun tiles(units: Units = Units.IMPERIAL): List<Pair<String, String>> = listOf(
        (gpsKm?.let { units.distance(it) } ?: "–") to "distance",
        (movingS?.let { duration(it) } ?: "–") to "moving",
        (avgMovingKph?.let { units.speed(it) } ?: "–") to "avg speed",
        (maxKph?.let { units.speed(it) } ?: "–") to "top speed",
        (stops?.toString() ?: "–") to "stops",
        (brakeApplications?.toString() ?: "–") to "brake applications",
    )

    /** Label / value lines for the card, in display order; missing metrics are left out. */
    fun lines(units: Units = Units.IMPERIAL): List<Pair<String, String>> = buildList {
        gpsKm?.let { gps ->
            val beside = listOfNotNull(canKm?.let { "CAN ${units.distance(it)}" }, odometerKm?.let { "odometer ${units.distance(it)}" })
            add("Distance" to units.distance(gps) + if (beside.isEmpty()) "" else " (${beside.joinToString(", ")})")
        }
        durationS?.let { d -> add("Time" to duration(d) + (movingS?.let { ", moving ${duration(it)}" } ?: "")) }
        if (avgMovingKph != null || maxKph != null) {
            add("Speed" to listOfNotNull(avgMovingKph?.let { "avg ${units.speed(it)} moving" }, maxKph?.let { "max ${units.speed(it)}" })
                .joinToString(", "))
        }
        val counts = listOfNotNull(
            stops?.let { "$it stops" }, brakeApplications?.let { "$it brake applications" }, shifts?.let { "$it shifts" },
        )
        if (counts.isNotEmpty()) add("Counts" to counts.joinToString(", "))
        if (selectorS.isNotEmpty()) {
            add("Selector" to SELECTOR_ORDER.filter { it in selectorS }.joinToString(", ") { "$it ${duration(selectorS.getValue(it))}" })
        }
        if (gearsS.isNotEmpty()) {
            add("Gears" to gearsS.entries.sortedBy { it.key.toIntOrNull() ?: 99 }.joinToString(", ") { "${it.key}: ${duration(it.value)}" })
        }
        if (hardBraking != null) {
            add("Hard events" to "braking $hardBraking, accel ${hardAcceleration ?: 0}, cornering ${sharpCornering ?: 0}")
        }
        val peaks = listOfNotNull(
            peakBrakingG?.let { "braking ${g(it)}" }, peakAccelerationG?.let { "accel ${g(it)}" },
            peakLateralG?.let { "cornering ${g(it)}" },
        )
        if (peaks.isNotEmpty()) add("Peaks" to peaks.joinToString(", "))
        maxRpm?.let { add("Engine" to "max ${it.roundToLong()} rpm") }
        if (coolantStartC != null && coolantEndC != null) {
            add("Coolant" to "${units.temperatureValue(coolantStartC)} → ${units.temperature(coolantEndC)}" +
                (coolantMaxC?.let { " (max ${units.temperatureValue(it)})" } ?: ""))
        }
        add("Data" to "$framesUsed of $framesReceived frames" + (completenessPct?.let { ", ${fmt1(it)}% complete" } ?: ""))
        uploadLag?.let { add("Upload lag" to it.text()) }
        pipelineLag?.let { add("Processing lag" to it.text()) }
        summaryAfterEndS?.let { add("Summary ready" to "${duration(it)} after the drive ended") }
    }

    companion object {
        private val SELECTOR_ORDER = listOf("P", "R", "N", "D", "L")

        fun fromJson(text: String): DriveSummary {
            val o = JSONObject(text)
            val m = o.getJSONObject("metrics")
            val dist = m.optJSONObject("distance_km")
            val speed = m.optJSONObject("speed_kph")
            val events = m.optJSONObject("events")
            val peaks = m.optJSONObject("peaks_g")
            val coolant = m.optJSONObject("temperatures_c")?.optJSONObject("coolant")
            val processing = m.optJSONObject("processing")
            return DriveSummary(
                sessionId = o.getString("source_session"),
                version = o.getInt("version"),
                framesUsed = o.getInt("frames_used"),
                framesReceived = o.getInt("frames_received"),
                durationS = m.num("duration_s"),
                movingS = m.num("moving_s"),
                stops = m.int("stops"),
                gpsKm = dist?.num("gps"),
                canKm = dist?.num("can"),
                odometerKm = dist?.num("odometer"),
                avgMovingKph = speed?.num("avg_moving"),
                maxKph = speed?.num("max_can") ?: speed?.num("max_gps"),
                maxRpm = m.optJSONObject("engine")?.num("max_rpm"),
                selectorS = m.optJSONObject("selector_s").toDoubles(),
                gearsS = m.optJSONObject("gears_s").toDoubles(),
                shifts = m.int("shifts"),
                brakeApplications = m.optJSONObject("brakes")?.int("applications"),
                hardBraking = events?.int("hard_braking"),
                hardAcceleration = events?.int("hard_acceleration"),
                sharpCornering = events?.int("sharp_cornering"),
                peakBrakingG = peaks?.num("braking"),
                peakAccelerationG = peaks?.num("acceleration"),
                peakLateralG = peaks?.num("lateral"),
                coolantStartC = coolant?.num("start"),
                coolantMaxC = coolant?.num("max"),
                coolantEndC = coolant?.num("end"),
                completenessPct = m.optJSONObject("quality")?.num("completeness_pct"),
                uploadLag = processing?.optJSONObject("upload_lag_s").lag(),
                pipelineLag = processing?.optJSONObject("pipeline_lag_s").lag(),
                summaryAfterEndS = processing?.num("summary_after_end_s"),
            )
        }

        private fun JSONObject.num(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
        private fun JSONObject.int(key: String): Int? = num(key)?.toInt()
        private fun JSONObject?.lag(): Lag? {
            val o = this ?: return null
            return Lag(o.num("p50") ?: return null, o.num("p95") ?: return null, o.num("max") ?: return null)
        }
        private fun JSONObject?.toDoubles(): Map<String, Double> =
            this?.keys()?.asSequence()?.mapNotNull { k -> num(k)?.let { k to it } }?.toMap() ?: emptyMap()

        private fun fmt1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)
        private fun fmt2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)
        fun g(x: Double) = fmt2(x) + " g"

        fun duration(seconds: Double): String {
            val s = seconds.roundToLong()
            return when {
                s >= 3600 -> "${s / 3600}h${"%02d".format((s % 3600) / 60)}m"
                s >= 60 -> "${s / 60}m${"%02d".format(s % 60)}s"
                else -> "${s}s"
            }
        }
    }
}
