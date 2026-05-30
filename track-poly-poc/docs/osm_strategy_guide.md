# OSM Strategy Selection Guide

How to choose the right `main.py` strategy for a given track.

---

## Decision tree

```
Does the track have a named type=circuit or type=route relation in OSM?
├── Yes → use strategy = "relation"
│         Set osm_name to the relation's name tag value.
│         If it's not type=circuit (e.g. type=route), also set osm_relation_id.
│         If it's an open route (e.g. IoM TT), set geometry = "linestring".
│
└── No → Are the ways individually named (e.g. "{Track} - Sector 1")?
          ├── Yes → use strategy = "ways"
          │         Set osm_name to the common name prefix.
          │
          └── No → Are the way IDs known from a bounding-box investigation?
                    ├── Yes → use strategy = "way_ids"
                    │         Set osm_way_ids to the list of integer IDs.
                    │         Pit lanes (name~"pit") are excluded by default.
                    │
                    └── No → Run a bbox Overpass query first (see below).
```

---

## Finding unknown tracks with a bounding-box query

When a track can't be found by name, query all highway/leisure/sport ways in the facility bbox:

```python
bbox = "lat_min,lon_min,lat_max,lon_max"
query = f"""
[out:json][timeout:30];
(
  way[highway]({bbox});
  way[leisure]({bbox});
  relation[type="circuit"]({bbox});
  relation[type="route"]({bbox});
);
out body; >; out skel qt;
"""
```

Look for:
- `type=circuit` relations → use `relation` strategy with `osm_relation_id`
- Named `highway=raceway` ways → use `ways` strategy
- Unnamed `highway=raceway` ways → use `way_ids` strategy, record IDs in tracks.toml
- `leisure=sports_centre` or `amenity=racetrack` → often a boundary polygon, not the course

---

## Common pitfalls

| Situation | Symptom | Fix |
|---|---|---|
| Venue renamed | No results by current name | Query by bbox, find old name |
| Street circuit | `ways` gives partial/open ring | Use `relation` strategy |
| Open road course | `polygon` finds stands/paddock | Add `geometry = "linestring"` |
| Unnamed ways | `ways` strategy returns nothing | Use `way_ids` strategy after bbox investigation |
| Pit lanes in polygon | Polygon has internal spurs | `way_ids` excludes `name~pit` by default |

---

## Known name aliases

| Current name | OSM name |
|---|---|
| Utah Motorsports Campus | Miller Motorsports Complex |

Add to this table when new aliases are discovered.
