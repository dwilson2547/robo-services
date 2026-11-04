package com.robo.phonegateway.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripMeterTest {
    @Test fun countsOnlyAccurateMovingFixes() {
        val m = TripMeter()
        assertNull(m.speedMps)
        m.add(42.0, -83.0, 5f, 0f)
        m.add(42.0002, -83.0, 5f, 0.2f)       // parked drift: not counted
        assertEquals(0.0, m.distanceM, 1e-9)
        m.add(42.0011, -83.0, 5f, 10f)         // moving: ~100 m
        assertEquals(100.1, m.distanceM, 0.5)
        m.add(42.0100, -83.0, 80f, 10f)        // a wild, inaccurate fix: ignored
        m.add(42.0020, -83.0, 5f, 10f)         // ~100 m on from the last good fix
        assertEquals(200.2, m.distanceM, 1.0)
        assertEquals(10f, m.speedMps)
    }

    @Test fun speedKeepsTheLastReportedValue() {
        val m = TripMeter()
        m.add(42.0, -83.0, 5f, 12f)
        m.add(42.0, -83.0, null, null)
        assertEquals(12f, m.speedMps)
    }
}
