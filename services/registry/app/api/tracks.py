from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Track
from app.schemas.tracks import TrackCreate, TrackOut, TrackUpdate

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
