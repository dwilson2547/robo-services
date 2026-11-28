"""Raw topics -> Parquet in the archive bucket, without losing records.

Each topic is read by explicit offset. A topic's position is the stored offset of
the consumer group `archive-writer`, and it only moves once every record up to it
is in the bucket. A crash, a failed upload or an Iggy restart therefore re-reads
and rewrites: a record can be archived twice (same `iggy_offset`), never skipped.

    python -m can_pub_sub_probe.archive_writer                      # run
    python -m can_pub_sub_probe.archive_writer --seed latest        # set each topic's position, and exit

Run one instance only (Deployment strategy Recreate): a partition belongs to one
group member. A group with no stored offset starts at the first retained message.
"""

from __future__ import annotations

import argparse
import asyncio
import datetime as _datetime
import io
import json
import logging
import os
import re
import signal
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

import boto3
import pyarrow as pa
import pyarrow.parquet as pq
from apache_iggy import AutoCommit, IggyClient, PollingStrategy

from . import observability as obs
from .iggy_backend import IggyMessage, _decode_message

LOG = logging.getLogger("archive-writer")
PARTITION = 0
# records held in memory per topic while uploads fail; then the topic stops being read
MAX_UNFLUSHED = 20_000
# this many failed reads or offset stores in a row: exit, and restart from the stored offsets
MAX_IGGY_FAILURES = 5
UNPLACED_GIVE_UP_S = 60.0


