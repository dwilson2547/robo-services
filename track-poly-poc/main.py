#!/usr/bin/env python3
"""Build geopolygons for racetracks from OpenStreetMap via the Overpass API.

Reads track definitions from tracks.toml and writes one GeoJSON file per track.

Two strategies are supported (set per-track in tracks.toml):

  ways     — query all highway=raceway segments whose name starts with osm_name
             and assemble them via linemerge+polygonize. Works for purpose-built
             closed circuits like IMS where segments are individually named.

  relation — query the OSM relation with type=circuit and name=osm_name, then
             assemble all non-pit-lane member ways. Required for street circuits
             like Monaco where some sections are public roads and not tagged as
             highway=raceway.

Either strategy accepts an optional osm_relation_id field in tracks.toml to fetch
the relation by numeric ID instead of by name+type. Useful when the relation is not
tagged type=circuit (e.g. type=route for the Isle of Man TT).

An optional geometry field controls output shape:
  polygon    — (default) linemerge + polygonize; for closed circuits.
  linestring — linemerge only; outputs the route as a LineString/MultiLineString.
               Use for open or public-road courses where polygonize finds the wrong
               enclosed areas (e.g. stands, paddock) instead of the course outline.

Usage:
    python main.py                    # run all tracks in tracks.toml
    python main.py --track "Monaco"   # run a single track by name (substring match)
"""
from __future__ import annotations

import argparse
import json
import sys
import tomllib
from pathlib import Path

import requests
from shapely.geometry import LineString, mapping
from shapely.ops import linemerge, polygonize, unary_union

OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
HEADERS = {"User-Agent": "track-poly-poc/0.1.0"}
CONFIG_FILE = Path(__file__).parent / "tracks.toml"


# ── Overpass queries ──────────────────────────────────────────────────────────

def fetch_way_segments(osm_name: str) -> list[dict]:
    """Fetch highway=raceway ways whose name starts with osm_name."""
    escaped = osm_name.replace('"', '\\"')
    query = f"""
[out:json][timeout:60];
way["highway"="raceway"]["name"~"^{escaped}"];
out geom;
"""
    return _post_ways(query)


def fetch_relation_ways(osm_name: str, relation_id: int | None = None) -> tuple[list[dict], dict]:
    """Fetch all non-pit-lane ways from the named type=circuit relation.

    If relation_id is provided, fetch by numeric OSM ID instead of name+type
    (useful for relations not tagged type=circuit, e.g. type=route).

    Returns (ways, relation_tags).
    """
    if relation_id is not None:
        query = f"""
[out:json][timeout:60];
relation({relation_id});
out body;
>;
out skel qt;
"""
    else:
        escaped = osm_name.replace('"', '\\"')
        query = f"""
[out:json][timeout:60];
relation["type"="circuit"]["name"="{escaped}"];
out body;
>;
out skel qt;
"""
    response = _post(query)
    data = response.json()

    nodes_by_id = {e["id"]: e for e in data["elements"] if e["type"] == "node"}
    ways_by_id = {e["id"]: e for e in data["elements"] if e["type"] == "way"}
    relations = [e for e in data["elements"] if e["type"] == "relation"]

    if not relations:
        raise ValueError(
            f"No relation found for {osm_name!r}"
            + (f" (id={relation_id})" if relation_id else " (type=circuit)")
        )

    relation = relations[0]
    relation_tags = relation.get("tags", {})

    ways = []
    for member in relation["members"]:
        if member["type"] != "way" or member["role"] == "pit_lane":
            continue
        way = ways_by_id.get(member["ref"])
        if not way:
            continue
        coords = [
            (nodes_by_id[nid]["lon"], nodes_by_id[nid]["lat"])
            for nid in way["nodes"]
            if nid in nodes_by_id
        ]
        if len(coords) >= 2:
            ways.append({
                "id": way["id"],
                "tags": way.get("tags", {}),
                "geometry": [{"lon": lon, "lat": lat} for lon, lat in coords],
            })

    return ways, relation_tags


def fetch_way_ids(way_ids: list[int], include_pit_lanes: bool = False) -> list[dict]:
    """Fetch specific OSM way IDs and return them as segment dicts.

    Excludes ways tagged name~'Pit Lane' by default.
    """
    id_list = ",".join(str(i) for i in way_ids)
    query = f"""
[out:json][timeout:60];
way(id:{id_list});
out geom;
"""
    data = _post(query).json()
    segments = []
    for el in data["elements"]:
        if el["type"] != "way":
            continue
        tags = el.get("tags", {})
        if not include_pit_lanes and "pit" in tags.get("name", "").lower():
            continue
        coords = [(pt["lon"], pt["lat"]) for pt in el.get("geometry", [])]
        if len(coords) >= 2:
            segments.append({"id": el["id"], "tags": tags, "geometry": [{"lon": lon, "lat": lat} for lon, lat in coords]})
    return segments


def _post(query: str) -> requests.Response:
    response = requests.post(
        OVERPASS_ENDPOINT,
        data=query.encode("utf-8"),
        headers=HEADERS,
        timeout=90,
    )
    response.raise_for_status()
    return response


