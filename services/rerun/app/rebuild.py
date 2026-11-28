"""One drive's raw records -> its vehicle_state rows, as the live pipeline would write them.

No network or database here. Frames close under vehicle-state's own rules, with
the registry's per-frame receipts standing in for telemetry.session.
"""

from __future__ import annotations

import math
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from .reuse import Decoded, FrameTable, build_rows, decode_record
from .source import KINDS, SLICE_US, read_slice, spill


@dataclass
class Receipt:
    gps: int
    imu: int
    can: int
    imu_missing: int | None
    received_at: datetime | None


@dataclass
class Result:
    frames: Counter = field(default_factory=Counter)  # complete / incomplete / empty
    records: Counter = field(default_factory=Counter)  # kept, per topic
    duplicates: int = 0
    untagged: int = 0
    decoded: int = 0
    unknown: int = 0
    rows: int = 0
    short: Counter = field(default_factory=Counter)  # per topic: records the receipts count and the archive lacks


def one_delivery(records: list[dict]) -> tuple[list[dict], int]:
    """A frame sent twice (catch-up upload, redelivery) is in the archive twice. Every
    record of one delivery has the same `received_at`: per frame keep the fullest, the
    earliest on a tie. Identical records within a delivery are real and stay."""
    frames: dict[int, dict[str, list[dict]]] = {}
    for record in records:
        base = int(record["payload"]["base_timestamp_us"])
        frames.setdefault(base, {}).setdefault(str(record.get("received_at")), []).append(record)
    kept: list[dict] = []
    for deliveries in frames.values():
        kept += deliveries[min(deliveries, key=lambda at: (-len(deliveries[at]), at))]
    return kept, len(records) - len(kept)


def rebuild(archive, session: str, device: str, receipts: dict[int, Receipt], dbc: tuple[str, dict] | None,
            roles: dict[str, dict], vehicle_id: int | None, user_sub: str | None, work: Path, write,
            interval_us: int = 1_000_000) -> Result:
    """Rebuild the drive, handing each slice's rows to `write`.

    `dbc` is (name, messages) from load_dbc, or None for a vehicle without one.
    """
    result = Result()
    carry: dict = {}
    key = (session, device)
    slices, result.untagged = spill(archive, session, work)
    pending = {base // SLICE_US for base in receipts}
    for number in sorted(set(slices) | pending):
        table = FrameTable()
        offset = 0

        def ingest(kind: str, record: dict) -> None:
            nonlocal offset
            offset += 1
            table.ingest(kind, offset, record, 0.0)

        seen: Counter = Counter()  # (kind, frame base) -> records
        for kind in KINDS:
            records = [r for r in read_slice(work, number, kind)
                       if str(r.get("source_session")) == session and str(r.get("device_id")) == device]
            records, dropped = one_delivery(records)
            result.duplicates += dropped
            result.records[kind] += len(records)
            records.sort(key=lambda r: (r["payload"]["base_timestamp_us"], r["payload"].get("dt_us") or 0))
            for record in records:
                seen[kind, int(record["payload"]["base_timestamp_us"])] += 1
                if kind != "can":
                    ingest(kind, record)
                elif dbc is None:
                    ingest("discovery", record)
                else:
                    out = decode_record(record, dbc[1], dbc[0])
                    if isinstance(out, Decoded):
                        result.decoded += 1
                        ingest("can", out.record)
                    else:
                        result.unknown += 1
                        ingest("unknown", out.record)
        for base, r in receipts.items():
            if base // SLICE_US != number:
                continue
            for kind in KINDS:
                result.short[kind] += max(0, getattr(r, kind) - seen[kind, base])
            ingest("session", {"source_session": session, "device_id": device, "message_type": "frame",
                               "received_at": r.received_at.isoformat() if r.received_at else None,
                               "payload": {"base_timestamp_us": base, "gps": r.gps, "imu": r.imu, "can": r.can,
                                           "imu_missing": r.imu_missing}})
        rows: list[dict[str, Any]] = []
        # nothing more is coming, so every frame is settled
        for base, frame, status in table.ready(math.inf).get(key, []):
            result.frames[status] += 1
            if status == "empty":
                continue
            for row in build_rows(frame.data, interval_us, carry, roles, status == "complete"):
                row["vehicle_id"], row["user_sub"] = vehicle_id, user_sub
                row["received_at"] = frame.received_at
                rows.append(row)
        result.rows += len(rows)
        write(rows)
        for kind in KINDS:
            (work / f"{number}.{kind}.jsonl").unlink(missing_ok=True)
    result.short = +result.short  # only the topics actually short
    return result
