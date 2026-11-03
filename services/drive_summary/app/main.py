"""drive_summary: settled sessions -> registry session_summaries, from Timescale vehicle_state.

Once a minute: sessions that have ended (or gone 30 min without an end) and have
had no frame for 2 min, with no summary yet, an older metrics version, or more
frames received since theirs was computed. Sessions without vehicle_state rows
(recorded before frames were tagged) are skipped, and only reported once.
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json, RealDictCursor

from .metrics import VERSION, one_line, summarise

LOG = logging.getLogger("drive-summary")
ROW_COLUMNS = (
    "t", "frame_base_us", "speed_kph", "rpm", "brake_on", "brake_kpa", "gear", "prndl", "odometer_km",
    "coolant_c", "oil_temp_c", "throttle_pct", "steering_deg", "lat", "lon", "gps_speed_kph", "gps_fixes",
    "gyro_mean", "imu_filled_in_frame", "complete",
)

PENDING = """
SELECT s.id, s.source_session, s.device_id, s.frames_expected, s.frames_received, s.ended_at
FROM sessions s LEFT JOIN session_summaries ss ON ss.session_pk = s.id
WHERE (s.ended_at IS NOT NULL OR s.last_frame_received_at < now() - interval '30 minutes')
  AND (s.last_frame_received_at IS NULL OR s.last_frame_received_at < now() - interval '2 minutes')
  AND (ss.session_pk IS NULL OR ss.version < %(version)s OR ss.frames_received <> s.frames_received)
ORDER BY s.id
"""

UPSERT = """
INSERT INTO session_summaries (session_pk, version, frames_used, frames_received, metrics, computed_at)
VALUES (%(pk)s, %(version)s, %(frames_used)s, %(frames_received)s, %(metrics)s, now())
ON CONFLICT (session_pk) DO UPDATE SET version = EXCLUDED.version, frames_used = EXCLUDED.frames_used,
  frames_received = EXCLUDED.frames_received, metrics = EXCLUDED.metrics, computed_at = now()
"""


def completeness(frames_expected: int | None, frames_received: int, ended: bool) -> float | None:
    if not ended or not frames_expected:
        return None
    return round(min(100.0, frames_received / frames_expected * 100), 1)


def session_rows(ts_conn, schema: str, table: str, session: str, device: str) -> list[dict[str, Any]]:
    with ts_conn, ts_conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(
            sql.SQL("SELECT {} FROM {} WHERE source_session = %s AND device_id = %s ORDER BY t").format(
                sql.SQL(", ").join(map(sql.Identifier, ROW_COLUMNS)), sql.Identifier(schema, table)
            ),
            (session, device),
        )
        return [dict(r) for r in cur.fetchall()]


def summarise_pending(reg_conn, ts_conn, schema: str = "telemetry", table: str = "vehicle_state",
                      no_rows: set[int] | None = None) -> list[str]:
    """One pass; returns the log lines of the summaries written."""
    no_rows = set() if no_rows is None else no_rows
    with reg_conn, reg_conn.cursor() as cur:
        cur.execute(PENDING, {"version": VERSION})
        pending = cur.fetchall()
    written = []
    for pk, session, device, expected, received, ended_at in pending:
        rows = session_rows(ts_conn, schema, table, session, device)
        if not rows:
            if pk not in no_rows:
                LOG.info("session %s:%s has no vehicle_state rows; not summarised", session, device)
                no_rows.add(pk)
            continue
        metrics = summarise(rows, frames_received=received,
                            completeness_pct=completeness(expected, received, ended_at is not None))
        with reg_conn, reg_conn.cursor() as cur:
            cur.execute(UPSERT, {"pk": pk, "version": VERSION, "frames_used": metrics["quality"]["frames_used"],
                                 "frames_received": received, "metrics": Json(metrics)})
        written.append(one_line(session, metrics))
    return written


def connect(prefix: str, defaults: dict[str, str] | None = None):
    defaults = defaults or {}
    return psycopg2.connect(
        host=os.environ[f"{prefix}_HOST"], port=int(os.getenv(f"{prefix}_PORT", "5432")),
        dbname=os.getenv(f"{prefix}_NAME", defaults.get("name", "postgres")),
        user=os.environ[f"{prefix}_USER"], password=os.environ[f"{prefix}_PASSWORD"],
    )


def main() -> int:
    logging.basicConfig(level=os.getenv("DRIVE_SUMMARY_LOG_LEVEL", "INFO"))
    every = float(os.getenv("DRIVE_SUMMARY_EVERY_S", "60"))
    schema, table = os.getenv("TS_SCHEMA", "telemetry"), os.getenv("TS_TABLE", "vehicle_state")
    LOG.info("summaries every %.0fs from %s.%s into the registry (metrics version %d)", every, schema, table, VERSION)
    reg = ts = None
    no_rows: set[int] = set()
    while True:
        try:
            reg = reg or connect("DB")
            ts = ts or connect("TS")
            for line in summarise_pending(reg, ts, schema, table, no_rows):
                LOG.info("%s", line)
        except psycopg2.Error:
            LOG.warning("database error; reconnecting next pass", exc_info=True)
            for c in (reg, ts):
                try:
                    c and c.close()
                except psycopg2.Error:
                    pass
            reg = ts = None
        time.sleep(every)


if __name__ == "__main__":
    raise SystemExit(main())
