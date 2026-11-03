"""The service loop end to end: a fake Iggy (recorded topics) and a real TimescaleDB.
Needs TEST_TIMESCALE_HOST like test_store.py. Covers the wiring the unit tests can't:
polling, the tick, writes, saved positions, and a restart that must neither
duplicate nor lose a frame."""

import asyncio
import json
import os
from types import SimpleNamespace

import pytest

from can_pub_sub_probe.iggy_backend import _encode_message

HOST = os.environ.get("TEST_TIMESCALE_HOST")
pytestmark = pytest.mark.skipif(not HOST, reason="needs TEST_TIMESCALE_HOST")

BASE = 1_793_743_684_000_000
TOPICS = ["telemetry.raw.gps", "telemetry.raw.imu", "telemetry.decoded.can", "telemetry.unknown.can", "telemetry.session"]


def record(base, **payload):
    return {"source_session": "s1", "device_id": "phone-1",
            "captured_at": "2026-11-03T21:28:04.100000+00:00", "payload": {"base_timestamp_us": base, **payload}}


def recorded_session(frames):
    topics = {t: [] for t in TOPICS}
    for i in range(frames):
        base = BASE + i * 1_000_000
        topics["telemetry.raw.gps"].append(record(base, latitude=42.0 + i, longitude=-71.0))
        topics["telemetry.raw.imu"].append(record(base, accel_m_s2={"x": 0, "y": 0, "z": 9.8},
                                                  gyro_deg_s={"x": 0, "y": 0, "z": 0}))
        topics["telemetry.decoded.can"].append(record(base, dt_us=5000, signals={"EngineRPM": 800.0 + i}, dbc="impala"))
        topics["telemetry.session"].append({**record(base, gps=1, imu=1, can=1, imu_missing=0), "message_type": "frame"})
    return topics


class FakeClient:
    topics: dict[str, list[dict]] = {}

    @classmethod
    def from_connection_string(cls, _):
        return cls()

    async def connect(self):
        pass

    async def get_topic(self, _stream, topic):
        return SimpleNamespace(messages_count=len(self.topics[topic]))

    async def create_topic(self, *_a, **_k):
        pass

    async def poll_messages(self, _stream, topic, _partition, strategy, count, _auto_commit):
        start = strategy.value
        out = []
        for offset, rec in enumerate(self.topics[topic][start:start + count], start):
            body = _encode_message(json.dumps(rec).encode(), {})
            out.append(SimpleNamespace(offset=lambda o=offset: o, payload=lambda b=body: b))
        return out


async def run_until(main, store, rows, timeout=20):
    task = asyncio.create_task(main.run(main.Settings()))
    for _ in range(timeout * 10):
        await asyncio.sleep(0.1)
        if len(await asyncio.to_thread(store.rows, "s1", "phone-1")) >= rows:
            break
    await asyncio.sleep(6)  # one offset save (every 5 s)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


def test_service_writes_rows_saves_positions_and_restarts_cleanly(monkeypatch):
    from app import main
    from app.store import Store

    port = os.environ.get("TEST_TIMESCALE_PORT", "5432")
    for k, v in {"IGGY_CONNECTION_STRING": "fake", "TS_HOST": HOST, "TS_PORT": port, "TS_USER": "postgres",
                 "TS_PASSWORD": "", "TS_TABLE": "vehicle_state_run", "VEHICLE_STATE_GRACE_S": "1"}.items():
        monkeypatch.setenv(k, v)
    monkeypatch.setattr(main, "IggyClient", FakeClient)
    store = Store({"host": HOST, "port": int(port), "dbname": "postgres", "user": "postgres", "password": ""},
                  table="vehicle_state_run")
    store.connect()
    with store.conn, store.conn.cursor() as cur:
        cur.execute("DROP TABLE IF EXISTS telemetry.vehicle_state_run, telemetry.vehicle_state_run_offsets")

    FakeClient.topics = recorded_session(5)
    asyncio.run(run_until(main, store, rows=5))
    rows = store.rows("s1", "phone-1")
    assert [r["signals"]["EngineRPM"] for r in rows] == [800.0, 801.0, 802.0, 803.0, 804.0]
    assert all(r["complete"] for r in rows) and [r["lat"] for r in rows] == [42.0, 43.0, 44.0, 45.0, 46.0]
    assert store.load_offsets()["telemetry.session"] == 5  # everything written: read on from the end

    # restart with three more frames recorded: only those are added, nothing duplicated
    FakeClient.topics = recorded_session(8)
    asyncio.run(run_until(main, store, rows=8))
    rows = store.rows("s1", "phone-1")
    assert [r["signals"]["EngineRPM"] for r in rows] == [800.0 + i for i in range(8)]
    assert store.load_offsets()["telemetry.decoded.can"] == 8
