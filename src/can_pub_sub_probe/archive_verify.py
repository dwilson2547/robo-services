"""Is every record still on the raw topics also in the Parquet archive?

    python -m can_pub_sub_probe.archive_verify [--topics a,b] [--backfill]

Reads each topic from its first retained message (explicit offsets, nothing is
committed), then compares session by session with the archive's `payload_json`.
Records are matched by content, with multiplicity. --backfill writes the missing
ones to the archive in the writer's own layout; without it nothing is written.

Run it before anything may delete from Iggy (retention, the message cleaner).
Configured like the archive writer.
"""

from __future__ import annotations

import argparse
import asyncio
import collections
import hashlib
import io
import json
import sys
import tempfile
from array import array
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

import pyarrow.parquet as pq
from apache_iggy import IggyClient, PollingStrategy

from .archive_writer import (ArchiveWriterSettings, BufferState, build_buffer_key, build_s3_client, flush_buffer,
                             normalize_record, sanitize_path_value)
from .iggy_backend import IggyMessage, _decode_message

BATCH = 2000


def digest(payload_text: str) -> int:
    return int.from_bytes(hashlib.blake2b(payload_text.encode("utf-8"), digest_size=8).digest(), "big")


def session_of(payload_text: str) -> str:
    try:
        value = json.loads(payload_text).get("source_session")
    except (ValueError, AttributeError):
        value = None
    return sanitize_path_value(str(value or "unknown-session"))


async def read_topic(client, stream: str, topic: str):
    """Every retained message as (offset, payload text, headers)."""
    offset, first = 0, True
    while True:
        strategy = PollingStrategy.First() if first else PollingStrategy.Offset(offset)
        messages = await client.poll_messages(stream, topic, 0, strategy, BATCH, False)
        first = False
        if not messages:
            return
        for message in messages:
            payload, headers = _decode_message(message.payload())
            yield message.offset(), payload.decode("utf-8", errors="replace"), headers
        offset = messages[-1].offset() + 1


def archived_digests(s3, bucket: str, prefix: str, topic: str, session: str, workers: int = 16) -> collections.Counter:
    root = f"{prefix}/topic={sanitize_path_value(topic)}/source_session={session}/"
    pages = s3.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=root)
    keys = [o["Key"] for p in pages for o in p.get("Contents", [])]

    def get(key: str) -> list[int]:
        body = io.BytesIO(s3.get_object(Bucket=bucket, Key=key)["Body"].read())
        column = pq.read_table(body, columns=["payload_json"], partitioning=None).column("payload_json")
        return [digest(text) for text in column.to_pylist()]

    found: collections.Counter = collections.Counter()
    with ThreadPoolExecutor(workers) as pool:
        for i in range(0, len(keys), 256):
            for digests in pool.map(get, keys[i:i + 256]):
                found.update(digests)
    return found


async def verify_topic(client, s3, settings, topic: str, work: Path, backfill: bool, log=print) -> dict:
    files: dict[str, Path] = {}
    buffers: dict[str, array] = collections.defaultdict(lambda: array("Q"))

    def spill() -> None:
        for session, values in buffers.items():
            path = files.setdefault(session, work / f"{len(files)}.u64")
            with open(path, "ab") as fh:
                values.tofile(fh)
        buffers.clear()

    total, last = 0, None
    async for offset, text, _ in read_topic(client, settings.iggy_stream, topic):
        buffers[session_of(text)].append(digest(text))
        total, last = total + 1, offset
        if total % 500_000 == 0:
            spill()
            log(f"  {topic}: read {total}")
    spill()

    missing: dict[str, collections.Counter] = {}
    for session, path in sorted(files.items()):
        want = array("Q")
        with open(path, "rb") as fh:
            want.frombytes(fh.read())
        path.unlink()
        # the archive may hold more (rewritten after a restart, or expired here already)
        lacking = collections.Counter(want) - archived_digests(s3, settings.s3_bucket, settings.key_prefix, topic, session)
        if lacking:
            missing[session] = lacking
            log(f"  {topic} session {session}: {sum(lacking.values())} of {len(want)} not archived")
    count = sum(sum(c.values()) for c in missing.values())
    log(f"{topic}: {total} messages, {len(files)} sessions, {count} not archived in {len(missing)} sessions")

    written = 0
    dates: collections.Counter = collections.Counter()
    if missing:
        wanted = {session: collections.Counter(c) for session, c in missing.items()}
        states: dict = {}
        async for offset, text, headers in read_topic(client, settings.iggy_stream, topic):
            if offset > last:
                break
            session = session_of(text)
            left = wanted.get(session)
            if not left or left[(key := digest(text))] <= 0:
                continue
            left[key] -= 1
            record = normalize_record(topic=topic, message=IggyMessage(text.encode("utf-8"), headers, offset),
                                      archived_at=datetime.now(UTC))
            dates[record["event_date"]] += 1
            if backfill:
                buffer_key = build_buffer_key(record)
                state = states.setdefault(buffer_key, BufferState(started_at=datetime.now(UTC)))
                state.records.append(record)
                if len(state.records) >= 5000:
                    flush_buffer(settings, s3, buffer_key, states.pop(buffer_key))
                    written += 5000
        for buffer_key, state in states.items():
            flush_buffer(settings, s3, buffer_key, state)
            written += len(state.records)
        log(f"{topic}: missing by date {dict(sorted(dates.items()))}")
        if backfill:
            log(f"{topic}: backfilled {written} records")
    return {"messages": total, "missing": count, "written": written, "dates": dict(dates)}


async def run(settings, topics: list[str], backfill: bool, client=None, s3=None, log=print) -> int:
    if client is None:
        client = IggyClient.from_connection_string(settings.iggy_connection_string)
        await client.connect()
    s3 = s3 or build_s3_client(settings)
    short = 0
    with tempfile.TemporaryDirectory(prefix="verify-") as work:
        for topic in topics:
            details = await client.get_topic(settings.iggy_stream, topic)
            if details is None or not details.messages_count:
                log(f"{topic}: empty")
                continue
            result = await verify_topic(client, s3, settings, topic, Path(work), backfill, log)
            short += result["missing"] - result["written"]
    log("every retained record is archived" if not short else f"{short} retained records are NOT archived")
    return 1 if short else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--topics", help="comma separated; default the writer's topics")
    ap.add_argument("--backfill", action="store_true", help="write the missing records to the archive")
    a = ap.parse_args(argv)
    sys.stdout.reconfigure(line_buffering=True)
    settings = ArchiveWriterSettings.from_env()
    topics = [t for t in a.topics.split(",") if t] if a.topics else list(settings.topics)
    return asyncio.run(run(settings, topics, a.backfill))


if __name__ == "__main__":
    raise SystemExit(main())
