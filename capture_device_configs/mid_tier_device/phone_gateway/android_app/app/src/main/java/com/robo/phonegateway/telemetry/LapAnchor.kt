package com.robo.phonegateway.telemetry

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/**
 * The "lap_anchor" event the lap-segmentation Flink job waits for before it
 * starts counting laps (derivations/lap_flink_job; docs/lap_segmentation_pipeline.md).
 *
 * Sent as a JSON packet on the same MQTT topic as the frames. kreceiver's JSON
 * path routes it by `source` (contains "gps") to telemetry.raw.gps and keys it
 * `source_session:device_id`; `source_session` is "proto-live" because that is
 * the session kreceiver gives this app's protobuf frames, so the anchor and the
 * GPS fixes reach the same lap-job state. Field names match the lap job's
 * default profile (latitude, longitude, ground_speed_kph).
 */
data class LapAnchor(
    val deviceId: String,
    val capturedAtMs: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val accuracyM: Float?,
) {
    fun toJson(): String = JSONObject().apply {
        put("source", "phone-gateway:gps")
        put("device_id", deviceId)
        put("source_session", SOURCE_SESSION)
        put("message_type", "lap_anchor")
        put("captured_at", Instant.ofEpochMilli(capturedAtMs).toString())
        put("latitude", latDeg)
        put("longitude", lonDeg)
        put("ground_speed_kph", 0.0)
        accuracyM?.let { put("accuracy_m", it.toDouble()) }
    }.toString()

    fun describe(): String {
        val acc = accuracyM?.let { String.format(Locale.US, " ±%.0f m", it) } ?: ""
        return String.format(Locale.US, "sent %.6f, %.6f%s at %s", latDeg, lonDeg, acc,
            Instant.ofEpochMilli(capturedAtMs).toString().substring(11, 19) + "Z")
    }

    companion object {
        const val SOURCE_SESSION = "proto-live"
        /** A fix older than this is not where the car is now. */
        const val MAX_FIX_AGE_MS = 3_000L
    }
}
