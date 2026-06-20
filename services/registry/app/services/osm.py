"""OSM track discovery via Overpass API.

Discover and build GeoJSON geometries for race tracks near a given coordinate.
Results are cached in memory by a short-lived token so the ingest step can
rebuild geometry server-side without a second Overpass round-trip.
"""
from __future__ import annotations

import time
import uuid
from collections import defaultdict

import requests
from shapely.geometry import LineString, mapping
from shapely.ops import linemerge

OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
_HEADERS = {"User-Agent": "robo-registry/0.1.0"}

# In-memory cache: token → {candidates, expires_at}
# Candidates include pre-built geometry so ingest never re-fetches from Overpass.
_cache: dict[str, dict] = {}
_CACHE_TTL = 600  # 10 minutes


# ── Public API ────────────────────────────────────────────────────────────────

def discover(lat: float, lon: float, radius_m: int) -> tuple[str, list[dict]]:
    """Query Overpass for race tracks near (lat, lon).

    Returns (token, candidates).  The token is required by ingest() to
    retrieve pre-built geometries without a second Overpass call.
    """
    _evict_expired()
    elements = _query(lat, lon, radius_m)
    candidates = _parse_candidates(elements)
    token = str(uuid.uuid4())
    _cache[token] = {"candidates": candidates, "expires_at": time.time() + _CACHE_TTL}
    return token, candidates


def ingest_from_cache(token: str, indices: list[int]) -> list[dict] | None:
    """Return the selected candidates from a prior discover() call.

    Returns None if the token is missing or expired.
    """
    _evict_expired()
    entry = _cache.get(token)
    if not entry:
        return None
    candidates = entry["candidates"]
    return [candidates[i] for i in indices if 0 <= i < len(candidates)]


# ── Overpass query ────────────────────────────────────────────────────────────

def _query(lat: float, lon: float, radius_m: int) -> list[dict]:
    query = f"""
[out:json][timeout:60];
(
  relation["type"="circuit"](around:{radius_m},{lat},{lon});
  relation["type"="route"]["route"="race"](around:{radius_m},{lat},{lon});
)->.rels;
way(r.rels)->.rel_ways;
way["highway"="raceway"](around:{radius_m},{lat},{lon})->.all_raceway;
(.all_raceway; - .rel_ways;)->.standalone;
.rels out body;
.rel_ways out geom;
.standalone out geom;
"""
    r = requests.post(
        OVERPASS_ENDPOINT, data={"data": query}, headers=_HEADERS, timeout=90
    )
    r.raise_for_status()
    return r.json().get("elements", [])


# ── Candidate parsing ─────────────────────────────────────────────────────────

def _parse_candidates(elements: list[dict]) -> list[dict]:
    relations = {e["id"]: e for e in elements if e["type"] == "relation"}
    ways_by_id = {e["id"]: e for e in elements if e["type"] == "way"}

    candidates: list[dict] = []
    rel_way_ids: set[int] = set()

    # ── Relation-based candidates ─────────────────────────────────────────────
    for rel_id, rel in relations.items():
        member_ways = []
        for member in rel.get("members", []):
            if member["type"] != "way":
                continue
            if member.get("role", "") == "pit_lane":
                continue
            way_id = member["ref"]
            if way_id in ways_by_id:
                member_ways.append(ways_by_id[way_id])
                rel_way_ids.add(way_id)

        if not member_ways:
            continue

        tags = rel.get("tags", {})
        name = tags.get("name") or tags.get("alt_name") or f"OSM Relation {rel_id}"
        geometry, geom_type = _build_geometry(member_ways)

        candidates.append({
            "name": name,
            "osm_relation_id": rel_id,
            "osm_way_ids": None,
            "geometry": geometry,
            "geometry_type": geom_type,
        })

    # ── Standalone way candidates ─────────────────────────────────────────────
    standalone = [
        w for w in elements
        if w["type"] == "way" and w["id"] not in rel_way_ids
    ]

    # Group by name tag first; collect truly unnamed separately.
    named: dict[str, list[dict]] = defaultdict(list)
    unnamed: list[dict] = []
    for way in standalone:
        tags = way.get("tags", {})
        way_name = tags.get("name", "")
        if way_name and "pit" not in way_name.lower():
            named[way_name].append(way)
        elif not way_name:
            unnamed.append(way)
        # ways whose name contains "pit" are intentionally dropped

    for name, group in named.items():
        geometry, geom_type = _build_geometry(group)
        candidates.append({
            "name": name,
            "osm_relation_id": None,
            "osm_way_ids": [w["id"] for w in group],
            "geometry": geometry,
            "geometry_type": geom_type,
        })

    # Connectivity grouping for unnamed ways.
    for group in _group_by_connectivity(unnamed):
        geometry, geom_type = _build_geometry(group)
        candidates.append({
            "name": "Unnamed track",
            "osm_relation_id": None,
            "osm_way_ids": [w["id"] for w in group],
            "geometry": geometry,
            "geometry_type": geom_type,
        })

    return candidates


# ── Geometry building ─────────────────────────────────────────────────────────

def _build_geometry(ways: list[dict]) -> tuple[dict, str]:
    """Build a centerline GeoJSON Feature from a list of Overpass way elements.

    For telemetry work the line is the source of truth because downstream
    enrichment computes track-relative `s` along the course centerline.  We
    intentionally do not polygonize here.
    """
    lines: list[LineString] = []
    for way in ways:
        coords = [(n["lon"], n["lat"]) for n in way.get("geometry", [])]
        if len(coords) >= 2:
            lines.append(LineString(coords))

    if not lines:
        return {"type": "Feature", "properties": {}, "geometry": None}, "linestring"

    merged = linemerge(lines)
    return {
        "type": "Feature",
        "properties": {},
        "geometry": mapping(merged),
    }, "linestring"


# ── Connectivity grouping ─────────────────────────────────────────────────────

def _group_by_connectivity(ways: list[dict]) -> list[list[dict]]:
    """Group ways into connected components by shared OSM node IDs."""
    if not ways:
        return []

    # Build node-set per way
    way_nodes: dict[int, set[int]] = {}
    for w in ways:
        way_nodes[w["id"]] = set(w.get("nodes", []))

    # Union-find
    parent: dict[int, int] = {w["id"]: w["id"] for w in ways}

    def find(x: int) -> int:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x: int, y: int) -> None:
        parent[find(x)] = find(y)

    ids = [w["id"] for w in ways]
    for i, a in enumerate(ids):
        for b in ids[i + 1:]:
            if way_nodes[a] & way_nodes[b]:
                union(a, b)

    groups: dict[int, list[dict]] = defaultdict(list)
    for w in ways:
        groups[find(w["id"])].append(w)

    return list(groups.values())


# ── Cache helpers ─────────────────────────────────────────────────────────────

def _evict_expired() -> None:
    now = time.time()
    expired = [k for k, v in _cache.items() if v["expires_at"] < now]
    for k in expired:
        del _cache[k]
