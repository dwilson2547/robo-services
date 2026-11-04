package com.robo.phonegateway.registry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TracksTest {
    @Test fun outlinesFromEveryGeometryType() {
        val line = JSONObject("""{"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[-83.0, 42.5], [-83.1, 42.6]]}}""")
        assertEquals(listOf(listOf(42.5 to -83.0, 42.6 to -83.1)), Outline.lines(line))
        val poly = JSONObject("""{"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}""")
        assertEquals(4, Outline.lines(poly).single().size)
        assertTrue(Outline.lines(JSONObject("""{"type": "Point", "coordinates": [0, 0]}""")).isEmpty())
        assertTrue(Outline.lines(null).isEmpty())
    }

    @Test fun listAndSearch() {
        val tracks = AppTrack.listFromJson("""[{"id": 4, "name": "Near Kart", "source": "osm", "center": [42.505, -83.005],
            "distance_km": 0.93, "has_start_line": false, "added_by_me": true,
            "geometry": {"type": "LineString", "coordinates": [[-83.0, 42.5], [-83.01, 42.51]]}}]""")
        assertEquals(AppTrack(4, "Near Kart", 42.505 to -83.005, 0.93, false, true, tracks[0].geometry), tracks[0])
        assertEquals(1, tracks[0].outline.size)
        val search = TrackSearch.fromJson("""{"token": "tok", "origin": [42.5, -83.0], "origin_label": "Warren, MI",
            "candidates": [{"index": 1, "name": "Near Kart", "geometry_type": "linestring", "center": null,
              "distance_km": null, "already_imported": true, "geometry": {"type": "LineString", "coordinates": []}}]}""")
        assertEquals("Warren, MI", search.originLabel)
        assertEquals(TrackCandidate(1, "Near Kart", null, true, search.candidates[0].geometry), search.candidates[0])
    }
}
