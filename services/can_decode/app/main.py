"""can_decode: Iggy telemetry.raw.can -> DBC decode -> telemetry.decoded.can.

Reads as Iggy consumer group CAN_DECODE_GROUP (default "can-decode"), whose
offset the server stores separately from the default consumer the parquet
archive writer uses, so a restart resumes where it stopped. The offset is
committed every COMMIT_EVERY messages *after* they are processed and published,
and once more on shutdown: a crash re-decodes at most that many frames, never
skips any. Where a new group starts is set explicitly with app.reset.

consume_messages() swallows a callback's exception and commits the offset
anyway, so the callback never raises: an unreadable record is counted and
skipped, and a failed publish is retried until Iggy accepts it.
"""

from __future__ import annotations

import asyncio
import datetime
import inspect
import json
import logging
import os
import signal

from apache_iggy import AutoCommit, AutoCommitAfter, IggyClient, PollingStrategy, SendMessage

from can_pub_sub_probe.consumer_watchdog import exit_for_restart, topic_count_reader, watch
from can_pub_sub_probe.iggy_backend import _decode_message, _encode_message

from .dbc_resolver import Dbc, DbcResolver, fetch_from_registry
from .decode import Decoded, decode_record, load_dbc

LOG = logging.getLogger("can-decode")
COMMIT_EVERY = 100
PARTITION = 0


class Settings:
    def __init__(self) -> None:
        self.connection_string = os.environ["IGGY_CONNECTION_STRING"]
        self.stream = os.getenv("IGGY_STREAM", "can-pub-sub-probe")
        self.group = os.getenv("CAN_DECODE_GROUP", "can-decode")
        self.in_topic = os.getenv("CAN_DECODE_INPUT_TOPIC", "telemetry.raw.can")
        self.out_topic = os.getenv("CAN_DECODE_OUTPUT_TOPIC", "telemetry.decoded.can")
        self.unknown_topic = os.getenv("CAN_DECODE_UNKNOWN_TOPIC", "")
        self.dbc_path = os.environ.get("DBC_PATH", "")
        # per-session DBCs from the registry (vehicle -> DBC); unset = DBC_PATH for everything
        self.registry_url = os.getenv("REGISTRY_URL", "")
        self.default_dbc_name = os.getenv("CAN_DECODE_DEFAULT_DBC_NAME", "default")


async def connect(settings: Settings) -> IggyClient:
    client = IggyClient.from_connection_string(settings.connection_string)
    await client.connect()
    return client


async def ensure_topic(client: IggyClient, stream: str, topic: str) -> None:
    if await client.get_stream(stream) is None:
        await client.create_stream(stream)
    if await client.get_topic(stream, topic) is None:
        await client.create_topic(stream, topic, partitions_count=1)


async def join_group(client: IggyClient, settings: Settings, auto_commit):
    return await client.consumer_group(
        settings.group,
        settings.stream,
        settings.in_topic,
        partition_id=PARTITION,
        polling_strategy=PollingStrategy.Next(),
        batch_length=200,
        auto_commit=auto_commit,
        poll_interval=datetime.timedelta(milliseconds=50),
    )


async def maybe_await(value):
    return await value if inspect.isawaitable(value) else value


async def run(settings: Settings) -> None:
    with open(settings.dbc_path, encoding="utf-8") as fh:
        default = Dbc(settings.default_dbc_name, load_dbc(fh.read()))
    resolver = DbcResolver(default, fetch_from_registry(settings.registry_url) if settings.registry_url else None)

    client = await connect(settings)
    for topic in filter(None, (settings.in_topic, settings.out_topic, settings.unknown_topic)):
        await ensure_topic(client, settings.stream, topic)
    consumer = await join_group(
        client, settings, AutoCommit.After(AutoCommitAfter.ConsumingEveryNthMessage(COMMIT_EVERY))
    )
    LOG.info(
        "%s -> %s (unknown: %s), default DBC %r (%d messages, %s), per-session DBCs from %s, consumer group %r",
        settings.in_topic, settings.out_topic, settings.unknown_topic or "dropped",
        default.name, len(default.messages), settings.dbc_path, settings.registry_url or "nowhere (default only)",
        settings.group,
    )

    counts = {"decoded": 0, "unknown": 0, "unreadable": 0}
    last_offset: list[int | None] = [None]

    async def publish(topic: str, record: dict, headers: dict[str, str]) -> None:
        body = json.dumps(record, separators=(",", ":"), sort_keys=True).encode("utf-8")
        delay = 1.0
        while True:
            try:
                await client.send_messages(
                    stream=settings.stream,
                    topic=topic,
                    partitioning=PARTITION,
                    messages=[SendMessage(_encode_message(body, headers))],
                )
                return
            except Exception:  # keep the offset uncommitted until it is published
                LOG.warning("publish to %s failed, retrying in %.0fs", topic, delay, exc_info=True)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 30.0)

    async def on_message(message) -> None:
        offset = message.offset()
        try:
            payload, headers = _decode_message(message.payload())
            record = json.loads(payload)
            dbc = await resolver.for_session(str(record.get("device_id", "")), str(record.get("source_session", "")))
            result = decode_record(record, dbc.messages, dbc.name)
        except (ValueError, KeyError, TypeError):
            counts["unreadable"] += 1
            LOG.warning("unreadable record at offset %s", offset, exc_info=True)
            last_offset[0] = offset
            return
        if isinstance(result, Decoded):
            counts["decoded"] += 1
            await publish(settings.out_topic, result.record, {**headers, "x-source-type": "can_decoded"})
        else:
            counts["unknown"] += 1
            if settings.unknown_topic:
                await publish(settings.unknown_topic, result.record, {**headers, "x-source-type": "can_unknown"})
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
            LOG.info(
                "decoded=%d unknown=%d unreadable=%d offset=%s",
                counts["decoded"], counts["unknown"], counts["unreadable"], last_offset[0],
            )

    reporter = asyncio.create_task(report())
    # apache-iggy does not rejoin the group after an Iggy restart; exit so the pod restarts
    watchdog = asyncio.create_task(
        watch(topic_count_reader(client, settings.stream, settings.in_topic), lambda: last_offset[0], exit_for_restart, stop)
    )
    await consumer.consume_messages(on_message, stop)
    if last_offset[0] is not None:
        # commit exactly what was processed, rather than up to the last Nth message
        await maybe_await(consumer.store_offset(last_offset[0], PARTITION))
        LOG.info("stopped; committed offset %d", last_offset[0])
    await reporter
    await watchdog


def main() -> int:
    logging.basicConfig(level=os.getenv("CAN_DECODE_LOG_LEVEL", "INFO"))
    asyncio.run(run(Settings()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
