# trace.py flood-fill bleeds into adjacent same-colored pavement producing oversized polygons

**Date:** 2026-05-30  
**Component:** `trace.py` — `segment_track`, `fetch_tiles`  
**Severity:** Medium — output polygon bbox ~2× the actual track area; unusable for precision work

---

## Observed symptom

Running `trace.py` on the Utah Motorsports Campus go-kart track (seeds taken from linestring
points on the track) produced a polygon with a bounding box ~508m wide. The actual track is
~270m wide. The extra ~238m extended westward into the adjacent motorsports campus facility.

A hand-drawn reference linestring (`go_cart_linestring.geojson`) drawn in the map UI showed the
track centerline accurately and was used as the comparison baseline.

---

## Root cause

### No spatial constraint on flood-fill growth

The segmentation pipeline uses K-means color clustering in LAB colorspace to identify the track
surface, then flood-fills from seed pixels to capture the connected region. With `--buffer 3`,
the fetched image canvas is ~1.5km wide. The kart track is dusty asphalt on a gravel surface —
visually similar to the surrounding campus pavement. Once the correct cluster was identified,
`floodFill` grew into all contiguous same-colored pixels across the entire image canvas.

```python
# Before fix: no spatial boundary on flood-fill
cv2.floodFill(flood, flood_padded, (col, row), 128)
# Result: fills entire connected asphalt region, not just the track
```

### Large default tile buffer amplifies bleed

`--buffer 3` fetches 3 extra tiles on each side of the seed bounding box, pulling in large
amounts of surrounding infrastructure that shares color characteristics with the track.

---

## Troubleshooting steps taken

1. **Overlaid `traced_track1.geojson` against hand-drawn linestring** — bbox mismatch confirmed,
   ~238m excess on the west side.
2. **Reviewed satellite imagery** — track is dusty asphalt; surrounding ground is gravel; the
   difference is subtle and K-means clusters them together at default settings.
3. **Checked geojson.io Mapbox layer** — kart track IS mapped in OSM (resolved separately);
   but the cv approach needed fixing regardless for genuinely unmapped tracks.
4. **Tested corridor mask approach** — dilating the linestring by 25m and applying as a binary
   mask before and after flood-fill constrained growth to the actual track corridor.

---

## Fix

### `trace.py` — new `--linestring` mode with corridor mask

Added `--linestring FILE` as a mutually exclusive alternative to `--seeds`. When provided:

1. All linestring vertices are used as seeds.
2. A pixel-space corridor mask is built by rasterizing the linestring and dilating by
   `--corridor-width` metres (default 30m).
3. The corridor mask is applied to the K-means cluster mask **before** flood-fill, so growth
   cannot escape the track corridor regardless of color similarity.
4. The corridor mask is applied again **after** flood-fill for belt-and-suspenders.
5. Default `--buffer` drops from 3 to 1 tile (the linestring bbox already covers the track).

```python
# After fix: corridor mask constrains flood-fill spatially
if corridor_mask is not None:
    cluster_mask = cv2.bitwise_and(cluster_mask, corridor_mask)
# ... flood-fill ...
if corridor_mask is not None:
    fill_mask = cv2.bitwise_and(fill_mask, corridor_mask)
```

**Result:** 22-vertex polygon, bbox ~325m wide (correctly includes 25m corridor on each side of
the 270m-wide track centerline).

---

## Files changed

- `trace.py` — `fetch_way_ids`, `build_corridor_mask`, `segment_track`, `main`