class Unplaced(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class ArchiveWriterSettings:
    iggy_connection_string: str
    iggy_stream: str
    topics: tuple[str, ...]
    s3_endpoint: str
    s3_bucket: str
    s3_access_key: str
    s3_secret_key: str
    key_prefix: str
    max_records_per_file: int
    max_seconds_per_file: float
    idle_sleep_seconds: float
    group: str

    @classmethod
    def from_env(cls) -> "ArchiveWriterSettings":
        topics = tuple(
            topic.strip()
            for topic in os.getenv(
                "ARCHIVE_WRITER_TOPICS",
                "telemetry.raw.gps,telemetry.raw.imu,telemetry.raw.can,telemetry.raw.rtk",
            ).split(",")
            if topic.strip()
        )
        return cls(
            iggy_connection_string=require_env("IGGY_CONNECTION_STRING"),
            iggy_stream=os.getenv("IGGY_STREAM", "can-pub-sub-probe"),
            topics=topics,
            s3_endpoint=os.getenv("ARCHIVE_WRITER_S3_ENDPOINT", "http://192.168.0.10:30320"),
            s3_bucket=os.getenv("ARCHIVE_WRITER_S3_BUCKET", "race-logger"),
            s3_access_key=require_env("ARCHIVE_WRITER_S3_ACCESS_KEY"),
            s3_secret_key=require_env("ARCHIVE_WRITER_S3_SECRET_KEY"),
            key_prefix=os.getenv("ARCHIVE_WRITER_KEY_PREFIX", "raw-telemetry"),
            max_records_per_file=int(os.getenv("ARCHIVE_WRITER_MAX_RECORDS_PER_FILE", "250")),
            max_seconds_per_file=float(os.getenv("ARCHIVE_WRITER_MAX_SECONDS_PER_FILE", "30")),
            idle_sleep_seconds=float(os.getenv("ARCHIVE_WRITER_IDLE_SLEEP_SECONDS", "1.0")),
            group=os.getenv("ARCHIVE_WRITER_GROUP", "archive-writer"),
        )


@dataclass(frozen=True, slots=True)
class BufferKey:
    topic: str
    source_session: str
    date: str
    hour: str


@dataclass(slots=True)
class BufferState:
    started_at: datetime
    records: list[dict[str, Any]] = field(default_factory=list)


class TopicArchiver:
    """One topic: read, buffer by session and hour, upload, and only then move the stored offset."""

    def __init__(self, settings: ArchiveWriterSettings, s3, client, topic: str) -> None:
        self.settings, self.s3, self.client, self.topic = settings, s3, client, topic
        self.group = None
        self.next: int | None = None  # next offset to read; None until the group has said
        self.stored: int | None = None
        self.buffers: dict[BufferKey, BufferState] = {}
        self.iggy_failures = 0
        self.waiting_from: int | None = None  # the topic's end when we first asked for a position
        self.new_since: float | None = None

    async def join(self) -> None:
        self.group = await self.client.consumer_group(
            self.settings.group, self.settings.iggy_stream, self.topic, partition_id=PARTITION,
            polling_strategy=PollingStrategy.Next(), batch_length=1, auto_commit=AutoCommit.Disabled(),
            poll_interval=_datetime.timedelta(milliseconds=200))

    async def find_position(self, wait_s: float = 2.0) -> bool:
        """The first message the group offers is the one after its stored offset. The SDK
        cannot read the offset itself, so until a message is offered the position stays
        unknown: guessing "caught up" could skip records."""

        async def first() -> int | None:
            async for message in self.group.iter_messages():
                return message.offset()
            return None

        try:
            offset = await asyncio.wait_for(first(), wait_s)
        except asyncio.TimeoutError:
            offset = None
        if offset is None:
            await self._check_still_unplaced()
            return False
        self.next, self.stored = offset, offset - 1
        LOG.info("%s: resuming at offset %d", self.topic, offset)
        return True

    async def _check_still_unplaced(self) -> None:
        """No offer is normal when caught up and quiet. New messages and still no offer
        means we do not hold the partition: give up rather than sit silently."""
        end = await self.end()
        if self.waiting_from is None:
            self.waiting_from = end
        elif end > self.waiting_from:
            self.new_since = self.new_since or time.monotonic()
            if time.monotonic() - self.new_since > UNPLACED_GIVE_UP_S:
                raise Unplaced(f"{self.topic}: new messages arrive but the group offers none")

    async def end(self) -> int:
        """One past the newest offset. Not messages_count, which shrinks when old messages are deleted."""
        last = await self.client.poll_messages(
            self.settings.iggy_stream, self.topic, PARTITION, PollingStrategy.Last(), 1, False)
        return last[-1].offset() + 1 if last else 0

    @property
    def unflushed(self) -> int:
        return sum(len(state.records) for state in self.buffers.values())

    async def read(self) -> int:
        if self.next is None and not await self.find_position():
            return 0
        if self.unflushed >= MAX_UNFLUSHED:
            return 0
        messages = await self.client.poll_messages(
            self.settings.iggy_stream, self.topic, PARTITION, PollingStrategy.Offset(self.next),
            self.settings.max_records_per_file, False)
        if not messages:
            return 0
        if messages[0].offset() > self.next:
            lost = messages[0].offset() - self.next
            LOG.error("%s: offsets %d..%d were deleted before they were archived (%d records)",
                      self.topic, self.next, messages[0].offset() - 1, lost)
            obs.archive_lost.add(lost, {"topic": self.topic})
        for message in messages:
            payload, headers = _decode_message(message.payload())
            archived_at = datetime.now(UTC)
            record = normalize_record(topic=self.topic, message=IggyMessage(payload, headers, message.offset()),
                                      archived_at=archived_at)
            self.buffers.setdefault(build_buffer_key(record), BufferState(started_at=archived_at)).records.append(record)
        self.next = messages[-1].offset() + 1
        return len(messages)

    async def flush(self, *, everything: bool = False) -> None:
        now = datetime.now(UTC)
        for key, state in list(self.buffers.items()):
            due = (everything or len(state.records) >= self.settings.max_records_per_file
                   or (now - state.started_at).total_seconds() >= self.settings.max_seconds_per_file)
            if not due:
                continue
            try:
                await asyncio.to_thread(flush_buffer, self.settings, self.s3, key, state)
            except Exception:  # keep the records; the offset stays below them
                obs.archive_failures.add(1, {"topic": self.topic})
                LOG.warning("%s: upload failed; will retry", self.topic, exc_info=True)
                return
            obs.archive_records.add(len(state.records), {"topic": self.topic})
            del self.buffers[key]

    async def commit(self) -> None:
        if self.next is None:
            return
        oldest = [state.records[0]["iggy_offset"] for state in self.buffers.values() if state.records]
        done = (min(oldest) if oldest else self.next) - 1
        if done >= 0 and done > (self.stored if self.stored is not None else -1):
            await asyncio.wait_for(self.group.store_offset(done, PARTITION), 10)
            self.stored = done

    async def step(self) -> int:
        try:
            read = await self.read()
            await self.flush()
            await self.commit()
        except Unplaced:
            raise
        except Exception:
            self.iggy_failures += 1
            LOG.warning("%s: iggy failed (%d in a row)", self.topic, self.iggy_failures, exc_info=True)
            if self.iggy_failures >= MAX_IGGY_FAILURES:
                raise
            return 0
        self.iggy_failures = 0
        return read


async def run(settings: ArchiveWriterSettings, s3, stop: asyncio.Event, client_factory=None,
              report_every_s: float = 30.0) -> None:
    client_factory = client_factory or (lambda: IggyClient.from_connection_string(settings.iggy_connection_string))
    archivers = []
    for topic in settings.topics:
        client = client_factory()
        await client.connect()
        if await client.get_stream(settings.iggy_stream) is None:
            await client.create_stream(settings.iggy_stream)
        if await client.get_topic(settings.iggy_stream, topic) is None:
            await client.create_topic(settings.iggy_stream, topic, partitions_count=1)
        archiver = TopicArchiver(settings, s3, client, topic)
        await archiver.join()
        archivers.append(archiver)
        for counter in (obs.archive_records, obs.archive_failures, obs.archive_lost):
            counter.add(0, {"topic": topic})  # so the series exist before the first event

    async def serve(archiver: TopicArchiver) -> None:
        reported = 0.0
        while not stop.is_set():
            read = await archiver.step()
            obs.archive_polls.add(1, {"topic": archiver.topic})
            if time.monotonic() - reported >= report_every_s and archiver.stored is not None:
                reported = time.monotonic()
                try:
                    obs.set_backlog("archive-writer", archiver.topic, await archiver.end(), archiver.stored)
                except Exception:
                    LOG.warning("%s: could not read the newest offset", archiver.topic, exc_info=True)
            if not read:
                try:
                    await asyncio.wait_for(stop.wait(), settings.idle_sleep_seconds)
                except asyncio.TimeoutError:
                    pass
        await archiver.flush(everything=True)
        await archiver.commit()

    tasks = [asyncio.create_task(serve(a)) for a in archivers]
    try:
        await asyncio.gather(*tasks)
    finally:
        for task in tasks:
            task.cancel()


async def seed(settings: ArchiveWriterSettings, target: str) -> None:
    """Store a position per topic: `latest` (everything there is already archived) or an offset."""
    for topic in settings.topics:
        client = IggyClient.from_connection_string(settings.iggy_connection_string)
        await client.connect()
        archiver = TopicArchiver(settings, None, client, topic)
        offset = await archiver.end() - 1 if target == "latest" else int(target)
        if offset < 0:
            LOG.info("%s: empty, nothing to store", topic)
            continue
        await archiver.join()
        await asyncio.wait_for(archiver.group.store_offset(offset, PARTITION), 10)
        LOG.info("%s: group %r stored offset %d", topic, settings.group, offset)


async def _main(args: argparse.Namespace) -> None:
    settings = ArchiveWriterSettings.from_env()
    if args.seed:
        await seed(settings, args.seed)
        return
    s3 = build_s3_client(settings)
    s3.head_bucket(Bucket=settings.s3_bucket)
    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    LOG.info("archiving %s to bucket=%s as group %r", ",".join(settings.topics), settings.s3_bucket, settings.group)
    await run(settings, s3, stop)


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=os.getenv("ARCHIVE_WRITER_LOG_LEVEL", "INFO"))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", help="store a position per topic (latest, or an offset) and exit")
    args = ap.parse_args(argv)
    obs.setup("archive-writer")
    asyncio.run(_main(args))
    return 0


