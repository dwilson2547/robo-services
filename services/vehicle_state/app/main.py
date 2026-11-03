"""vehicle_state: decoded CAN + GPS + IMU + frame receipts -> Timescale vehicle_state rows.

docs/plans/2026-11-03-trip-pipelines.md §3. One consumer group per input topic
(auto-commit off): FrameTable regroups each frame from its tagged samples, a
1 s tick writes frames once they are complete (or can no longer grow), and
offsets are stored only below the oldest frame not yet written.
"""

from __future__ import annotations

import asyncio
import copy
import inspect
import datetime
import json
import logging
import os
import signal
import time
from collections import Counter

import psycopg2
from apache_iggy import AutoCommit, IggyClient, PollingStrategy

from can_pub_sub_probe.consumer_watchdog import exit_for_restart, topic_count_reader, watch
from can_pub_sub_probe.iggy_backend import _decode_message

from .frames import KINDS, FrameTable, Key
from .registry import Registry, SessionInfo, fetch_from_registry
from .snapshot import Carry, build_rows
from .store import Store

LOG = logging.getLogger("vehicle-state")
PARTITION = 0
# wait this long for the registry to know a session (session_start) before writing
# its frames without vehicle and roles
UNKNOWN_SESSION_WAIT_S = 30.0


class Settings:
    def __init__(self) -> None:
        self.connection_string = os.environ["IGGY_CONNECTION_STRING"]
        self.stream = os.getenv("IGGY_STREAM", "can-pub-sub-probe")
        self.group = os.getenv("VEHICLE_STATE_GROUP", "vehicle-state")
        self.topics = {
            "gps": os.getenv("VEHICLE_STATE_GPS_TOPIC", "telemetry.raw.gps"),
            "imu": os.getenv("VEHICLE_STATE_IMU_TOPIC", "telemetry.raw.imu"),
            "can": os.getenv("VEHICLE_STATE_CAN_TOPIC", "telemetry.decoded.can"),
            "unknown": os.getenv("VEHICLE_STATE_UNKNOWN_TOPIC", "telemetry.unknown.can"),
            "session": os.getenv("VEHICLE_STATE_SESSION_TOPIC", "telemetry.session"),
        }
        self.registry_url = os.getenv("REGISTRY_URL", "")
        self.interval_us = int(os.getenv("STATE_INTERVAL_MS", "1000")) * 1000
        if self.interval_us <= 0 or 1_000_000 % self.interval_us:
            raise ValueError("STATE_INTERVAL_MS must divide 1000")
        self.grace_s = float(os.getenv("VEHICLE_STATE_GRACE_S", "10"))
        self.db = {
            "host": os.environ["TS_HOST"],
            "port": int(os.getenv("TS_PORT", "5432")),
            "dbname": os.getenv("TS_NAME", "postgres"),
            "user": os.environ["TS_USER"],
            "password": os.environ["TS_PASSWORD"],
        }
        self.schema = os.getenv("TS_SCHEMA", "telemetry")
        self.table = os.getenv("TS_TABLE", "vehicle_state")


class SessionState:
    def __init__(self, written: set[int], carry: Carry) -> None:
        self.written, self.carry = written, carry


