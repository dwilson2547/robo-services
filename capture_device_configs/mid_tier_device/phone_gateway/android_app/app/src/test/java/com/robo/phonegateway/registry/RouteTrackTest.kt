package com.robo.phonegateway.registry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTrackTest {

    private fun track(vararg pts: Point4) = RouteTrack(pts.map { RouteTrack.Point(it.t, it.lat, 0.0, it.kph) })
    data class Point4(val t: Double, val lat: Double, val kph: Double?)

    @Test fun parsesTheRegistryTrack() {
        val t = RouteTrack.fromJson("""{"source_session": "1", "points": [[0.0, 42.5, -83.0, 0.0], [1.0, 42.6, -83.1, null]]}""")
        assertEquals(listOf(RouteTrack.Point(0.0, 42.5, -83.0, 0.0), RouteTrack.Point(1.0, 42.6, -83.1, null)), t.points)
        assertEquals((42.5 to -83.1) to (42.6 to -83.0), t.bounds())
    }

    @Test fun speedBandsInMph() {
        assertEquals(RouteTrack.Band.CRAWL, RouteTrack.Band.of(null))
        assertEquals(RouteTrack.Band.CRAWL, RouteTrack.Band.of(32.0))   // 19.9 mph
        assertEquals(RouteTrack.Band.TOWN, RouteTrack.Band.of(33.0))    // 20.5 mph
        assertEquals(RouteTrack.Band.ROAD, RouteTrack.Band.of(70.0))    // 43.5 mph
        assertEquals(RouteTrack.Band.FAST, RouteTrack.Band.of(90.0))    // 55.9 mph
    }

    @Test fun linesSplitByBandAndGapAndJoinAtBandChanges() {
        val t = track(
            Point4(0.0, 1.0, 10.0), Point4(1.0, 2.0, 10.0), Point4(2.0, 3.0, 50.0), Point4(3.0, 4.0, 50.0),
            Point4(10.0, 5.0, 50.0), Point4(11.0, 6.0, 50.0),
        )
        val features = JSONObject(t.geoJson()).getJSONArray("features")
        val lines = (0 until features.length()).map { features.getJSONObject(it) }
            .filter { it.getJSONObject("properties").getString("kind") == "route" }
            .map { f ->
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                f.getJSONObject("properties").getString("color") to (0 until c.length()).map { c.getJSONArray(it).getDouble(1) }
            }
        assertEquals(
            listOf(
                RouteTrack.Band.CRAWL.color to listOf(1.0, 2.0, 3.0),  // runs into the first faster point: no visual gap
                RouteTrack.Band.TOWN.color to listOf(3.0, 4.0),
                RouteTrack.Band.TOWN.color to listOf(5.0, 6.0),        // a new line after the 7 s gap
            ),
            lines,
        )
        val kinds = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("properties").getString("kind") }
        assertEquals(listOf("start", "end"), kinds.filter { it != "route" })
    }

    @Test fun noPoints() {
        assertNull(RouteTrack(emptyList()).bounds())
        assertEquals(true, RouteTrack(emptyList()).isEmpty)
    }
}
