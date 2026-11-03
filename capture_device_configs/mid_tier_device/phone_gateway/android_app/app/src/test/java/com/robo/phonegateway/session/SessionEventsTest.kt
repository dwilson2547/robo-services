package com.robo.phonegateway.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventsTest {

    @Test
    fun `start carries what kreceiver routes on and the session details`() {
        val json = JSONObject(
            SessionEvents.Start(
                deviceId = "phone-1", sessionId = 1_793_000_000_000, mode = "race", vehicleId = 7,
                canFilter = listOf(0x1E5, 0xC9), dongle = "CAN-DONGLE AA:BB", appVersion = "1.0", phone = "samsung SM-G998U1",
            ).toJson(),
        )
        assertEquals("phone-gateway:session", json.getString("source")) // kreceiver -> telemetry.session
        assertEquals("session_start", json.getString("message_type"))
        assertEquals("1793000000000", json.getString("source_session"))
        assertEquals("2026-10-26T07:33:20Z", json.getString("captured_at"))
        assertEquals("race", json.getString("mode"))
        assertEquals("0x1E5", json.getJSONArray("can_filter").getString(0))
        assertEquals(7, json.getInt("vehicle_id"))
        assertTrue(json.isNull("track_id"))
    }

    @Test
    fun `end carries counts, with nulls for what a recovered session cannot know`() {
        val json = JSONObject(
            SessionEvents.End(
                deviceId = "phone-1", sessionId = 1_793_000_000_000, endedAtMs = 1_793_000_120_000,
                frames = 120, firstFrameUs = 1_793_000_000_000_000, lastFrameUs = 1_793_000_119_000_000,
                imuSecondsDropped = null, imuPointsFilled = null, lateSamples = null,
                dongleCanDrops = null, dongleImuDrops = null, reason = "app_killed_recovered",
            ).toJson(),
        )
        assertEquals("session_end", json.getString("message_type"))
        assertEquals(120, json.getLong("frames"))
        assertEquals(1_793_000_119_000_000, json.getLong("last_frame_us"))
        assertTrue(json.isNull("late_samples"))
        assertEquals("app_killed_recovered", json.getString("reason"))
    }
}
