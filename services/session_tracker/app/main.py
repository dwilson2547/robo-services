"""session_tracker: Iggy telemetry.session -> registry sessions tables.

Reads as Iggy consumer group SESSION_TRACKER_GROUP (default "session-tracker"),
committing every COMMIT_EVERY messages after they are written and once more on
shutdown, like can-decode. A new group starts at the beginning of the topic, which
is what this wants: session events are few and every write is idempotent.

consume_messages() commits even when the callback raises, so the callback never
does: an unreadable record is counted and skipped, and a database error is retried
(with a fresh connection) until the write succeeds.
"""

from __future__ import annotations

import asyncio
import datetime
import inspect
import json
import logging
import os
import signal

import psycopg2
from apache_iggy import AutoCommit, AutoCommitAfter, IggyClient, PollingStrategy

from can_pub_sub_probe import observability as obs
from can_pub_sub_probe.consumer_watchdog import exit_for_restart, topic_count_reader, watch
from can_pub_sub_probe.iggy_backend import _decode_message

from .store import apply

LOG = logging.getLogger("session-tracker")
COMMIT_EVERY = 20
PARTITION = 0


class Settings:
    def __init__(self) -> None:
        self.connection_string = os.environ["IGGY_CONNECTION_STRING"]
        self.stream = os.getenv("IGGY_STREAM", "can-pub-sub-probe")
        self.group = os.getenv("SESSION_TRACKER_GROUP", "session-tracker")
        self.topic = os.getenv("SESSION_TRACKER_TOPIC", "telemetry.session")
        # the registry's database, as separate parameters (no credentials in a URL)
        self.db = {
            "host": os.environ["DB_HOST"],
            "port": int(os.getenv("DB_PORT", "5432")),
            "dbname": os.environ["DB_NAME"],
            "user": os.environ["DB_USER"],
            "password": os.environ["DB_PASSWORD"],
        }


async def maybe_await(value):
    return await value if inspect.isawaitable(value) else value


async def run(settings: Settings) -> None:
    client = IggyClient.from_connection_string(settings.connection_string)
    await client.connect()
    if await client.get_stream(settings.stream) is None:
        await client.create_stream(settings.stream)
    if await client.get_topic(settings.stream, settings.topic) is None:
        await client.create_topic(settings.stream, settings.topic, partitions_count=1)
    consumer = await client.consumer_group(
        settings.group,
        settings.stream,
        settings.topic,
        partition_id=PARTITION,
        polling_strategy=PollingStrategy.Next(),
        batch_length=100,
        auto_commit=AutoCommit.After(AutoCommitAfter.ConsumingEveryNthMessage(COMMIT_EVERY)),
        poll_interval=datetime.timedelta(milliseconds=200),
    )
    LOG.info("%s -> registry sessions, consumer group %r", settings.topic, settings.group)

    conn = [psycopg2.connect(**settings.db)]
    counts: dict[str, int] = {}
    last_offset: list[int | None] = [None]

    async def write(record: dict) -> str:
        delay = 1.0
        while True:
            try:
                return apply(conn[0], record)
            except psycopg2.Error:
                LOG.warning("database write failed, retrying in %.0fs", delay, exc_info=True)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30.0)
                try:
                    conn[0].close()
                except psycopg2.Error:
                    pass
                try:
                    conn[0] = psycopg2.connect(**settings.db)
                except psycopg2.Error:
                    pass  # next attempt retries the connect too

    async def on_message(message) -> None:
        offset = message.offset()
        try:
            payload, _headers = _decode_message(message.payload())
            record = json.loads(payload)
            if not isinstance(record, dict) or "source_session" not in record or "device_id" not in record:
                raise ValueError("not a normalized session record")
        except (ValueError, KeyError, TypeError):
            counts["unreadable"] = counts.get("unreadable", 0) + 1
            LOG.warning("unreadable record at offset %s", offset, exc_info=True)
            last_offset[0] = offset
            return
        try:
            kind = await write(record)
        except (KeyError, TypeError, ValueError):  # malformed fields inside a readable record
            kind = "unreadable"
            LOG.warning("bad %s record at offset %s", record.get("message_type"), offset, exc_info=True)
        counts[kind] = counts.get(kind, 0) + 1
        lag = obs.seconds_since(record.get("received_at"))
        if lag is not None and kind != "unreadable":
            obs.pipeline_lag.record(lag, {"stage": "session-tracker"})
        if kind in ("session_start", "session_end"):
            LOG.info("%s %s:%s", kind, record["source_session"], record["device_id"])
        last_offset[0] = offset

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)

    async def report() -> None:
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), 60)
            except asyncio.TimeoutError:
                pass
            LOG.info("%s offset=%s", " ".join(f"{k}={v}" for k, v in sorted(counts.items())) or "idle", last_offset[0])

    reporter = asyncio.create_task(report())
    # apache-iggy does not rejoin the group after an Iggy restart; exit so the pod restarts
    watchdog = asyncio.create_task(
        watch(topic_count_reader(client, settings.stream, settings.topic), lambda: last_offset[0], exit_for_restart, stop,
              on_reading=lambda count, offset: obs.set_backlog("session-tracker", settings.topic, count, offset))
    )
    await consumer.consume_messages(on_message, stop)
    if last_offset[0] is not None:
        await maybe_await(consumer.store_offset(last_offset[0], PARTITION))
        LOG.info("stopped; committed offset %d", last_offset[0])
    await reporter
    await watchdog
    conn[0].close()


def main() -> int:
    logging.basicConfig(level=os.getenv("SESSION_TRACKER_LOG_LEVEL", "INFO"))
    obs.setup("session-tracker")
    asyncio.run(run(Settings()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
