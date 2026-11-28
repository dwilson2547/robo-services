"""Re-run tests. The database tests need a scratch Postgres (they drop and create tables):

    docker run -d --rm --name rerun-test -p 127.0.0.1:55439:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:17
    TEST_DATABASE_URL=postgresql://postgres@127.0.0.1:55439/postgres PYTHONPATH=. python -m pytest tests
"""

import io
import json
import os
from datetime import datetime, timedelta, timezone

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from app import main, rebuild as rb
from app.reuse import Store, load_dbc
from app.source import SLICE_US, S3Archive

DBC = 'BO_ 100 Speed: 8 X\n SG_ Spd : 7|16@0+ (0.01,0) [0|0] "kph" X\n'
ROLES = {"speed_kph": {"signal": "Spd"}}
SESSION, DEVICE = "1790000000000", "phone-test"
T0 = 1790000000 * 1_000_000


def record(base, payload, received="2026-09-21T00:00:09+00:00"):
    at = datetime.fromtimestamp(base / 1e6 + payload.get("dt_us", 0) / 1e6, timezone.utc)
    return json.dumps({"source_session": SESSION, "device_id": DEVICE, "captured_at": at.isoformat(),
                       "received_at": received, "payload": {"base_timestamp_us": base, **payload}})


def can(base, can_id=100, kph=50.0, dt=1000, **kw):
    return record(base, {"can_id": can_id, "data_hex": f"{int(kph * 100):04x}000000000000", "dlc": 8, "dt_us": dt}, **kw)


class Fake:
    def __init__(self, **kinds):
        self.kinds = kinds

    def batches(self, session, kind):
        # a record is its payload, or (payload, iggy offset) as newer archive files give it
        yield [r if isinstance(r, tuple) else (r, None) for r in self.kinds.get(kind, [])]


def receipt(can_count):
    return rb.Receipt(gps=0, imu=0, can=can_count, imu_missing=None, received_at=None)


def run(tmp_path, archive, receipts, dbc=True, roles=ROLES):
    rows = []
    result = rb.rebuild(archive, SESSION, DEVICE, receipts, ("t", load_dbc(DBC)) if dbc else None, roles, 1, "sub",
                        tmp_path, rows.extend)
    return result, rows


def test_a_frame_delivered_twice_counts_once_and_repeats_within_it_stay(tmp_path):
    first = [can(T0), can(T0), can(T0, dt=2000)]  # two identical records: a real repeat
    again = [can(T0, received="2026-09-21T01:00:00+00:00"), can(T0, dt=2000, received="2026-09-21T01:00:00+00:00")]
    result, rows = run(tmp_path, Fake(can=again + first), {T0: receipt(3)})
    assert (result.duplicates, result.records["can"], result.decoded) == (2, 3, 3)
    assert result.frames == {"complete": 1} and not result.short and rows[0]["speed_kph"] == 50.0


def test_a_record_the_writer_archived_twice_counts_once(tmp_path):
    repeat = can(T0)  # the same bytes at two offsets are two real frames
    archive = Fake(can=[(repeat, 5), (repeat, 6), (repeat, 5), (repeat, 6)])
    result, _ = run(tmp_path, archive, {T0: receipt(2)})
    assert (result.records["can"], result.duplicates) == (2, 2) and not result.short


