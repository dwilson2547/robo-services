package com.robo.phonegateway.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LapAnchorTest {
    @Test
    fun `json matches what kreceiver and the lap job expect`() {
        val json = JSONObject(LapAnchor("phone-1", 1_793_000_000_000, 42.1234567, -83.7654321, 4.5f).toJson())
        assertEquals("lap_anchor", json.getString("message_type"))
        assertEquals("proto-live", json.getString("source_session")) // same lap-job key as the frames
        assertEquals("phone-gateway:gps", json.getString("source"))  // routes to telemetry.raw.gps
        assertEquals("phone-1", json.getString("device_id"))
        assertEquals(42.1234567, json.getDouble("latitude"), 1e-9)
        assertEquals(-83.7654321, json.getDouble("longitude"), 1e-9)
        assertEquals("2026-10-26T07:33:20Z", json.getString("captured_at"))
        assertEquals(4.5, json.getDouble("accuracy_m"), 1e-9)
    }

    @Test
    fun `accuracy is left out when unknown`() {
        assertFalse(JSONObject(LapAnchor("p", 0, 1.0, 2.0, null).toJson()).has("accuracy_m"))
    }
}
