"""The phone app's API: everything here needs the user's authentik token
(app/auth.py) and only ever touches that user's own rows. This prefix is the only
part of the registry exposed publicly."""

from pydantic import BaseModel
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import func
from sqlalchemy.orm import Session

from app import device_tokens
from app.auth import current_user, current_user_or_device
from app.db import get_db
from datetime import UTC, datetime

from app.api.sessions import summary_out
from app.models import Dbc, Device, RecordingSession, SessionFrame, SessionSummary, Track, User, Vehicle
from app.schemas.app_api import (
    AppCandidateOut, AppDiscoverIn, AppDiscoverOut, AppIngestIn, AppIngestOut, AppTrackOut,
    DbcChoice, MeOut, VehicleIn, VehicleOut,
)
from app.services import osm as osm_service
from app.services import tracks as track_service
from app.config import settings
from app.schemas.sessions import AppSessionOut, SessionSummaryOut, TrackOut
from app.services.sessions import TRACK_COLUMNS, delete_vehicle_state, derive_state, fetch_track

import logging

log = logging.getLogger("registry.app_api")
router = APIRouter(prefix="/app", tags=["app"])


@router.get("/me", response_model=MeOut)
def me(user: User = Depends(current_user)):
    return user


@router.get("/dbcs", response_model=list[DbcChoice])
def list_dbcs(user: User = Depends(current_user), db: Session = Depends(get_db)):
    """DBCs a vehicle can use (names only; the content stays in the cluster)."""
    return db.query(Dbc).order_by(Dbc.name).all()


def _check_dbc(db: Session, body: VehicleIn) -> None:
    if body.dbc_id is not None and db.get(Dbc, body.dbc_id) is None:
        raise HTTPException(status_code=422, detail="unknown dbc_id")


