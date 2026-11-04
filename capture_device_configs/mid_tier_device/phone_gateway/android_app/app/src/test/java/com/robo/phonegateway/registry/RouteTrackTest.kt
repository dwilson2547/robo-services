package com.robo.phonegateway.registry

import com.robo.phonegateway.registry.RouteTrack.ColorBy
import com.robo.phonegateway.registry.RouteTrack.Point
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrackTest {

    private val json = """{"source_session": "1",
      "columns": ["s", "lat", "lon", "speed_kph", "rpm", "throttle_pct", "brake_on", "gear", "prndl"],
      "rows": [[0.0, 42.5, -83.0, 0.0, 750.2, 0.0, true, 15.0, "P"],
               [1.0, 42.6, -83.1, 40.0, 2100.0, 23.0, false, 2.0, "D"],
               [2.0, 42.7, -83.2, null, null, null, null, null, null]]}"""

    @Test fun parsesColumnsByName() {
        val t = RouteTrack.fromJson(json)
        assertEquals(Point(0.0, 42.5, -83.0, 0.0, 750.2, 0.0, true, 15.0, "P"), t.points[0])
        assertEquals(Point(2.0, 42.7, -83.2), t.points[2])
        assertEquals((42.5 to -83.2) to (42.7 to -83.0), t.bounds())
    }

    @Test fun bandsPerAttributeAndUnits() {
        val p = Point(0.0, 0.0, 0.0, kph = 33.0, rpm = 2600.0, throttlePct = 35.0, brakeOn = true, gear = 3.0, prndl = "D")
        assertEquals("20–40", ColorBy.SPEED.band(p, Units.IMPERIAL)!!.label)  // 20.5 mph
        assertEquals("30–60", ColorBy.SPEED.band(p, Units.METRIC)!!.label)
        assertEquals("<20", ColorBy.SPEED.band(p.copy(kph = 32.0), Units.IMPERIAL)!!.label)  // 19.9 mph
        assertEquals("2500–3500", ColorBy.RPM.band(p, Units.IMPERIAL)!!.label)
        assertEquals("30–60%", ColorBy.THROTTLE.band(p, Units.IMPERIAL)!!.label)
        assertEquals("on", ColorBy.BRAKE.band(p, Units.IMPERIAL)!!.label)
        assertEquals("3", ColorBy.GEAR.band(p, Units.IMPERIAL)!!.label)
        assertEquals("R", ColorBy.GEAR.band(p.copy(prndl = "R", gear = 14.0), Units.IMPERIAL)!!.label)  // not "14"
        assertNull(ColorBy.GEAR.band(p.copy(prndl = null, gear = 15.0), Units.IMPERIAL))
        assertNull(ColorBy.SPEED.band(Point(0.0, 0.0, 0.0), Units.IMPERIAL))
    }

    @Test fun brakeIsGradedByPressureWhenTheVehicleReportsIt() {
        val p = Point(0.0, 0.0, 0.0, brakeOn = true)
        assertEquals("off", ColorBy.BRAKE.band(p.copy(brakeKpa = 7.8), Units.IMPERIAL)!!.label)  // resting pressure
        assertEquals("light", ColorBy.BRAKE.band(p.copy(brakeKpa = 45.0), Units.IMPERIAL)!!.label)
        assertEquals("medium", ColorBy.BRAKE.band(p.copy(brakeKpa = 101.0), Units.IMPERIAL)!!.label)
        assertEquals("firm", ColorBy.BRAKE.band(p.copy(brakeKpa = 136.5), Units.IMPERIAL)!!.label)
        assertEquals("hard", ColorBy.BRAKE.band(p.copy(brakeKpa = 174.2), Units.IMPERIAL)!!.label)
        assertEquals("on", ColorBy.BRAKE.band(p, Units.IMPERIAL)!!.label)  // flag only
        val pressure = RouteTrack.fromJson(json.replace("\"prndl\"],", "\"prndl\", \"brake_kpa\"],")
            .replace("15.0, \"P\"]", "15.0, \"P\", 101.4]").replace("2.0, \"D\"]", "2.0, \"D\", 7.8]")
            .replace("null, null]]", "null, null, null]]"))
        assertEquals(101.4, pressure.points[0].brakeKpa!!, 0.0)
        assertEquals(listOf("off", "light", "medium", "firm", "hard"), ColorBy.BRAKE.legend(Units.IMPERIAL, pressure).map { it.label })
        assertEquals(listOf("off", "on"), ColorBy.BRAKE.legend(Units.IMPERIAL, RouteTrack.fromJson(json)).map { it.label })
        assertEquals("14.7 psi", Units.IMPERIAL.pressure(101.4))
        assertEquals("101 kPa", Units.METRIC.pressure(101.4))
    }

    @Test fun gearLegendListsWhatTheTrackHas() {
        val t = RouteTrack.fromJson(json)
        assertEquals(listOf("P", "2"), ColorBy.GEAR.legend(Units.IMPERIAL, t).map { it.label })
        assertTrue(t.has(ColorBy.RPM))
        assertFalse(RouteTrack(listOf(Point(0.0, 0.0, 0.0))).has(ColorBy.RPM))
    }

    @Test fun linesSplitByBandAndGap() {
        fun pt(t: Double, lat: Double, kph: Double) = Point(t, lat, 0.0, kph = kph)
        val t = RouteTrack(listOf(pt(0.0, 1.0, 10.0), pt(1.0, 2.0, 10.0), pt(2.0, 3.0, 50.0), pt(3.0, 4.0, 50.0),
            pt(10.0, 5.0, 50.0), pt(11.0, 6.0, 50.0)))
        val features = JSONObject(t.geoJson(ColorBy.SPEED)).getJSONArray("features")
        val lines = (0 until features.length()).map { features.getJSONObject(it) }
            .filter { it.getJSONObject("properties").getString("kind") == "route" }
            .map { f ->
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                (0 until c.length()).map { c.getJSONArray(it).getDouble(1) }
            }
        assertEquals(listOf(listOf(1.0, 2.0, 3.0), listOf(3.0, 4.0), listOf(5.0, 6.0)), lines)
    }

    @Test fun tapFindsTheNearestPointWithinReach() {
        val t = RouteTrack.fromJson(json)
        assertEquals(1.0, t.nearest(42.6001, -83.1, maxM = 50.0)!!.tS, 0.0)   // ~11 m away
        assertNull(t.nearest(42.65, -83.1, maxM = 50.0))                      // ~5.5 km from any point
    }
}
