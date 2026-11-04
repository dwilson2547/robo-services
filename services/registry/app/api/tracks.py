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
from app.services import tracks as track_service

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
    """Query Overpass for race tracks near a coordinate: a token and candidates
    (already_imported is informational; ingest re-checks)."""
    token, candidates = track_service.discover(db, body.lat, body.lon, body.radius_m)
    return {"token": token, "candidates": candidates}


@router.post("/ingest", response_model=OsmIngestResult)
def ingest_tracks(body: OsmIngestRequest, db: Session = Depends(get_db)):
    """Ingest selected candidates from a prior discover() call (geometry from the
    server-side cache, never the client; dedup re-checked at write time)."""
    result = track_service.ingest(db, body.token, body.selected_indices)
    if result is None:
        raise HTTPException(status_code=410, detail="Discover token expired or not found. Please run discover again.")
    ingested, skipped = result
    return {"ingested": ingested, "skipped": skipped}