def _post_ways(query: str) -> list[dict]:
    data = _post(query).json()
    return [el for el in data["elements"] if el["type"] == "way"]


# ── Geometry ──────────────────────────────────────────────────────────────────

def build_polygon(segments: list[dict]):
    lines = []
    for seg in segments:
        coords = [(pt["lon"], pt["lat"]) for pt in seg.get("geometry", [])]
        if len(coords) >= 2:
            lines.append(LineString(coords))

    if not lines:
        raise ValueError("No usable geometry in returned segments.")

    merged = linemerge(lines)
    polygons = list(polygonize(merged))

    if not polygons:
        raise ValueError(
            f"Could not close a polygon from {len(lines)} segment(s). "
            "The segments may not form a continuous closed ring."
        )

    return unary_union(polygons)


def build_linestring(segments: list[dict]):
    lines = []
    for seg in segments:
        coords = [(pt["lon"], pt["lat"]) for pt in seg.get("geometry", [])]
        if len(coords) >= 2:
            lines.append(LineString(coords))

    if not lines:
        raise ValueError("No usable geometry in returned segments.")

    return linemerge(lines)


def collect_tags(segments: list[dict]) -> dict:
    merged: dict = {}
    for seg in segments:
        for k, v in seg.get("tags", {}).items():
            if k not in merged:
                merged[k] = v
    return merged


def vertex_count(geom) -> int:
    if geom.geom_type == "Polygon":
        return len(geom.exterior.coords)
    if geom.geom_type == "LineString":
        return len(geom.coords)
    return sum(
        len(p.exterior.coords) if p.geom_type == "Polygon" else len(p.coords)
        for p in geom.geoms
    )


# ── Runner ────────────────────────────────────────────────────────────────────

def run_track(track: dict, output_dir: Path) -> bool:
    name = track["name"]
    osm_name = track["osm_name"]
    strategy = track.get("strategy", "ways")
    output_path = output_dir / track["output"]

    print(f"\n{'─' * 60}")
    print(f"  {name}  [{strategy}]")
    print(f"{'─' * 60}")

    try:
        extra_props: dict = {}
        if strategy == "ways":
            segments = fetch_way_segments(osm_name)
        elif strategy == "relation":
            relation_id = track.get("osm_relation_id")
            segments, relation_tags = fetch_relation_ways(osm_name, relation_id=relation_id)
            extra_props = relation_tags
        elif strategy == "way_ids":
            way_ids = track.get("osm_way_ids", [])
            if not way_ids:
                print(f"  ERROR: strategy='way_ids' requires osm_way_ids list")
                return False
            include_pits = track.get("include_pit_lanes", False)
            segments = fetch_way_ids(way_ids, include_pit_lanes=include_pits)
        else:
            print(f"  ERROR: unknown strategy {strategy!r} (expected 'ways', 'relation', or 'way_ids')")
            return False
    except (requests.RequestException, ValueError) as exc:
        print(f"  ERROR: {exc}")
        return False

    if not segments:
        print(f"  ERROR: No segments found for {osm_name!r}")
        return False

    geom_type = track.get("geometry", "polygon")

    print(f"  Segments: {len(segments)}")
    for seg in segments:
        seg_name = seg.get("tags", {}).get("name", "(unnamed)")
        print(f"    way {seg['id']}: {seg_name!r}  ({len(seg.get('geometry', []))} nodes)")

    try:
        if geom_type == "linestring":
            shape = build_linestring(segments)
        else:
            shape = build_polygon(segments)
    except ValueError as exc:
        print(f"  ERROR: {exc}")
        return False

    bounds = shape.bounds
    tags = {**collect_tags(segments), **extra_props}
    print(f"  Geometry:  {shape.geom_type}, {vertex_count(shape)} vertices")
    print(f"  Bounding box:")
    print(f"    lon {bounds[0]:.6f} → {bounds[2]:.6f}")
    print(f"    lat {bounds[1]:.6f} → {bounds[3]:.6f}")

    geojson = {
        "type": "FeatureCollection",
        "features": [
            {
                "type": "Feature",
                "properties": {
                    "name": name,
                    "osm_strategy": strategy,
                    "osm_way_ids": [seg["id"] for seg in segments],
                    **tags,
                },
                "geometry": mapping(shape),
            }
        ],
    }

    output_path.write_text(json.dumps(geojson, indent=2))
    print(f"  Saved → {output_path}")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Build racetrack geopolygons from OSM.")
    parser.add_argument(
        "--track",
        metavar="NAME",
        help="Run only the track whose name contains this substring (case-insensitive).",
    )
    args = parser.parse_args()

    config = tomllib.loads(CONFIG_FILE.read_text())
    tracks = config.get("tracks", [])

    if args.track:
        tracks = [t for t in tracks if args.track.lower() in t["name"].lower()]
        if not tracks:
            print(f"No tracks found matching {args.track!r}")
            return 1

    output_dir = Path(__file__).parent
    results = {t["name"]: run_track(t, output_dir) for t in tracks}

    print(f"\n{'═' * 60}")
    print("  Summary")
    print(f"{'═' * 60}")
    for name, ok in results.items():
        status = "✓" if ok else "✗"
        print(f"  {status}  {name}")

    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())



