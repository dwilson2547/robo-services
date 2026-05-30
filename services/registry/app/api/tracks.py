from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Track
from app.schemas.tracks import (
    OsmDiscoverRequest,
    OsmDiscoverResponse,
    OsmIngestRequest,
    OsmIngestResult,
    TrackCreate,
    TrackOut,
    TrackUpdate,
)
from app.services import osm as osm_service

router = APIRouter(prefix="/tracks", tags=["tracks"])


@router.get("/", response_model=list[TrackOut])
def list_tracks(db: Session = Depends(get_db)):
    return db.query(Track).order_by(Track.name).all()


@router.get("/{track_id}", response_model=TrackOut)
def get_track(track_id: int, db: Session = Depends(get_db)):
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="Track not found")
    return track


@router.post("/", response_model=TrackOut, status_code=201)
def create_track(body: TrackCreate, db: Session = Depends(get_db)):
    track = Track(**body.model_dump())
    db.add(track)
    db.commit()
    db.refresh(track)
    return track


@router.put("/{track_id}", response_model=TrackOut)
def update_track(track_id: int, body: TrackUpdate, db: Session = Depends(get_db)):
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="Track not found")
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(track, field, value)
    db.commit()
    db.refresh(track)
    return track


@router.delete("/{track_id}", status_code=204)
def delete_track(track_id: int, db: Session = Depends(get_db)):
    track = db.query(Track).filter(Track.id == track_id).first()
    if not track:
        raise HTTPException(status_code=404, detail="Track not found")
    db.delete(track)
    db.commit()


# ── OSM discovery / ingest ────────────────────────────────────────────────────

@router.post("/discover", response_model=OsmDiscoverResponse)
def discover_tracks(body: OsmDiscoverRequest, db: Session = Depends(get_db)):
    """Query Overpass for race tracks near a coordinate.

    Returns a token and candidate list.  Candidates include an `already_imported`
    flag but this is informational only — the ingest step re-checks the DB.
    """
    existing_relation_ids: set[int] = {
        row.osm_relation_id
        for row in db.query(Track.osm_relation_id).all()
        if row.osm_relation_id is not None
    }
    existing_way_ids: set[int] = set()
    for row in db.query(Track.osm_way_ids).all():
        if row.osm_way_ids:
            existing_way_ids.update(row.osm_way_ids)

    token, raw_candidates = osm_service.discover(body.lat, body.lon, body.radius_m)

    candidates = []
    for c in raw_candidates:
        already = False
        if c["osm_relation_id"] is not None:
            already = c["osm_relation_id"] in existing_relation_ids
        elif c["osm_way_ids"]:
            already = bool(set(c["osm_way_ids"]) & existing_way_ids)
        candidates.append({**c, "already_imported": already})

    return {"token": token, "candidates": candidates}


@router.post("/ingest", response_model=OsmIngestResult)
def ingest_tracks(body: OsmIngestRequest, db: Session = Depends(get_db)):
    """Ingest selected candidates from a prior discover() call.

    Geometry is taken from the server-side cache (never from the client).
    Dedup is re-checked against the DB at write time.
    """
    selected = osm_service.ingest_from_cache(body.token, body.selected_indices)
    if selected is None:
        raise HTTPException(status_code=410, detail="Discover token expired or not found. Please run discover again.")

    existing_relation_ids: set[int] = {
        row.osm_relation_id
        for row in db.query(Track.osm_relation_id).all()
        if row.osm_relation_id is not None
    }
    existing_way_ids: set[int] = set()
    for row in db.query(Track.osm_way_ids).all():
        if row.osm_way_ids:
            existing_way_ids.update(row.osm_way_ids)

    ingested: list[Track] = []
    skipped: list[dict] = []

    for candidate in selected:
        rel_id = candidate.get("osm_relation_id")
        way_ids = candidate.get("osm_way_ids")
        name = candidate.get("name", "Unknown")

        if rel_id is not None and rel_id in existing_relation_ids:
            skipped.append({"name": name, "reason": f"OSM relation {rel_id} already imported"})
            continue

        if way_ids and set(way_ids) & existing_way_ids:
            overlap = set(way_ids) & existing_way_ids
            skipped.append({"name": name, "reason": f"Way IDs {sorted(overlap)} already imported"})
            continue

        track = Track(
            name=name,
            source="osm",
            geometry=candidate.get("geometry"),
            osm_relation_id=rel_id,
            osm_way_ids=way_ids,
        )
        db.add(track)
        try:
            db.commit()
            db.refresh(track)
            ingested.append(track)
            # Update local sets so subsequent iterations in this batch also dedup correctly.
            if rel_id is not None:
                existing_relation_ids.add(rel_id)
            if way_ids:
                existing_way_ids.update(way_ids)
        except Exception:
            db.rollback()
            skipped.append({"name": name, "reason": "Database conflict (already imported)"})

    return {"ingested": ingested, "skipped": skipped}

