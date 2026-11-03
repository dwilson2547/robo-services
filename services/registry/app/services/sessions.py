"""Recording-session state, derived on read (docs/plans/2026-11-03-recording-sessions.md).

- open:         started, no session_end yet, frames still arriving
- unterminated: no session_end and nothing heard for UNTERMINATED_AFTER
- complete:     session_end seen and every frame it counted reached the pipeline
- incomplete:   session_end seen, some frames missing (a backfill could add them)

Some loss is expected on a phone link, so completeness_pct says how much arrived
rather than treating anything short of 100 % as a failure.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

UNTERMINATED_AFTER = timedelta(minutes=30)


@dataclass(frozen=True)
class SessionState:
    state: str
    completeness_pct: float | None


def derive_state(
    *,
    ended_at: datetime | None,
    frames_expected: int | None,
    frames_received: int,
    started_at: datetime | None,
    last_frame_received_at: datetime | None,
    now: datetime,
) -> SessionState:
    pct = None
    if frames_expected:
        pct = round(min(frames_received, frames_expected) * 100.0 / frames_expected, 1)
    elif frames_expected == 0:
        pct = 100.0
    if ended_at is not None:
        complete = frames_expected is not None and frames_received >= frames_expected
        return SessionState("complete" if complete else "incomplete", pct)
    heard = max((t for t in (last_frame_received_at, started_at) if t is not None), default=None)
    if heard is None or now - heard > UNTERMINATED_AFTER:
        return SessionState("unterminated", pct)
    return SessionState("open", pct)
