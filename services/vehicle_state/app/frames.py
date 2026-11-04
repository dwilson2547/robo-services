"""Regroup a frame's samples from the sample topics and decide when it can be written.

kreceiver tags every sample with its frame (`payload.base_timestamp_us`) and
publishes a receipt per frame with the counts it sent. A frame is

- complete: the receipt is here and every count matches it (CAN = decoded + unknown
  + discovery, the last for a vehicle with no DBC);
- incomplete: a count is short but can no longer change, because that topic has
  moved past the frame (a later frame of the same session, or anything
  EVENT_LAG_US later in event time on any device), or has had nothing to process
  for `grace_s` (it is caught up);
- empty: nothing but a receipt, under the same rules (e.g. frames from before
  samples were tagged): dropped, never written.

Frames of a session close in time order (carry-forward state needs it). Offsets
are committed per topic only below the oldest open frame's first message there,
so a restart replays every frame that was not written yet.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from .snapshot import CanSample, FrameData, GpsFix, ImuSample

SAMPLE_KINDS = ("gps", "imu", "can", "unknown", "discovery")
KINDS = SAMPLE_KINDS + ("session",)
EVENT_LAG_US = 30_000_000

Key = tuple[str, str]  # (source_session, device_id)


def _t_us(captured_at: str) -> int:
    dt = datetime.fromisoformat(captured_at)
    return int(dt.timestamp()) * 1_000_000 + dt.microsecond


@dataclass
class OpenFrame:
    data: FrameData
    opened_at: float
    last_progress: float
    receipt: dict[str, Any] | None = None
    counts: Counter = field(default_factory=Counter)
    first_offset: dict[str, int] = field(default_factory=dict)
    received_at: str | None = None  # the latest kreceiver receive time among its records

    @property
    def has_samples(self) -> bool:
        return any(self.counts[k] for k in SAMPLE_KINDS)


class FrameTable:
    def __init__(self, grace_s: float = 10.0) -> None:
        self.grace_s = grace_s
        self.frames: dict[Key, dict[int, OpenFrame]] = {}
        self.closed_upto: dict[Key, int] = {}
        self.hwm: dict[str, dict[Key, int]] = {k: {} for k in KINDS}
        self.hwm_any: dict[str, int] = {k: -1 for k in KINDS}
        self.last_offset: dict[str, int | None] = {k: None for k in KINDS}
        self.last_message_at: dict[str, float] = {k: 0.0 for k in KINDS}
        self.skipped: Counter = Counter()

    # --- ingest -----------------------------------------------------------

    def ingest(self, kind: str, offset: int, record: dict[str, Any], now: float) -> None:
        self.last_offset[kind] = offset
        self.last_message_at[kind] = now
        session, device = str(record.get("source_session") or ""), str(record.get("device_id") or "")
        if not session or session == "proto-live" or not device:
            self.skipped["no session"] += 1
            return
        payload = record.get("payload") or {}
        if kind == "session" and record.get("message_type") != "frame":
            return  # session_start / session_end: not a frame
        base = payload.get("base_timestamp_us")
        if base is None:
            self.skipped["untagged"] += 1
            return
        base = int(base)
        key = (session, device)
        if base <= self.closed_upto.get(key, -1):
            self.skipped["late"] += 1
            return
        frame = self.frames.setdefault(key, {}).get(base)
        if frame is None:
            frame = OpenFrame(FrameData(session, device, base), opened_at=now, last_progress=now)
            self.frames[key][base] = frame
        frame.first_offset.setdefault(kind, offset)
        frame.last_progress = now
        received = record.get("received_at")
        if isinstance(received, str) and (frame.received_at is None or _t_us(received) > _t_us(frame.received_at)):
            frame.received_at = received
        self.hwm[kind][key] = max(self.hwm[kind].get(key, -1), base)
        self.hwm_any[kind] = max(self.hwm_any[kind], base)

        data = frame.data
        if kind == "session":
            frame.receipt = payload
            data.imu_filled = payload.get("imu_missing")
            return
        frame.counts[kind] += 1
        if kind == "gps":
            data.gps.append(GpsFix(_t_us(record["captured_at"]), payload))
        elif kind == "imu":
            data.imu.append(ImuSample(_t_us(record["captured_at"]), payload["accel_m_s2"], payload["gyro_deg_s"]))
        elif kind == "can":
            data.can.append(CanSample(base + int(payload.get("dt_us") or 0), payload.get("signals") or {}))
            data.dbc = payload.get("dbc", data.dbc)
        elif kind == "unknown":
            data.dbc = payload.get("dbc", data.dbc)

    # --- closing ----------------------------------------------------------

    def _settled(self, kind: str, key: Key, base: int, now: float) -> bool:
        return (
            self.hwm[kind].get(key, -1) > base
            or self.hwm_any[kind] > base + EVENT_LAG_US
            or now - self.last_message_at[kind] >= self.grace_s
        )

    def status(self, key: Key, base: int, frame: OpenFrame, now: float) -> str | None:
        """complete | incomplete | empty, or None while it may still grow."""
        receipt = frame.receipt
        if receipt is None:
            if not self._settled("session", key, base, now):
                return None
            short = [k for k in SAMPLE_KINDS if not self._settled(k, key, base, now)]
            if short:
                return None
            return "incomplete" if frame.has_samples else "empty"
        mismatched = []
        if frame.counts["gps"] != receipt.get("gps", 0):
            mismatched.append("gps")
        if frame.counts["imu"] != receipt.get("imu", 0):
            mismatched.append("imu")
        if frame.counts["can"] + frame.counts["unknown"] + frame.counts["discovery"] != receipt.get("can", 0):
            mismatched += ["can", "unknown", "discovery"]
        if not mismatched:
            return "complete"
        if all(self._settled(k, key, base, now) for k in mismatched):
            return "incomplete" if frame.has_samples else "empty"
        return None

    def ready(self, now: float) -> dict[Key, list[tuple[int, OpenFrame, str]]]:
        """Per session, the frames that can close now, oldest first (stops at the first that can't)."""
        out: dict[Key, list[tuple[int, OpenFrame, str]]] = {}
        for key, frames in self.frames.items():
            for base in sorted(frames):
                st = self.status(key, base, frames[base], now)
                if st is None:
                    break
                out.setdefault(key, []).append((base, frames[base], st))
        return out

    def close(self, key: Key, base: int) -> None:
        frames = self.frames.get(key, {})
        frames.pop(base, None)
        if not frames:
            self.frames.pop(key, None)
        self.closed_upto[key] = max(self.closed_upto.get(key, -1), base)

    # --- offsets ----------------------------------------------------------

    def commit_offsets(self) -> dict[str, int | None]:
        """Per topic, the last offset that is safe to store (None: nothing to store)."""
        out: dict[str, int | None] = {}
        for kind in KINDS:
            firsts = [f.first_offset[kind] for fs in self.frames.values() for f in fs.values() if kind in f.first_offset]
            if firsts:
                out[kind] = min(firsts) - 1 if min(firsts) > 0 else None
            else:
                out[kind] = self.last_offset[kind]
        return out

    def open_count(self) -> int:
        return sum(len(fs) for fs in self.frames.values())
