"""A drive's raw records from the Parquet archive, spilled to disk by time slice.

The archive holds each record as kreceiver published it (`payload_json`), under
`topic=<topic>/source_session=<id>/`. A long drive has millions of CAN records, so
they are written to one file per topic and slice, and rebuilt a slice at a time.
"""

from __future__ import annotations

import io
import json
import re
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pyarrow.parquet as pq

KINDS = ("gps", "imu", "can")
SLICE_US = 300 * 1_000_000
_BASE = re.compile(r'"base_timestamp_us"\s*:\s*(\d+)')


def _payloads(data) -> list[str]:
    # partitioning=None: the Hive-style path must not add columns that clash with the file's own
    return pq.read_table(data, columns=["payload_json"], partitioning=None).column("payload_json").to_pylist()


class LocalArchive:
    """<root>/<session>/<kind>.parquet: a copy of a drive, for testing."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)

    def batches(self, session: str, kind: str) -> Iterator[list[str]]:
        path = self.root / session / f"{kind}.parquet"
        if path.exists():
            yield _payloads(path)


class S3Archive:
    def __init__(self, client, bucket: str, prefix: str = "raw-telemetry", workers: int = 8) -> None:
        self.client, self.bucket, self.prefix, self.workers = client, bucket, prefix, workers

    def batches(self, session: str, kind: str) -> Iterator[list[str]]:
        prefix = f"{self.prefix}/topic=telemetry.raw.{kind}/source_session={session}/"
        pages = self.client.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=prefix)
        keys = sorted(o["Key"] for p in pages for o in p.get("Contents", []))

        def get(key: str) -> list[str]:
            return _payloads(io.BytesIO(self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()))

        with ThreadPoolExecutor(self.workers) as pool:
            for i in range(0, len(keys), 64):  # bounded: not the whole drive in memory
                yield from pool.map(get, keys[i:i + 64])


def spill(archive, session: str, work: Path) -> tuple[list[int], int]:
    """Write the drive's records to <work>/<slice>.<kind>.jsonl.

    Returns the slices in time order and the number of records with no frame tag.
    """
    slices: set[int] = set()
    untagged = 0
    for kind in KINDS:
        for batch in archive.batches(session, kind):
            by_slice: dict[int, list[str]] = {}
            for payload in batch:
                found = _BASE.search(payload)
                if not found:
                    untagged += 1
                    continue
                by_slice.setdefault(int(found[1]) // SLICE_US, []).append(payload.replace("\n", " "))
            for number, lines in by_slice.items():
                slices.add(number)
                with open(work / f"{number}.{kind}.jsonl", "a", encoding="utf-8") as fh:
                    fh.write("\n".join(lines) + "\n")
    return sorted(slices), untagged


def read_slice(work: Path, number: int, kind: str) -> list[dict]:
    path = work / f"{number}.{kind}.jsonl"
    if not path.exists():
        return []
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]
