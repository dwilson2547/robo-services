"""The phone app's API: everything here needs the user's authentik token
(app/auth.py) and only ever touches that user's own rows. This prefix is the only
part of the registry exposed publicly."""

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_db
from datetime import UTC, datetime

from app.api.sessions import summary_out
from app.models import Dbc, RecordingSession, SessionSummary, User, Vehicle
from app.schemas.app_api import DbcChoice, MeOut, VehicleIn, VehicleOut
from app.schemas.sessions import AppSessionOut, SessionSummaryOut
from app.services.sessions import derive_state

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


@router.get("/sessions/{source_session}/summary", response_model=SessionSummaryOut)
def my_session_summary(source_session: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    """The drive summary of one of the caller's sessions (recorded under their account);
    404 for anyone else's, or while it is not computed yet."""
    row = (
        db.query(RecordingSession)
        .filter(RecordingSession.source_session == source_session, RecordingSession.user_sub == user.sub)
        .order_by(RecordingSession.id.desc())
        .first()
    )
    if row is None or user.sub is None:
        raise HTTPException(status_code=404, detail="session not found")
    return summary_out(db, row)


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
