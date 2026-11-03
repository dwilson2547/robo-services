from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import RecordingSession
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
