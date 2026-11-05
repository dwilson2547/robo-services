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


TRACK_COLUMNS = ["s", "lat", "lon", "speed_kph", "rpm", "throttle_pct", "brake_on", "gear", "prndl", "brake_kpa"]


def fetch_track(database_url: str, source_session: str, device_id: str) -> list[list]:
    """A session's route from Timescale vehicle_state, one row per interval with a GPS
    fix, in TRACK_COLUMNS order: seconds from the first row, lat, lon, speed (CAN, else
    GPS) and the vehicle's role columns (null where its DBC maps nothing)."""
    import psycopg2

    query = """
        SELECT extract(epoch FROM t - min(t) OVER ()) AS s, lat, lon, coalesce(speed_kph, gps_speed_kph),
               rpm, throttle_pct, brake_on, gear, prndl, brake_kpa
        FROM telemetry.vehicle_state
        WHERE source_session = %s AND device_id = %s
        ORDER BY t
    """
    with psycopg2.connect(database_url) as conn, conn.cursor() as cur:
        cur.execute(query, (source_session, device_id))
        rows = cur.fetchall()

    def r1(x):
        return None if x is None else round(float(x), 1)

    return [
        [round(float(s), 1), lat, lon, r1(kph), r1(rpm), r1(throttle), brake, r1(gear), prndl, r1(brake_kpa)]
        for s, lat, lon, kph, rpm, throttle, brake, gear, prndl, brake_kpa in rows
        if lat is not None and lon is not None
    ]


def delete_vehicle_state(database_url: str, source_session: str, device_id: str) -> int:
    """Delete a session's vehicle_state rows from Timescale; returns how many."""
    import psycopg2

    with psycopg2.connect(database_url) as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM telemetry.vehicle_state WHERE source_session = %s AND device_id = %s",
                    (source_session, device_id))
        return cur.rowcount