def build_s3_client(settings: ArchiveWriterSettings):
    return boto3.client(
        "s3",
        endpoint_url=settings.s3_endpoint,
        aws_access_key_id=settings.s3_access_key,
        aws_secret_access_key=settings.s3_secret_key,
        region_name="us-east-1",
    )


def normalize_record(*, topic: str, message: IggyMessage, archived_at: datetime) -> dict[str, Any]:
    payload_text = message.payload.decode("utf-8", errors="replace")
    try:
        payload_obj = json.loads(payload_text)
    except ValueError:
        payload_obj = None
    if not isinstance(payload_obj, dict):  # archive it as it came rather than stop on it
        payload_obj = {}
    captured_at = payload_obj.get("captured_at") or payload_obj.get("received_at")
    event_time = parse_event_time(captured_at, archived_at)
    return {
        "topic": topic,
        "device_id": str(payload_obj.get("device_id") or "unknown-device"),
        "source_session": str(payload_obj.get("source_session") or "unknown-session"),
        "captured_at": payload_obj.get("captured_at"),
        "received_at": payload_obj.get("received_at"),
        "message_type": payload_obj.get("message_type"),
        "source": payload_obj.get("source"),
        "event_date": event_time.strftime("%Y-%m-%d"),
        "event_hour": event_time.strftime("%H"),
        "event_timestamp": event_time.isoformat().replace("+00:00", "Z"),
        "archived_at": archived_at.isoformat().replace("+00:00", "Z"),
        "headers_json": json.dumps(message.headers, separators=(",", ":"), sort_keys=True),
        "payload_json": payload_text,
        "iggy_offset": message.offset,
    }


