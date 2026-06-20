from __future__ import annotations

import io
import json
import logging
import os
import re
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

import boto3
import pyarrow as pa
import pyarrow.parquet as pq
from apache_iggy import PollingStrategy

from .iggy_backend import IggyBackendConfig, IggyMessage, IggyPubSubBackend

LOG = logging.getLogger("archive-writer")
logging.basicConfig(level=os.getenv("ARCHIVE_WRITER_LOG_LEVEL", "INFO"))


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
    max_seconds_per_file: int
    idle_sleep_seconds: float
    start_strategy: str

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
            max_seconds_per_file=int(os.getenv("ARCHIVE_WRITER_MAX_SECONDS_PER_FILE", "30")),
            idle_sleep_seconds=float(os.getenv("ARCHIVE_WRITER_IDLE_SLEEP_SECONDS", "1.0")),
            start_strategy=os.getenv("ARCHIVE_WRITER_START_STRATEGY", "latest"),
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


def main() -> int:
    settings = ArchiveWriterSettings.from_env()
    s3 = build_s3_client(settings)
    s3.head_bucket(Bucket=settings.s3_bucket)
    backend = IggyPubSubBackend(
        IggyBackendConfig(
            connection_string=settings.iggy_connection_string,
            stream=settings.iggy_stream,
            poll_count=settings.max_records_per_file,
        )
    )
    backend.ping()

    LOG.info(
        "Archive writer connected to bucket=%s endpoint=%s topics=%s",
        settings.s3_bucket,
        settings.s3_endpoint,
        ",".join(settings.topics),
    )

    buffers: dict[BufferKey, BufferState] = {}
    initial_polling_strategies = {
        topic: build_initial_polling_strategy(backend, topic, settings.start_strategy)
        for topic in settings.topics
    }
    try:
        while True:
            saw_messages = False
            for topic in settings.topics:
                polling_strategy = initial_polling_strategies.pop(topic, None)
                for message in backend.subscribe(topic, polling_strategy=polling_strategy):
                    saw_messages = True
                    archived_at = datetime.now(UTC)
                    record = normalize_record(topic=topic, message=message, archived_at=archived_at)
                    key = build_buffer_key(record)
                    state = buffers.setdefault(key, BufferState(started_at=archived_at))
                    state.records.append(record)
                    if len(state.records) >= settings.max_records_per_file:
                        flush_buffer(settings, s3, key, state)
                        buffers.pop(key, None)
            flush_due_buffers(settings, s3, buffers)
            if not saw_messages:
                time.sleep(settings.idle_sleep_seconds)
    except KeyboardInterrupt:
        LOG.info("Stopping archive writer, flushing %s remaining buffers", len(buffers))
        for key, state in list(buffers.items()):
            flush_buffer(settings, s3, key, state)
            buffers.pop(key, None)
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
    payload_obj = json.loads(payload_text)
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
    }


def build_buffer_key(record: dict[str, Any]) -> BufferKey:
    return BufferKey(
        topic=str(record["topic"]),
        source_session=str(record["source_session"]),
        date=str(record["event_date"]),
        hour=str(record["event_hour"]),
    )


def flush_due_buffers(settings: ArchiveWriterSettings, s3, buffers: dict[BufferKey, BufferState]) -> None:
    now = datetime.now(UTC)
    for key, state in list(buffers.items()):
        if not state.records:
            buffers.pop(key, None)
            continue
        age_seconds = (now - state.started_at).total_seconds()
        if age_seconds >= settings.max_seconds_per_file:
            flush_buffer(settings, s3, key, state)
            buffers.pop(key, None)


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


def build_initial_polling_strategy(backend: IggyPubSubBackend, topic: str, start_strategy: str) -> Any:
    normalized = start_strategy.strip().lower()
    if normalized == "latest":
        latest_offset = backend.latest_offset(topic)
        if latest_offset is None:
            return PollingStrategy.Offset(0)
        return PollingStrategy.Offset(latest_offset + 1)
    if normalized == "earliest":
        return PollingStrategy.First()
    raise ValueError("ARCHIVE_WRITER_START_STRATEGY must be 'latest' or 'earliest'")


if __name__ == "__main__":
    raise SystemExit(main())
