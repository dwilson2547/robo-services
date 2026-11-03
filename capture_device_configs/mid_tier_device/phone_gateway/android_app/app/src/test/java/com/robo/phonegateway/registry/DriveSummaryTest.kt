package com.robo.phonegateway.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DriveSummaryTest {

    // trimmed from the registry's summary of drive 1 (session 1793745445702, metrics v2)
    private val drive1 = """
        {"source_session": "1793745445702", "device_id": "phone-1", "version": 2, "frames_used": 756,
         "frames_received": 756, "computed_at": "2026-11-03T23:20:00Z",
         "metrics": {"version": 2, "duration_s": 756.0, "moving_s": 577.0, "stops": 9,
           "distance_km": {"gps": 7.428, "can": 7.812, "odometer": 8.0},
           "speed_kph": {"avg_moving": 48.7, "max_can": 81.7, "max_gps": 78.0},
           "engine": {"max_rpm": 2773.0, "avg_rpm_moving": 1276.0, "seconds_above_4000": 0.0},
           "selector_s": {"D": 697.0, "P": 35.0, "R": 24.0}, "gears_s": {"1": 218.0, "2": 60.0, "3": 128.0, "4": 291.0},
           "shifts": 32, "brakes": {"applications": 19, "seconds": 275.0, "max_kpa": 174.0},
           "events": {"hard_braking": 0, "hard_acceleration": 0, "sharp_cornering": 0},
           "peaks_g": {"braking": -0.291, "acceleration": 0.174, "lateral": 0.398},
           "temperatures_c": {"coolant": {"start": 66.0, "max": 95.0, "end": 94.0}, "oil": null},
           "quality": {"completeness_pct": 100.0, "frames_used": 756, "frames_received": 756}}}
    """.trimIndent()

    @Test fun drive1ReadsAsMilesMphAndFahrenheit() {
        val lines = DriveSummary.fromJson(drive1).lines().toMap()
        assertEquals("4.62 mi (CAN 4.85, odometer 4.97)", lines["Distance"])
        assertEquals("12m36s, moving 9m37s", lines["Time"])
        assertEquals("avg 30.3 mph moving, max 50.8", lines["Speed"])
        assertEquals("9 stops, 19 brake applications, 32 shifts", lines["Counts"])
        assertEquals("P 35s, R 24s, D 11m37s", lines["Selector"])
        assertEquals("1: 3m38s, 2: 1m00s, 3: 2m08s, 4: 4m51s", lines["Gears"])
        assertEquals("braking 0, accel 0, cornering 0", lines["Hard events"])
        assertEquals("braking -0.29 g, accel 0.17 g, cornering 0.40 g", lines["Peaks"])
        assertEquals("max 2773 rpm", lines["Engine"])
        assertEquals("151 → 201 °F (max 203)", lines["Coolant"])
        assertEquals("756 of 756 frames, 100.0% complete", lines["Data"])
    }

    @Test fun metricsAVehicleCannotProvideAreLeftOut() {
        val bare = """{"source_session": "1", "device_id": "p", "version": 2, "frames_used": 3, "frames_received": 3,
            "metrics": {"duration_s": 3.0, "moving_s": 0.0, "stops": 0, "distance_km": {"gps": 0.0, "can": null, "odometer": null},
              "speed_kph": {"avg_moving": null, "max_can": null, "max_gps": null}, "selector_s": {}, "gears_s": {},
              "shifts": 0, "temperatures_c": {"coolant": null}, "quality": {"completeness_pct": null}}}"""
        val lines = DriveSummary.fromJson(bare).lines().toMap()
        assertEquals("0.00 mi", lines["Distance"])
        assertFalse("Speed" in lines || "Selector" in lines || "Coolant" in lines || "Hard events" in lines || "Peaks" in lines)
        assertEquals("3 of 3 frames", lines["Data"])
    }

    @Test fun durations() {
        assertEquals("45s", DriveSummary.duration(45.0))
        assertEquals("1m05s", DriveSummary.duration(65.0))
        assertEquals("1h02m", DriveSummary.duration(3720.0))
    }
}
