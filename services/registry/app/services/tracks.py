"""Track discovery and ingest from OSM, shared by the LAN API (/api/tracks) and the
phone API (/api/app/tracks)."""

from __future__ import annotations

import math

from sqlalchemy.orm import Session

from app.models import Track
from app.services import osm as osm_service


def _imported(db: Session) -> tuple[set[int], set[int]]:
    rels = {r.osm_relation_id for r in db.query(Track.osm_relation_id).all() if r.osm_relation_id is not None}
    ways: set[int] = set()
    for r in db.query(Track.osm_way_ids).all():
        if r.osm_way_ids:
            ways.update(r.osm_way_ids)
    return rels, ways


def _coords(geometry: dict | None) -> list[tuple[float, float]]:
    """(lon, lat) pairs of a GeoJSON Feature or geometry, any type."""
    if not geometry:
        return []
    g = geometry.get("geometry", geometry)
    out: list[tuple[float, float]] = []

    def walk(c):
        if isinstance(c, (list, tuple)) and len(c) >= 2 and all(isinstance(v, (int, float)) for v in c[:2]):
            out.append((float(c[0]), float(c[1])))
        elif isinstance(c, (list, tuple)):
            for x in c:
                walk(x)

    walk(g.get("coordinates", []))
    return out


def center(geometry: dict | None) -> tuple[float, float] | None:
    """(lat, lon) of the middle of a geometry's bounding box."""
    pts = _coords(geometry)
    if not pts:
        return None
    lons, lats = [p[0] for p in pts], [p[1] for p in pts]
    return ((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)


def distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(h))


def discover(db: Session, lat: float, lon: float, radius_m: int) -> tuple[str, list[dict]]:
    """OSM candidates near (lat, lon), each flagged if it is already a track."""
    rels, ways = _imported(db)
    token, raw = osm_service.discover(lat, lon, radius_m)
    out = []
    for c in raw:
        if c["osm_relation_id"] is not None:
            already = c["osm_relation_id"] in rels
        else:
            already = bool(set(c["osm_way_ids"] or []) & ways)
        out.append({**c, "already_imported": already})
    return token, out


def ingest(db: Session, token: str, indices: list[int], added_by: int | None = None) -> tuple[list[Track], list[dict]] | None:
    """Create tracks from a prior discover's candidates (geometry from the server's
    cache, never the client). None when the token has expired."""
    selected = osm_service.ingest_from_cache(token, indices)
    if selected is None:
        return None
    rels, ways = _imported(db)
    ingested: list[Track] = []
    skipped: list[dict] = []
    for c in selected:
        rel_id, way_ids, name = c.get("osm_relation_id"), c.get("osm_way_ids"), c.get("name", "Unknown")
        if rel_id is not None and rel_id in rels:
            skipped.append({"name": name, "reason": f"OSM relation {rel_id} already imported"})
            continue
        if way_ids and set(way_ids) & ways:
            skipped.append({"name": name, "reason": f"Way IDs {sorted(set(way_ids) & ways)} already imported"})
            continue
        track = Track(name=name, source="osm", geometry=c.get("geometry"), osm_relation_id=rel_id,
                      osm_way_ids=way_ids, added_by_user_id=added_by)
        db.add(track)
        try:
            db.commit()
            db.refresh(track)
        except Exception:
            db.rollback()
            skipped.append({"name": name, "reason": "Database conflict (already imported)"})
            continue
        ingested.append(track)
        if rel_id is not None:
            rels.add(rel_id)
        if way_ids:
            ways.update(way_ids)
    return ingested, skipped
