"""Store against a real TimescaleDB. Needs TEST_TIMESCALE_HOST (port 5432, user postgres,
trust auth), e.g. docker run -d -p 55433:5432 -e POSTGRES_HOST_AUTH_METHOD=trust
timescale/timescaledb:latest-pg16 and TEST_TIMESCALE_HOST=127.0.0.1 TEST_TIMESCALE_PORT=55433."""

import os

import pytest

from app.snapshot import CanSample, FrameData, build_rows
from app.store import Store

HOST = os.environ.get("TEST_TIMESCALE_HOST")
pytestmark = pytest.mark.skipif(not HOST, reason="needs TEST_TIMESCALE_HOST")
BASE = 1_793_743_684_000_000


@pytest.fixture
def store():
    s = Store({"host": HOST, "port": int(os.environ.get("TEST_TIMESCALE_PORT", "5432")), "dbname": "postgres",
               "user": "postgres", "password": ""}, schema="telemetry", table="vehicle_state_test")
    s.connect()
    with s.conn, s.conn.cursor() as cur:
        cur.execute("DROP TABLE IF EXISTS telemetry.vehicle_state_test")
    s.ensure_schema()
    s.ensure_schema()  # idempotent
    return s


def rows_for(base, carry, rpm):
    return build_rows(FrameData("s1", "phone-1", base, can=[CanSample(base + 10, {"EngineRPM": rpm})], dbc="impala"),
                      1_000_000, carry, {"rpm": {"signal": "EngineRPM"}}, complete=True)


def test_rows_round_trip_upsert_and_restart_state(store):
    carry = {"Odometer": (100.0, BASE - 2_000_000)}
    store.write(rows_for(BASE, carry, 800.0))
    store.write(rows_for(BASE + 1_000_000, carry, 900.0))
    store.write(rows_for(BASE + 1_000_000, dict(carry), 900.0))  # a frame written twice: same row
    rows = store.rows("s1", "phone-1")
    assert [r["rpm"] for r in rows] == [800.0, 900.0]
    assert rows[1]["signals"] == {"Odometer": 100.0, "EngineRPM": 900.0}
    assert rows[1]["carried"] == {"Odometer": 4000}

    written, restored = store.session_state("s1", "phone-1", 1_000_000)
    assert written == {BASE, BASE + 1_000_000}
    assert restored["EngineRPM"] == (900.0, BASE + 2_000_000)  # last row's end: seen within that interval
    assert restored["Odometer"] == (100.0, BASE - 2_000_000)
    assert store.session_state("other", "phone-1", 1_000_000) == (set(), {})


def test_table_is_a_hypertable(store):
    with store.conn, store.conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name = 'vehicle_state_test'")
        assert cur.fetchone()[0] == 1


def test_role_columns_added_later_appear_on_an_existing_table(store):
    with store.conn, store.conn.cursor() as cur:
        cur.execute("ALTER TABLE telemetry.vehicle_state_test DROP COLUMN prndl")
    store.ensure_schema()
    with store.conn, store.conn.cursor() as cur:
        cur.execute("SELECT data_type FROM information_schema.columns WHERE table_name = 'vehicle_state_test' AND column_name = 'prndl'")
        assert cur.fetchone() == ("text",)
