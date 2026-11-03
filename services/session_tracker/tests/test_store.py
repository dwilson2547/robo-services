"""Runs against a real Postgres with the registry schema (alembic upgrade head).
Set TEST_DATABASE_URL to run; skipped otherwise."""

import os

import pytest

psycopg2 = pytest.importorskip("psycopg2")
URL = os.environ.get("TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not URL, reason="TEST_DATABASE_URL not set")

from app.store import apply  # noqa: E402

KEY = {"source_session": "1793726754694", "device_id": "phone-1"}


@pytest.fixture()
def conn():
    c = psycopg2.connect(URL)
    with c, c.cursor() as cur:
        cur.execute("TRUNCATE sessions CASCADE")
    yield c
    c.close()


def frame(base_us, received="2026-11-03T17:26:00+00:00"):
    return {**KEY, "message_type": "frame", "received_at": received,
            "payload": {"base_timestamp_us": base_us, "gps": 1, "imu": 50, "imu_missing": 0, "can": 470}}


def row(conn):
    with conn.cursor() as cur:
        cur.execute("""SELECT mode, started_at IS NOT NULL, ended_at IS NOT NULL, end_reason, frames_expected,
                              frames_received, track_id FROM sessions WHERE source_session=%s AND device_id=%s""",
                    (KEY["source_session"], KEY["device_id"]))
        return cur.fetchone()


def test_start_frames_end_and_redelivery(conn):
    start = {**KEY, "message_type": "session_start",
             "payload": {"captured_at": "2026-11-03T17:25:54.694Z", "mode": "race", "track_id": None}}
    end = {**KEY, "message_type": "session_end",
           "payload": {"captured_at": "2026-11-03T17:26:21.479Z", "frames": 3, "reason": "user_stop",
                       "first_frame_us": 1, "last_frame_us": 3}}
    assert apply(conn, start) == "session_start"
    for b in (1, 2, 2, 3):  # second 2 redelivered
        apply(conn, frame(b))
    assert apply(conn, frame(2)) == "duplicate"
    assert apply(conn, end) == "session_end"
    apply(conn, end)  # resent from the phone's outbox
    assert row(conn) == ("race", True, True, "user_stop", 3, 3, None)


def test_frames_before_a_lost_start_still_make_a_session(conn):
    apply(conn, frame(10))
    assert row(conn) == (None, False, False, None, None, 1, None)


def test_unknown_track_id_is_dropped_not_an_error(conn):
    apply(conn, {**KEY, "message_type": "session_start", "payload": {"captured_at": "2026-11-03T17:25:54Z",
                                                                       "mode": "race", "track_id": 999999}})
    assert row(conn)[6] is None


def test_other_message_types_are_ignored(conn):
    assert apply(conn, {**KEY, "message_type": "lap_anchor", "payload": {}}) == "ignored"


def test_user_comes_from_the_stamp_and_is_never_cleared(conn):
    start = {**KEY, "message_type": "session_start", "user_sub": "abc123",
             "payload": {"captured_at": "2026-11-03T17:25:54Z", "mode": "race", "user_sub": "spoofed"}}
    apply(conn, start)
    apply(conn, frame(1))  # an unstamped record (old app, bare topic)
    with conn.cursor() as cur:
        cur.execute("SELECT user_sub FROM sessions WHERE source_session=%s", (KEY["source_session"],))
        assert cur.fetchone()[0] == "abc123"
