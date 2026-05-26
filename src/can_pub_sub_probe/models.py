from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from enum import Enum


def utc_from_timestamp_ms(timestamp_ms: int) -> datetime:
    return datetime.fromtimestamp(timestamp_ms / 1000, tz=timezone.utc)


@dataclass(frozen=True, slots=True)
class RawFrame:
    timestamp_ms: int
    can_id: int
    dlc: int
    data: bytes
    is_extended: bool = False

    def __post_init__(self) -> None:
        if self.timestamp_ms < 0:
            raise ValueError("timestamp_ms must be non-negative")
        if not 0 <= self.can_id <= 0x1FFFFFFF:
            raise ValueError("can_id must fit in a 29-bit CAN identifier")
        if not 0 <= self.dlc <= 8:
            raise ValueError("dlc must be between 0 and 8")
        if len(self.data) > 8:
            raise ValueError("data must be at most 8 bytes for classic CAN")
        if len(self.data) < self.dlc:
            raise ValueError("data length cannot be shorter than dlc")
        object.__setattr__(self, "data", bytes(self.data[: self.dlc]))

    @property
    def padded_data(self) -> bytes:
        return self.data.ljust(8, b"\x00")

    @property
    def captured_at(self) -> datetime:
        return utc_from_timestamp_ms(self.timestamp_ms)


@dataclass(frozen=True, slots=True)
class ProbeContext:
    tracer_id: str
    flow_id: int
    injected_at: datetime
    suppress_egress: bool = True


@dataclass(frozen=True, slots=True)
class SignalEvent:
    signal_name: str
    value: float
    unit: str
    raw_frame_id: int
    captured_at: datetime
    processed_at: datetime
    source_session: str
    probe: ProbeContext | None = None


class DropReason(str, Enum):
    UNKNOWN_CAN_ID = "UNKNOWN_CAN_ID"
    DBC_DECODE_FAILED = "DBC_DECODE_FAILED"
    FRAME_INVALID = "FRAME_INVALID"
    RANGE_EXCEEDED = "RANGE_EXCEEDED"
    RATE_IMPLAUSIBLE = "RATE_IMPLAUSIBLE"
    FILTER_REJECTED = "FILTER_REJECTED"
    NO_ROUTE = "NO_ROUTE"
    ROUTING_TABLE_VERSION_MISMATCH = "ROUTING_TABLE_VERSION_MISMATCH"
    WINDOW_OVERFLOW = "WINDOW_OVERFLOW"
    INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES"
    SILENT_DROP = "SILENT_DROP"


@dataclass(frozen=True, slots=True)
class DropEvent:
    tracer_id: str | None
    hop: str
    outcome: str
    reason_code: DropReason
    reason_detail: str
    signal: str
    timestamp: datetime


@dataclass(frozen=True, slots=True)
class DecodedSignal:
    name: str
    value: float
    unit: str


class AggregationKind(str, Enum):
    WINDOWED = "WINDOWED"
    PROBE_PASSTHROUGH = "PROBE_PASSTHROUGH"
    DERIVED = "DERIVED"


@dataclass(frozen=True, slots=True)
class AggregatedSignal:
    signal_name: str
    topic: str
    unit: str
    window_start: datetime
    window_end: datetime
    sample_count: int
    min_value: float
    max_value: float
    mean_value: float
    stddev_value: float
    source_session: str
    kind: AggregationKind
    probe: ProbeContext | None = None
    derived_from: tuple[str, ...] = ()
