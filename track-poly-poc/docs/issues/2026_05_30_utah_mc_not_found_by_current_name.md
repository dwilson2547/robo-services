# Utah Motorsports Campus tracks not found in OSM under current venue name

**Date:** 2026-05-30  
**Component:** `main.py` — `fetch_way_segments`, `fetch_relation_ways`; OSM data  
**Severity:** Low — data exists; query used wrong name

---

## Observed symptom

Overpass queries for `highway=raceway` ways and `type=circuit` relations matching
"Utah Motorsports Campus" or "Miller Motorsports Park" returned no results. The facility was
assumed to be unmapped in OSM, and satellite imagery tracing was used as a workaround.

---

## Root cause

### OSM mapped under former venue name

The facility was named **Miller Motorsports Park** when originally mapped. It was renamed to
Utah Motorsports Campus in 2015. The OSM data was never updated to reflect the name change. All
four major circuit relations are tagged:

```
name = "Miller Motorsports Complex - {East|West|Long|Perimeter} Course"
type = circuit
```

Additionally, the smaller unnamed kart track and medium track cluster exist as bare
`highway=raceway` ways with no name tag and no containing relation — these would never be
found by any name-based query.

### Name-only queries miss unnamed ways

The `ways` strategy queries `["highway"="raceway"]["name"~"^{osm_name}"]`. Ways without a
`name` tag are excluded regardless of location. A significant portion of race infrastructure
(chicanes, short connectors, kart tracks) exists in OSM without names.

---

## Troubleshooting steps taken

1. **Queried by name** — "Utah Motorsports Campus", "Miller Motorsports Park" — no results.
2. **Ran bounding-box query for all highway/leisure/sport ways** in facility area — returned
   `leisure=sports_centre` way 38461281 tagged `name=Utah Motorsports Campus` and ~30
   `highway=raceway` ways, plus 4 `type=circuit` relations tagged "Miller Motorsports Complex".
3. **Identified relations** — 5020269 (East), 5020271 (West), 5025569 (Long), 5025570 (Perimeter).
4. **Located unnamed kart track** — way 476963551, bbox matches hand-drawn linestring exactly.
5. **Located medium track cluster** — ways 343896481–343896487.

---

## Fix

### `main.py` — new `way_ids` strategy

Added a `way_ids` strategy that fetches specific way IDs directly via `way(id:...)` syntax.
This bypasses name-based lookups entirely, enabling precise fetching of unnamed ways once
their IDs are known from a bounding-box investigation.

```toml
[[tracks]]
name = "Utah Motorsports Campus - Kart Track"
osm_way_ids = [476963551]
strategy = "way_ids"
output = "umc_kart_track.geojson"
```

### `tracks.toml` — all 6 UMC circuits added

All four named relations added using `strategy = "relation"` with `osm_relation_id`.
Kart and medium tracks added using `strategy = "way_ids"`.

---

## Files changed

- `main.py` — `fetch_way_ids`, `run_track`
- `tracks.toml` — 6 new UMC track entries