def build_buffer_key(record: dict[str, Any]) -> BufferKey:
    return BufferKey(
        topic=str(record["topic"]),
        source_session=str(record["source_session"]),
        date=str(record["event_date"]),
        hour=str(record["event_hour"]),
    )


def flush_buffer(settings: ArchiveWriterSettings, s3, key: BufferKey, state: BufferState) -> None:
    if not state.records:
        return
    key_name = build_object_key(
        prefix=settings.key_prefix,
        topic=key.topic,
        source_session=key.source_session,
        event_date=key.date,
        event_hour=key.hour,
    )
    table = pa.Table.from_pylist(state.records)
    output = io.BytesIO()
    pq.write_table(table, output, compression="snappy")
    output.seek(0)
    s3.put_object(
        Bucket=settings.s3_bucket,
        Key=key_name,
        Body=output.getvalue(),
        ContentType="application/vnd.apache.parquet",
    )
    LOG.info(
        "Wrote parquet archive object bucket=%s key=%s records=%s",
        settings.s3_bucket,
        key_name,
        len(state.records),
    )


def build_object_key(*, prefix: str, topic: str, source_session: str, event_date: str, event_hour: str) -> str:
    safe_topic = sanitize_path_value(topic)
    safe_session = sanitize_path_value(source_session)
    part = datetime.now(UTC).strftime("%Y%m%dT%H%M%S")
    return (
        f"{prefix}/topic={safe_topic}/source_session={safe_session}/date={event_date}/hour={event_hour}/"
        f"part-{part}-{uuid.uuid4().hex[:12]}.parquet"
    )


def sanitize_path_value(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._=-]+", "_", value)


def parse_event_time(value: str | None, fallback: datetime) -> datetime:
    if not value:
        return fallback
    normalized = value.replace("Z", "+00:00")
    try:
        return datetime.fromisoformat(normalized).astimezone(UTC)
    except ValueError:
        return fallback


def require_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise ValueError(f"{name} must be set")
    return value


if __name__ == "__main__":
    raise SystemExit(main())
