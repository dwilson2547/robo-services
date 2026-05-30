# Satellite Imagery Tracing — Limitations and Best Practices

Notes on when `trace.py` works well, when it struggles, and how to get better results.

---

## Tile source

**Esri World Imagery** (`server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`)

- No API key required
- `Access-Control-Allow-Origin: *` (usable from browser JS too)
- Zoom 17 ≈ 1m/pixel — good for kart tracks and above
- Zoom 16 ≈ 2m/pixel — better for large circuits (fewer tiles, faster)

---

## When imagery tracing works well

- Track surface is **visually distinct** from surroundings (e.g. black asphalt on pale dirt/grass)
- Track is **isolated** — no adjacent same-colored pavement within flood-fill range
- Track is **wide enough** to have interior pixels (≥5m width at zoom 17)

Good candidates: gravel-surfaced rally stages, unpaved oval tracks, desert circuits.

---

## When imagery tracing struggles

- **Dusty asphalt on gravel** (e.g. Utah kart track) — K-means cannot separate them
- **Urban street circuits** — surrounded by identical road pavement
- **Recently repaved tracks** — fresh asphalt matches surroundings more closely
- **Overhead shadows** — tree cover or grandstand shadows alter apparent color

In all these cases, OSM data (if available) will be more accurate. Check OSM first.

---

## Best practices

### Always check OSM first

Run a bounding-box Overpass query before reaching for `trace.py`. Many unmapped-seeming
tracks are in OSM under a former name or with minimal tags. See `osm_strategy_guide.md`.

### Use `--linestring` instead of `--seeds` where possible

`--seeds` has no spatial constraint — flood-fill can bleed across the entire image canvas.
`--linestring` builds a corridor mask that hard-limits detection to within N metres of
the drawn line.

```bash
# Draw a rough centerline in map.html, export as linestring GeoJSON, then:
python trace.py --linestring drawn_line.geojson --corridor-width 25 --output track.geojson
```

The corridor width should be:
- **Kart tracks**: 15–25m (track ~6–10m wide + margin)
- **Car circuits**: 25–40m (track 10–15m wide + margin)
- **Wide ovals**: 40–60m

### Tune `--clusters` if the surface isn't being isolated

More clusters = finer color discrimination. Increase from default 6 if:
- Mask coverage is 0% (seeds not matching any cluster)
- Mask coverage is >20% (too many clusters matching seeds)

```bash
python trace.py --linestring line.geojson --clusters 8 --output track.geojson
```

### Save the linestring alongside the output GeoJSON

The linestring is the reproducible input. If re-tracing is needed, the linestring is
far easier to adjust than re-picking seed coordinates.

---

## Workflow summary

```
1. Open map.html → navigate to track location
2. Shift/Ctrl+drag to draw a rough centerline
3. Export linestring GeoJSON from the panel
4. python trace.py --linestring <file> --corridor-width <N> --output <track>.geojson
5. Check output in geojson.io
6. If wrong: adjust linestring in map.html or tune --corridor-width / --clusters
```
