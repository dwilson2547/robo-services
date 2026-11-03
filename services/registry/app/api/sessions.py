from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Dbc, RecordingSession, Vehicle
from app.schemas.dbcs import SessionDbcOut
from app.schemas.sessions import SessionDetailOut, SessionOut
from app.services.sessions import derive_state

router = APIRouter(prefix="/sessions", tags=["sessions"])


def _out(row: RecordingSession, now: datetime, detail: bool = False) -> dict:
    s = derive_state(
        ended_at=row.ended_at,
        frames_expected=row.frames_expected,
        frames_received=row.frames_received,
        started_at=row.started_at,
        last_frame_received_at=row.last_frame_received_at,
        now=now,
    )
    out = {c: getattr(row, c) for c in SessionOut.model_fields if hasattr(row, c)}
    out.update(state=s.state, completeness_pct=s.completeness_pct)
    if detail:
        out.update(start_event=row.start_event, end_event=row.end_event)
    return out


@router.get("/", response_model=list[SessionOut])
def list_sessions(
    device_id: str | None = None,
    limit: int = Query(50, ge=1, le=500),
    db: Session = Depends(get_db),
):
    """Most recent first (by start, falling back to first frame for sessions whose start was lost)."""
    q = db.query(RecordingSession)
    if device_id:
        q = q.filter(RecordingSession.device_id == device_id)
    rows = q.order_by(RecordingSession.source_session.desc()).limit(limit).all()
    now = datetime.now(tz=UTC)
    return [_out(r, now) for r in rows]


@router.get("/{device_id}/{source_session}/dbc", response_model=SessionDbcOut)
def session_dbc(device_id: str, source_session: str, db: Session = Depends(get_db)):
    """The DBC to decode this session's CAN with: its vehicle's. 404 with a reason
    when there is none yet (no session_start seen, no vehicle, vehicle without a DBC);
    can-decode then uses its default."""
    row = (
        db.query(RecordingSession)
        .filter(RecordingSession.device_id == device_id, RecordingSession.source_session == source_session)
        .first()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="session not found")
    if row.vehicle_id is None:
        raise HTTPException(status_code=404, detail="session has no vehicle")
    vehicle = db.get(Vehicle, row.vehicle_id)
    if vehicle is None or vehicle.dbc_id is None:
        raise HTTPException(status_code=404, detail="vehicle has no DBC")
    dbc = db.get(Dbc, vehicle.dbc_id)
    return {"vehicle_id": vehicle.id, "dbc_id": dbc.id, "name": dbc.name, "sha256": dbc.sha256, "content": dbc.content}


@router.get("/{device_id}/{source_session}", response_model=SessionDetailOut)
def get_session(device_id: str, source_session: str, db: Session = Depends(get_db)):
    row = (
        db.query(RecordingSession)
        .filter(RecordingSession.device_id == device_id, RecordingSession.source_session == source_session)
        .first()
    )
    if row is None:
        raise HTTPException(status_code=404, detail="session not found")
    return _out(row, datetime.now(tz=UTC), detail=True)
