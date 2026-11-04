"""One worker pass against a registry Postgres (alembic head) and a TimescaleDB.
Needs TEST_DATABASE_URL (registry, like services/registry) and TEST_TIMESCALE_HOST/PORT."""

import os
from datetime import UTC, datetime, timedelta

import pytest

REG = os.environ.get("TEST_DATABASE_URL")
TS = os.environ.get("TEST_TIMESCALE_HOST")
pytestmark = pytest.mark.skipif(not (REG and TS), reason="needs TEST_DATABASE_URL and TEST_TIMESCALE_HOST")


def test_pass_summarises_settled_sessions_once_and_again_when_frames_arrive():
    import psycopg2
    from psycopg2.extras import Json

    from app.main import summarise_pending
    from app.metrics import VERSION

    reg = psycopg2.connect(REG)
    ts = psycopg2.connect(host=TS, port=int(os.environ.get("TEST_TIMESCALE_PORT", "5432")), dbname="postgres", user="postgres")
    now = datetime.now(UTC)
    with reg, reg.cursor() as c:
        c.execute("TRUNCATE sessions RESTART IDENTITY CASCADE")
        c.execute("INSERT INTO sessions (source_session, device_id, frames_expected, frames_received, ended_at, last_frame_received_at)"
                  " VALUES ('done', 'p', 3, 3, %s, %s), ('open', 'p', NULL, 5, NULL, %s), ('old', 'p', 9, 9, %s, %s)",
                  (now - timedelta(minutes=5), now - timedelta(minutes=5), now, now - timedelta(days=1), now - timedelta(days=1)))
    with ts, ts.cursor() as c:
        c.execute("CREATE SCHEMA IF NOT EXISTS telemetry; DROP TABLE IF EXISTS telemetry.vs_pass")
        c.execute("CREATE TABLE telemetry.vs_pass (t timestamptz, source_session text, device_id text, frame_base_us bigint,"
                  " speed_kph float, rpm float, brake_on bool, brake_kpa float, gear float, prndl text, odometer_km float,"
                  " coolant_c float, oil_temp_c float, throttle_pct float, steering_deg float, lat float, lon float,"
                  " gps_speed_kph float, gps_fixes int, gyro_mean jsonb, imu_filled_in_frame int, complete bool,"
                  " received_at timestamptz, written_at timestamptz)")
        for i in range(3):
            t = now - timedelta(minutes=6) + timedelta(seconds=i)
            # frame i ends at t + 1 s, reaches kreceiver 2 s later and is written 0.5 s after that
            c.execute("INSERT INTO telemetry.vs_pass VALUES (%s, 'done', 'p', %s, 20, 900, false, 0, 1, 'D', 5, 80, null, 10, 0,"
                      " 42, -83, 20, 1, %s, 0, true, %s, %s)",
                      (t, int(t.timestamp() * 1e6), Json({"x": 0, "y": 0, "z": 0}), t + timedelta(seconds=3),
                       t + timedelta(seconds=3.5)))

    lines = summarise_pending(reg, ts, table="vs_pass")
    assert len(lines) == 1 and lines[0].startswith("session done:")  # 'open' is not settled, 'old' has no rows
    assert summarise_pending(reg, ts, table="vs_pass") == []  # nothing changed: not recomputed
    with reg, reg.cursor() as c:
        c.execute("SELECT version, frames_used, frames_received, metrics->'quality'->>'completeness_pct',"
                  " metrics->'processing' FROM session_summaries")
        [(version, used, received, pct, proc)] = c.fetchall()
        assert (version, used, received, pct) == (VERSION, 3, 3, "100.0")
        assert proc["frames_timed"] == 3
        assert proc["upload_lag_s"] == {"p50": 2.0, "p95": 2.0, "max": 2.0}
        assert proc["pipeline_lag_s"] == {"p50": 0.5, "p95": 0.5, "max": 0.5}
        assert 299 <= proc["summary_after_end_s"] <= 310  # ended 5 minutes ago, summarised now
        c.execute("UPDATE sessions SET frames_received = 4 WHERE source_session = 'done'")  # a late frame
        c.execute("UPDATE session_summaries SET computed_at = now() - interval '1 hour'")
    assert len(summarise_pending(reg, ts, table="vs_pass")) == 1
    with reg, reg.cursor() as c:  # the recompute keeps when the drive was first ready
        c.execute("SELECT metrics->'processing'->>'summary_after_end_s' FROM session_summaries")
        assert float(c.fetchone()[0]) == proc["summary_after_end_s"]
