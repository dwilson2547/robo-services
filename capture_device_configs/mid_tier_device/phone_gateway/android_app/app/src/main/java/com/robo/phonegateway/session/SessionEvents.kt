package com.robo.phonegateway.session

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * session_start / session_end JSON events (docs/plans/2026-11-03-recording-sessions.md).
 *
 * Sent on the frames' MQTT topic. kreceiver's JSON path routes any `source`
 * containing "session" to telemetry.session (never to the sensor topics) and keys
 * it `source_session:device_id`, the same key it gives this session's frames
 * (whose TelemetryFrame.session_id is [sessionId]).
 */
object SessionEvents {
    const val SOURCE = "phone-gateway:session"

    data class Start(
        val deviceId: String,
        /** Capture start, epoch ms; also the session id. */
        val sessionId: Long,
        val mode: String,
        val canFilter: List<Int>,
        /** Dongle name and BLE address if connected at start, else null. */
        val dongle: String?,
        val appVersion: String,
        val phone: String,
    ) {
        fun toJson(): String = base(deviceId, sessionId, "session_start", sessionId).apply {
            put("mode", mode)
            put("vehicle_id", JSONObject.NULL) // vehicles: later phase
            put("track_id", JSONObject.NULL) // track selection: later phase
            put("can_filter", JSONArray(canFilter.map { "0x" + it.toString(16).uppercase() }))
            put("dongle", dongle ?: JSONObject.NULL)
            put("app_version", appVersion)
            put("phone", phone)
        }.toString()
    }

    data class End(
        val deviceId: String,
        val sessionId: Long,
        val endedAtMs: Long,
        /** Frames the app built and logged for the session. */
        val frames: Long,
        val firstFrameUs: Long?,
        val lastFrameUs: Long?,
        val imuSecondsDropped: Long?,
        val imuPointsFilled: Long?,
        val lateSamples: Long?,
        val dongleCanDrops: Long?,
        val dongleImuDrops: Long?,
        /** user_stop | app_killed_recovered */
        val reason: String,
    ) {
        fun toJson(): String = base(deviceId, sessionId, "session_end", endedAtMs).apply {
            put("frames", frames)
            put("first_frame_us", firstFrameUs ?: JSONObject.NULL)
            put("last_frame_us", lastFrameUs ?: JSONObject.NULL)
            // null where the app could not know (a recovered session only has its frame log)
            put("imu_seconds_dropped", imuSecondsDropped ?: JSONObject.NULL)
            put("imu_points_filled", imuPointsFilled ?: JSONObject.NULL)
            put("late_samples", lateSamples ?: JSONObject.NULL)
            put("dongle_can_drops", dongleCanDrops ?: JSONObject.NULL)
            put("dongle_imu_drops", dongleImuDrops ?: JSONObject.NULL)
            put("reason", reason)
        }.toString()
    }

    private fun base(deviceId: String, sessionId: Long, type: String, atMs: Long) = JSONObject().apply {
        put("source", SOURCE)
        put("device_id", deviceId)
        put("source_session", sessionId.toString())
        put("message_type", type)
        put("captured_at", Instant.ofEpochMilli(atMs).toString())
    }
}