def test_signals_carry_across_slices_and_late_frames_are_placed_in_time(tmp_path):
    later = (T0 // SLICE_US + 2) * SLICE_US
    archive = Fake(can=[can(later, can_id=7), can(T0, kph=42.0)])  # out of order, the later one unknown to the DBC
    result, rows = run(tmp_path, archive, {T0: receipt(1), later: receipt(1)})
    assert [r["frame_base_us"] for r in rows] == [T0, later]
    assert rows[1]["speed_kph"] == 42.0 and (result.decoded, result.unknown) == (1, 1)
    assert not list(tmp_path.iterdir())  # the spill files are removed


def test_missing_records_are_reported_even_for_a_frame_the_archive_lacks_entirely(tmp_path):
    result, _ = run(tmp_path, Fake(can=[can(T0)]), {T0: receipt(2), T0 + 1_000_000: receipt(5)})
    assert result.short == {"can": 6} and result.frames == {"incomplete": 1, "empty": 1}


def test_a_vehicle_without_a_dbc_still_gets_rows(tmp_path):
    result, rows = run(tmp_path, Fake(can=[can(T0)]), {T0: receipt(1)}, dbc=False, roles={})
    assert result.frames == {"complete": 1} and rows[0]["speed_kph"] is None


def test_s3_archive_reads_every_object_of_the_session():
    def blob(lines):
        buf = io.BytesIO()
        pq.write_table(pa.table({"payload_json": lines}), buf)
        return buf.getvalue()

    objects = {f"raw-telemetry/topic=telemetry.raw.can/source_session=9/part-{i:03d}": blob([str(i)]) for i in range(130)}

    class Client:
        def get_paginator(self, _):
            class Pages:
                def paginate(self, Bucket, Prefix):
                    keys = [k for k in objects if k.startswith(Prefix)]
                    return [{"Contents": [{"Key": k} for k in keys[:100]]}, {"Contents": [{"Key": k} for k in keys[100:]]}]
            return Pages()

        def get_object(self, Bucket, Key):
            return {"Body": io.BytesIO(objects[Key])}

    got = [line for batch in S3Archive(Client(), "b").batches("9", "can") for line in batch]
    assert got == [(str(i), None) for i in range(130)]


# --- against a database -----------------------------------------------------

needs_db = pytest.mark.skipif(not os.getenv("TEST_DATABASE_URL"), reason="TEST_DATABASE_URL not set")
SCHEMA = "rerun_test"


@pytest.fixture
def db():
    import psycopg2

    url = os.environ["TEST_DATABASE_URL"]
    reg, ts = psycopg2.connect(url), psycopg2.connect(url)
    with reg, reg.cursor() as cur:
        cur.execute(f"""
            DROP SCHEMA IF EXISTS {SCHEMA} CASCADE; CREATE SCHEMA {SCHEMA}; SET search_path = {SCHEMA};
            CREATE TABLE dbcs (id int primary key, name text, content text, roles jsonb);
            CREATE TABLE vehicles (id int primary key, dbc_id int);
            CREATE TABLE sessions (id int primary key, source_session text, device_id text, user_sub text, vehicle_id int,
                ended_at timestamptz, last_frame_received_at timestamptz, frames_expected int, frames_received int);
            CREATE TABLE session_frames (session_pk int, base_timestamp_us bigint, gps int, imu int, imu_missing int,
                can int, received_at timestamptz);
            CREATE TABLE session_summaries (session_pk int primary key, version int, frames_used int,
                frames_received int, metrics jsonb, computed_at timestamptz);""")
        cur.execute("INSERT INTO dbcs VALUES (1, 't', %s, %s)", (DBC, json.dumps(ROLES)))
        cur.execute("INSERT INTO vehicles VALUES (1, 1)")
        cur.execute("INSERT INTO sessions VALUES (1, %s, %s, 'sub', 1, now() - interval '1 day',"
                    " now() - interval '1 day', 2, 2)", (SESSION, DEVICE))
        for base in (T0, T0 + 1_000_000):
            cur.execute("INSERT INTO session_frames VALUES (1, %s, 0, 0, NULL, 1, now() - interval '1 day')", (base,))
    reg.cursor().execute(f"SET search_path = {SCHEMA}")
    reg.commit()
    store = Store({"dsn": url}, SCHEMA, hypertable=False)
    store.connect()
    store.ensure_schema()
    yield reg, ts, store
    reg.rollback()
    with reg, reg.cursor() as cur:
        cur.execute(f"DROP SCHEMA {SCHEMA} CASCADE")


def go(db, archive, **kw):
    reg, ts, _ = db
    kw.setdefault("apply", True)
    return main.rerun(SESSION, reg, ts, archive, schema=SCHEMA, **kw)


FULL = Fake(can=[can(T0), can(T0 + 1_000_000, kph=60.0)])


def speeds(db):
    return [r["speed_kph"] for r in db[2].rows(SESSION, DEVICE)]


def summaries(db):
    with db[0], db[0].cursor() as cur:
        cur.execute("SELECT metrics FROM session_summaries")
        return [r[0] for r in cur.fetchall()]


@needs_db
def test_report_only_writes_nothing_and_apply_writes_rows_and_summary(db):
    assert "report only" in go(db, FULL, apply=False)
    assert speeds(db) == [] and summaries(db) == []
    assert "replaced" in go(db, FULL)
    assert speeds(db) == [50.0, 60.0]
    assert summaries(db)[0]["rerun"]["dbc"] == "t"


@needs_db
def test_a_short_archive_or_a_crash_leaves_the_old_rows(db, monkeypatch):
    go(db, FULL)
    with pytest.raises(main.Refused, match="short"):
        go(db, Fake(can=[can(T0, kph=1.0)]))
    with pytest.raises(main.Refused, match="fewer rows"):
        go(db, Fake(can=[can(T0, kph=1.0)]), allow_short=True)

    class Broken:
        def batches(self, session, kind):
            raise OSError("bucket unreachable")

    with pytest.raises(OSError):
        go(db, Broken())

    def boom(*a, **k):
        raise RuntimeError("died after rows were written")

    real = main.rebuild

    def partway(*args, **kwargs):
        real(*args, **kwargs)  # deletes done and rows inserted in the open transaction
        boom()

    monkeypatch.setattr(main, "rebuild", partway)
    with pytest.raises(RuntimeError):
        go(db, FULL)
    assert speeds(db) == [50.0, 60.0]


@needs_db
def test_a_drive_still_receiving_frames_is_refused(db):
    with db[0], db[0].cursor() as cur:
        cur.execute("UPDATE sessions SET last_frame_received_at = %s", (datetime.now(timezone.utc) - timedelta(minutes=5),))
    with pytest.raises(main.Refused, match="30 min"):
        go(db, FULL)