@router.get("/vehicles", response_model=list[VehicleOut])
def list_vehicles(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return db.query(Vehicle).filter(Vehicle.user_id == user.id).order_by(Vehicle.name).all()


@router.post("/vehicles", response_model=VehicleOut, status_code=201)
def create_vehicle(body: VehicleIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    _check_dbc(db, body)
    vehicle = Vehicle(user_id=user.id, **body.model_dump())
    db.add(vehicle)
    db.commit()
    db.refresh(vehicle)
    return vehicle


def _own(db: Session, user: User, vehicle_id: int) -> Vehicle:
    vehicle = db.query(Vehicle).filter(Vehicle.id == vehicle_id, Vehicle.user_id == user.id).first()
    if vehicle is None:  # someone else's is "not found" too, so ids reveal nothing
        raise HTTPException(status_code=404, detail="vehicle not found")
    return vehicle


@router.put("/vehicles/{vehicle_id}", response_model=VehicleOut)
def update_vehicle(vehicle_id: int, body: VehicleIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    vehicle = _own(db, user, vehicle_id)
    _check_dbc(db, body)
    for field, value in body.model_dump().items():
        setattr(vehicle, field, value)
    vehicle.updated_at = func.now()
    db.commit()
    db.refresh(vehicle)
    return vehicle


@router.delete("/vehicles/{vehicle_id}", status_code=204)
def delete_vehicle(vehicle_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.delete(_own(db, user, vehicle_id))
    db.commit()
    return Response(status_code=204)


def _my_session(db: Session, user: User, source_session: str) -> RecordingSession:
    """One of the caller's sessions (recorded under their account); 404 for anyone else's."""
    row = (
        db.query(RecordingSession)
        .filter(RecordingSession.source_session == source_session, RecordingSession.user_sub == user.sub)
        .order_by(RecordingSession.id.desc())
        .first()
    )
    if row is None or user.sub is None:
        raise HTTPException(status_code=404, detail="session not found")
    return row


@router.get("/sessions/{source_session}/summary", response_model=SessionSummaryOut)
def my_session_summary(source_session: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """The drive summary of one of the caller's sessions; 404 while it is not computed yet."""
    return summary_out(db, _my_session(db, user, source_session))


RECORDING_GRACE_S = 5 * 60


@router.delete("/sessions/{source_session}", status_code=204)
def delete_my_session(source_session: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Delete one of the caller's drives: its vehicle_state rows in Timescale first, then
    the session (its frames, summary and discovery result go with it). Nothing is
    deleted if the Timescale part fails. A session still recording (no end, a frame in
    the last 5 minutes) is refused. Raw telemetry in iggy and the parquet archive stays."""
    row = _my_session(db, user, source_session)
    recent = row.last_frame_received_at is not None and \
        (datetime.now(UTC) - row.last_frame_received_at).total_seconds() < RECORDING_GRACE_S
    if row.ended_at is None and recent:
        raise HTTPException(status_code=409, detail="the session is still recording")
    if settings.timescale_database_url:
        try:
            delete_vehicle_state(settings.timescale_database_url, row.source_session, row.device_id)
        except Exception:  # psycopg2 errors and connection failures alike
            log.warning("deleting vehicle_state of session %s failed", row.source_session, exc_info=True)
            raise HTTPException(status_code=503, detail="could not delete the drive's snapshots; nothing was deleted")
    db.query(RecordingSession).filter(RecordingSession.id == row.id).delete(synchronize_session=False)
    db.commit()
    return Response(status_code=204)


@router.get("/sessions/{source_session}/frames")
def my_session_frames(source_session: str, user: User = Depends(current_user_or_device), db: Session = Depends(get_db)):
    """The frames (by base_timestamp_us) of one of the caller's sessions that reached the
    pipeline, so the phone's background catch-up sends only the rest
    (docs/plans/2026-11-04-reliable-uploads.md §1). 404 while the session is unknown.
    Also takes the phone's upload token, so catch-up works while signed out (§2)."""
    row = _my_session(db, user, source_session)
    bases = [b for (b,) in db.query(SessionFrame.base_timestamp_us)
             .filter(SessionFrame.session_pk == row.id).order_by(SessionFrame.base_timestamp_us)]
    return {"source_session": row.source_session, "ended": row.ended_at is not None,
            "frames_expected": row.frames_expected, "base_timestamp_us": bases}


@router.get("/sessions/{source_session}/track", response_model=TrackOut)
def my_session_track(source_session: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """The route of one of the caller's sessions, from its vehicle_state rows (empty for
    sessions recorded before those existed)."""
    row = _my_session(db, user, source_session)
    if not settings.timescale_database_url:
        raise HTTPException(status_code=503, detail="Timescale is not configured")
    return {"source_session": row.source_session, "columns": TRACK_COLUMNS,
            "rows": fetch_track(settings.timescale_database_url, row.source_session, row.device_id)}


def _headline(metrics: dict) -> dict:
    speed = metrics.get("speed_kph") or {}
    return {
        "distance_km": (metrics.get("distance_km") or {}).get("gps"),
        "duration_s": metrics.get("duration_s"),
        "moving_s": metrics.get("moving_s"),
        "avg_moving_kph": speed.get("avg_moving"),
        "max_kph": speed.get("max_can") if speed.get("max_can") is not None else speed.get("max_gps"),
    }


@router.get("/sessions", response_model=list[AppSessionOut])
def my_sessions(
    limit: int = Query(30, ge=1, le=200),
    offset: int = Query(0, ge=0),
    user: User = Depends(current_user),
    db: Session = Depends(get_db),
):
    """The caller's sessions (recorded under their account), newest first, each with
    its vehicle's name and, once drive-summary has written it, a summary headline."""
    if user.sub is None:
        return []
    rows = (
        db.query(RecordingSession, Vehicle.name, SessionSummary.metrics)
        .outerjoin(Vehicle, Vehicle.id == RecordingSession.vehicle_id)
        .outerjoin(SessionSummary, SessionSummary.session_pk == RecordingSession.id)
        .filter(RecordingSession.user_sub == user.sub)
        .order_by(RecordingSession.started_at.desc().nulls_last(), RecordingSession.id.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    now = datetime.now(tz=UTC)
    out = []
    for row, vehicle_name, metrics in rows:
        state = derive_state(
            ended_at=row.ended_at, frames_expected=row.frames_expected, frames_received=row.frames_received,
            started_at=row.started_at, last_frame_received_at=row.last_frame_received_at, now=now,
        )
        out.append({
            "source_session": row.source_session, "device_id": row.device_id, "mode": row.mode,
            "started_at": row.started_at, "ended_at": row.ended_at,
            "state": state.state, "completeness_pct": state.completeness_pct,
            "vehicle_id": row.vehicle_id, "vehicle_name": vehicle_name,
            "summary": _headline(metrics) if metrics else None,
        })
    return out


# ── tracks: shared by everyone (a track is a place); found and added from OSM ──

MAX_DISCOVER_RADIUS_M = 160_934  # 100 mi


def _track_out(t: Track, user: User, origin: tuple[float, float] | None) -> dict:
    c = track_service.center(t.geometry)
    return {
        "id": t.id, "name": t.name, "source": t.source,
        "center": list(c) if c else None,
        "distance_km": round(track_service.distance_km(origin, c), 2) if origin and c else None,
        "has_start_line": t.start_line is not None,
        "added_by_me": t.added_by_user_id == user.id,
        "geometry": t.geometry,
        "start_line": t.start_line,
    }


@router.get("/tracks", response_model=list[AppTrackOut])
def list_tracks(lat: float | None = None, lon: float | None = None,
                user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Every track; nearest first when the phone says where it is, else by name."""
    origin = (lat, lon) if lat is not None and lon is not None else None
    out = [_track_out(t, user, origin) for t in db.query(Track).all()]
    if origin:
        out.sort(key=lambda t: (t["distance_km"] is None, t["distance_km"] or 0, t["name"]))
    else:
        out.sort(key=lambda t: t["name"].lower())
    return out


@router.post("/tracks/discover", response_model=AppDiscoverOut)
def discover_tracks(body: AppDiscoverIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """OSM tracks within radius_m (at most 100 mi) of a point or a US ZIP code."""
    if body.zip is not None:
        zip_code = body.zip.strip()
        if not (len(zip_code) == 5 and zip_code.isdigit()):
            raise HTTPException(status_code=422, detail="zip must be 5 digits")
        try:
            found = osm_service.geocode_zip(zip_code)
        except Exception:
            raise HTTPException(status_code=502, detail="ZIP lookup unavailable; try again or search nearby")
        if found is None:
            raise HTTPException(status_code=404, detail=f"ZIP {zip_code} not found")
        lat, lon, label = found
    elif body.lat is not None and body.lon is not None:
        lat, lon, label = body.lat, body.lon, "your location"
    else:
        raise HTTPException(status_code=422, detail="give lat and lon, or zip")
    radius = max(1_000, min(body.radius_m, MAX_DISCOVER_RADIUS_M))
    try:
        token, candidates = track_service.discover(db, lat, lon, radius)
    except Exception:
        raise HTTPException(status_code=502, detail="OSM track search unavailable; try again shortly")
    out = []
    for i, c in enumerate(candidates):
        cc = track_service.center(c["geometry"])
        out.append({
            "index": i, "name": c["name"], "geometry_type": c["geometry_type"],
            "center": list(cc) if cc else None,
            "distance_km": round(track_service.distance_km((lat, lon), cc), 2) if cc else None,
            "already_imported": c["already_imported"], "geometry": c["geometry"],
        })
    out.sort(key=lambda c: (c["distance_km"] is None, c["distance_km"] or 0))
    return {"token": token, "origin": [lat, lon], "origin_label": label, "candidates": out}


@router.post("/tracks/ingest", response_model=AppIngestOut)
def ingest_tracks(body: AppIngestIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Add candidates from a discover (by their index), recording who added them."""
    result = track_service.ingest(db, body.token, body.selected_indices, added_by=user.id)
    if result is None:
        raise HTTPException(status_code=410, detail="search expired; search again")
    ingested, skipped = result
    return {"ingested": [_track_out(t, user, None) for t in ingested], "skipped": skipped}


class UploadKeyIn(BaseModel):
    public_key_pem: str


@router.put("/devices/{device_id}/upload-key", status_code=204)
def register_upload_key(device_id: str, body: UploadKeyIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Register this phone's upload key (docs/plans/2026-11-04-reliable-uploads.md §2): the
    public half of an EC P-256 key the phone keeps in hardware. Replaces any earlier key.
    A device another user owns is refused; an unknown one is created for the caller."""
    if user.sub is None:
        raise HTTPException(status_code=403, detail="no sub")
    try:
        device_tokens.check_public_key(body.public_key_pem)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc))
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if device is None:
        device = Device(device_id=device_id, display_name=device_id, source="app", user_id=user.id)
        db.add(device)
    elif device.user_id not in (None, user.id):
        raise HTTPException(status_code=403, detail="device belongs to another user")
    device.user_id = user.id
    device.upload_key = body.public_key_pem
    device.upload_key_sub = user.sub
    device.upload_key_at = datetime.now(UTC)
    db.commit()
    return Response(status_code=204)


@router.delete("/devices/{device_id}/upload-key", status_code=204)
def revoke_upload_key(device_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """Forget a phone's upload key (sign-out, or a lost phone): its tokens stop working."""
    device = db.query(Device).filter(Device.device_id == device_id, Device.user_id == user.id).first()
    if device is not None:
        device.upload_key = device.upload_key_sub = device.upload_key_at = None
        db.commit()
    return Response(status_code=204)
