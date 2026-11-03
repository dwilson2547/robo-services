package com.robo.phonegateway.registry

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class SessionItemTest {

    private val list = """[
      {"source_session": "1793745445702", "device_id": "p", "mode": "race", "started_at": "2026-11-03T22:37:25.702000Z",
       "ended_at": "2026-11-03T22:50:00.103000Z", "state": "complete", "completeness_pct": 100.0, "vehicle_id": 1,
       "vehicle_name": "Impala", "summary": {"distance_km": 7.428, "duration_s": 756.0, "moving_s": 577.0,
       "avg_moving_kph": 48.7, "max_kph": 81.7}},
      {"source_session": "1793728153233", "device_id": "p", "mode": "race", "started_at": "2026-11-03T17:49:13.233000Z",
       "ended_at": "2026-11-03T17:58:09.235000Z", "state": "incomplete", "completeness_pct": 98.5, "vehicle_id": null,
       "vehicle_name": null, "summary": null},
      {"source_session": "1793760000000", "device_id": "p", "mode": "race", "started_at": null, "ended_at": null,
       "state": "open", "completeness_pct": null, "vehicle_id": null, "vehicle_name": null, "summary": null}
    ]"""

    @Test fun historyRows() {
        val (drive, old, open) = SessionItem.listFromJson(list)
        val detroit = ZoneId.of("America/Detroit")
        assertEquals("Tue Nov 3, 5:37 PM · Impala", drive.title(detroit))
        assertEquals("4.62 mi · 12m36s · avg 30.3 mph", drive.subtitle())
        assertEquals("Tue Nov 3, 12:49 PM", old.title(detroit))
        assertEquals("no summary · 98% arrived", old.subtitle())
        assertEquals("session 1793760000000", open.title(detroit))
        assertEquals("recording", open.subtitle())
    }
}
