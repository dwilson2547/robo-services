# polygonize on Isle of Man TT returns stands/paddock enclosures instead of the race course

**Date:** 2026-05-30  
**Component:** `main.py` — `build_polygon`, `run_track`  
**Severity:** Medium — produces incorrect geometry for route-type tracks; OSM data is correct

---

## Observed symptom

Running `main.py` against the Isle of Man TT (Snaefell Mountain Course, OSM relation 188240)
produced a MultiPolygon of 48 small polygons. Overlaying in geojson.io showed these were
grandstand and paddock enclosures — not the 60km mountain course itself.

---

## Root cause

### polygonize operates on enclosed rings, not open routes

`shapely.ops.polygonize` only produces polygons from closed rings. The IoM TT is a
`type=route` relation (public road course), not a `type=circuit`. Its member ways form a
long open linestring, not a closed loop. The small enclosed areas detected by polygonize
came from short connecting ways that happened to form closed rings around facility buildings.

```python
# build_polygon always tries to close rings — wrong for open routes
polygons = list(polygonize(merged))
```

### OSM relation type differs from racing circuits

The IoM TT is mapped as `type=route` (like a road or cycling route), not `type=circuit`.
The distinction matters: circuit relations are closed loops; route relations are open paths.

---

## Troubleshooting steps taken

1. **Checked raw geometry in geojson.io** — 48 small polygons confirmed as structures, not course.
2. **Inspected OSM relation 188240** — tagged `type=route`, not `type=circuit`; member ways form
   a continuous open path around the mountain course.
3. **Tested `linemerge` without `polygonize`** — produced a single 2130-vertex MultiLineString
   tracing the full 60km course correctly.

---

## Fix

### `main.py` — added `geometry = "linestring"` option in `run_track`

Added a `geometry` field to the track config (default: `"polygon"`). When set to `"linestring"`,
`build_linestring()` is called instead of `build_polygon()`, skipping polygonize entirely.

```python
geom_type = track.get("geometry", "polygon")
if geom_type == "linestring":
    shape = build_linestring(segments)
else:
    shape = build_polygon(segments)
```

### `tracks.toml` — IoM TT entry updated

```toml
[[tracks]]
name = "Snaefell Mountain Course"
osm_relation_id = 188240
strategy = "relation"
geometry = "linestring"   # <-- added
output = "snaefell_mountain_course.geojson"
```

---

## Files changed

- `main.py` — `build_linestring`, `run_track`
- `tracks.toml` — IoM TT entry
