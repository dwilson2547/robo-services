"""Apply telemetry.session records to the registry's sessions tables.

The tables belong to the registry (its Alembic migration 0004); this only writes
rows. Every write is idempotent, so a record delivered twice (consumer restart,
MQTT redelivery, a session_end resent from the phone's outbox) changes nothing
the second time:

- session_start / session_end upsert their columns on (source_session, device_id);
- a frame receipt inserts (session, base_timestamp_us) once, and frames_received
  only grows when that insert was new.

Records are kreceiver's normalized messages: `message_type`, `device_id`,
`source_session`, `received_at`, `user_sub` (kreceiver's stamp from the per-user
MQTT topic; never the payload's own claim), and the original packet (or the
frame receipt) under `payload`. A known user is never overwritten with null.
"""

from __future__ import annotations

import json
from typing import Any

_UPSERT_START = """
INSERT INTO sessions (source_session, device_id, user_sub, mode, vehicle_id, track_id, started_at, start_event)
VALUES (%(source_session)s, %(device_id)s, %(user_sub)s, %(mode)s, %(vehicle_id)s,
        (SELECT id FROM tracks WHERE id = %(track_id)s), %(started_at)s, %(event)s)
ON CONFLICT (source_session, device_id) DO UPDATE SET
    user_sub = COALESCE(EXCLUDED.user_sub, sessions.user_sub),
    mode = EXCLUDED.mode,
    vehicle_id = EXCLUDED.vehicle_id,
    track_id = EXCLUDED.track_id,
    started_at = EXCLUDED.started_at,
    start_event = EXCLUDED.start_event,
    updated_at = now()
"""

_UPSERT_END = """
INSERT INTO sessions (source_session, device_id, user_sub, ended_at, end_reason, frames_expected,
                      first_frame_us, last_frame_us, end_event)
VALUES (%(source_session)s, %(device_id)s, %(user_sub)s, %(ended_at)s, %(reason)s, %(frames)s,
        %(first_frame_us)s, %(last_frame_us)s, %(event)s)
ON CONFLICT (source_session, device_id) DO UPDATE SET
    user_sub = COALESCE(EXCLUDED.user_sub, sessions.user_sub),
    ended_at = EXCLUDED.ended_at,
    end_reason = EXCLUDED.end_reason,
    frames_expected = EXCLUDED.frames_expected,
    first_frame_us = EXCLUDED.first_frame_us,
    last_frame_us = EXCLUDED.last_frame_us,
    end_event = EXCLUDED.end_event,
    updated_at = now()
"""

# DO UPDATE (a no-op touch) so RETURNING gives the id for existing rows too
_SESSION_ID = """
INSERT INTO sessions (source_session, device_id, user_sub) VALUES (%(source_session)s, %(device_id)s, %(user_sub)s)
ON CONFLICT (source_session, device_id) DO UPDATE SET
    user_sub = COALESCE(EXCLUDED.user_sub, sessions.user_sub),
    updated_at = now()
RETURNING id
"""

_INSERT_FRAME = """
INSERT INTO session_frames (session_pk, base_timestamp_us, gps, imu, imu_missing, can, received_at)
VALUES (%(pk)s, %(base)s, %(gps)s, %(imu)s, %(imu_missing)s, %(can)s, %(received_at)s)
ON CONFLICT DO NOTHING
"""

_COUNT_FRAME = """
UPDATE sessions SET
    frames_received = frames_received + 1,
    last_frame_received_at = GREATEST(COALESCE(last_frame_received_at, %(received_at)s), %(received_at)s)
WHERE id = %(pk)s
"""


def _int(value: Any) -> int | None:
    return int(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def apply(conn, record: dict[str, Any]) -> str:
    """Write one record in its own transaction; returns what it was (for counting)."""
    kind = record.get("message_type")
    key = {
        "source_session": str(record["source_session"]),
        "device_id": str(record["device_id"]),
        "user_sub": record.get("user_sub") if isinstance(record.get("user_sub"), str) else None,
    }
    payload = record.get("payload") or {}
    with conn:
        with conn.cursor() as cur:
            if kind == "session_start":
                cur.execute(_UPSERT_START, {
                    **key,
                    "mode": payload.get("mode"),
                    "vehicle_id": _int(payload.get("vehicle_id")),
                    "track_id": _int(payload.get("track_id")),
                    "started_at": payload.get("captured_at") or record.get("captured_at"),
                    "event": json.dumps(payload),
                })
            elif kind == "session_end":
                cur.execute(_UPSERT_END, {
                    **key,
                    "ended_at": payload.get("captured_at") or record.get("captured_at"),
                    "reason": payload.get("reason"),
                    "frames": _int(payload.get("frames")),
                    "first_frame_us": _int(payload.get("first_frame_us")),
                    "last_frame_us": _int(payload.get("last_frame_us")),
                    "event": json.dumps(payload),
                })
            elif kind == "frame":
                cur.execute(_SESSION_ID, key)
                pk = cur.fetchone()[0]
                received_at = record.get("received_at")
                cur.execute(_INSERT_FRAME, {
                    "pk": pk,
                    "base": int(payload["base_timestamp_us"]),
                    "gps": _int(payload.get("gps")) or 0,
                    "imu": _int(payload.get("imu")) or 0,
                    "imu_missing": _int(payload.get("imu_missing")) or 0,
                    "can": _int(payload.get("can")) or 0,
                    "received_at": received_at,
                })
                if cur.rowcount == 1:
                    cur.execute(_COUNT_FRAME, {"pk": pk, "received_at": received_at})
                else:
                    return "duplicate"
            else:
                return "ignored"
    return kind