async def run(settings: Settings) -> None:
    store = Store(settings.db, settings.schema, settings.table)
    store.connect()
    store.ensure_schema()
    registry = Registry(fetch_from_registry(settings.registry_url) if settings.registry_url else None)
    table = FrameTable(settings.grace_s)
    sessions: dict[Key, SessionState] = {}
    counts: Counter = Counter()
    stop = asyncio.Event()

    consumers = {}
    clients = {}
    for kind in KINDS:
        client = IggyClient.from_connection_string(settings.connection_string)
        await client.connect()
        topic = settings.topics[kind]
        if await client.get_topic(settings.stream, topic) is None:
            await client.create_topic(settings.stream, topic, partitions_count=1)
        clients[kind] = client
        consumers[kind] = await client.consumer_group(
            settings.group,
            settings.stream,
            topic,
            partition_id=PARTITION,
            polling_strategy=PollingStrategy.Next(),
            batch_length=500,
            auto_commit=AutoCommit.Disabled(),
            poll_interval=datetime.timedelta(milliseconds=100),
        )
    LOG.info(
        "%s -> %s.%s every %d ms, consumer group %r, roles from %s",
        ", ".join(settings.topics.values()), settings.schema, settings.table, settings.interval_us // 1000,
        settings.group, settings.registry_url or "nowhere",
    )

    def handler(kind: str):
        async def on_message(message) -> None:
            try:
                payload, _ = _decode_message(message.payload())
                record = json.loads(payload)
                if isinstance(record, dict):
                    table.ingest(kind, message.offset(), record, time.monotonic())
                    return
            except (ValueError, KeyError, TypeError):
                LOG.warning("unreadable %s record at offset %s", kind, message.offset(), exc_info=True)
            table.last_offset[kind] = message.offset()
            counts["unreadable"] += 1

        return on_message

    async def write_ready() -> None:
        now = time.monotonic()
        for key, frames in table.ready(now).items():
            session, device = key
            state = sessions.get(key)
            if state is None:
                written, carry = await asyncio.to_thread(store.session_state, session, device, settings.interval_us)
                state = sessions[key] = SessionState(written, carry)
            for base, frame, status in frames:
                if base in state.written:  # replayed after a restart
                    table.close(key, base)
                    counts["replayed"] += 1
                    continue
                if status == "empty":
                    table.close(key, base)
                    counts["empty"] += 1
                    continue
                info = await registry.info(device, session)
                if info is None:
                    if now - frame.opened_at < UNKNOWN_SESSION_WAIT_S:
                        break  # the session_start may still be on its way
                    LOG.warning("session %s:%s unknown to the registry; writing without vehicle", session, device)
                    info = SessionInfo()
                carry = copy.copy(state.carry)
                rows = build_rows(frame.data, settings.interval_us, carry, info.roles, status == "complete")
                for row in rows:
                    row["vehicle_id"], row["user_sub"] = info.vehicle_id, info.user_sub
                await asyncio.to_thread(store.write, rows)
                state.carry = carry
                state.written.add(base)
                table.close(key, base)
                counts[status] += 1
                counts["rows"] += len(rows)

    async def store_offsets(stored: dict[str, int | None]) -> None:
        for kind, offset in table.commit_offsets().items():
            if offset is not None and offset != stored.get(kind):
                result = consumers[kind].store_offset(offset, PARTITION)
                if inspect.isawaitable(result):
                    await result
                stored[kind] = offset

    async def ticker() -> None:
        stored: dict[str, int | None] = {}
        last_report = last_offsets = time.monotonic()
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), 1.0)
            except asyncio.TimeoutError:
                pass
            try:
                await write_ready()
            except psycopg2.Error:
                LOG.warning("timescale write failed; reconnecting, retrying next tick", exc_info=True)
                await asyncio.to_thread(store.connect)
            except Exception:
                LOG.warning("tick failed; retrying", exc_info=True)
            now = time.monotonic()
            if now - last_offsets >= 5 or stop.is_set():
                try:
                    await store_offsets(stored)
                except Exception:
                    LOG.warning("storing offsets failed", exc_info=True)
                last_offsets = now
            if now - last_report >= 60:
                LOG.info("%s open=%d skipped=%s", " ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "idle",
                         table.open_count(), dict(table.skipped))
                last_report = now

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)

    # consume_messages returns a Future (not a coroutine): ensure_future, not create_task
    tasks = [asyncio.ensure_future(consumers[k].consume_messages(handler(k), stop)) for k in KINDS]
    # apache-iggy does not rejoin a group after an Iggy restart; exit so the pod restarts
    tasks += [
        asyncio.create_task(watch(
            topic_count_reader(clients[k], settings.stream, settings.topics[k]),
            (lambda k=k: table.last_offset[k]), exit_for_restart, stop,
        ))
        for k in KINDS
    ]
    tick = asyncio.create_task(ticker())
    await asyncio.gather(*tasks)
    await tick


def main() -> int:
    logging.basicConfig(level=os.getenv("VEHICLE_STATE_LOG_LEVEL", "INFO"))
    asyncio.run(run(Settings()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
