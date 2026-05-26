from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal, Protocol

from .models import AggregatedSignal, ProbeContext, SignalEvent


def utcnow() -> datetime:
    return datetime.now(tz=timezone.utc)


def topic_domain(topic: str) -> str:
    return topic.removeprefix("signals.")


@dataclass(frozen=True, slots=True)
class RawSignalSinkRecord:
    signal_name: str
    source_topic: str
    value: float
    unit: str
    raw_frame_id: int
    captured_at: datetime
    processed_at: datetime
    source_session: str
    probe: ProbeContext | None = None


@dataclass(frozen=True, slots=True)
class AggregateSignalSinkRecord:
    signal_name: str
    measurement: str
    domain: str
    source_topic: str
    unit: str
    window_start: datetime
    window_end: datetime
    sample_count: int
    min_value: float
    max_value: float
    mean_value: float
    stddev_value: float
    source_session: str
    kind: str
    probe: ProbeContext | None = None
    derived_from: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class SinkTerminalEvent:
    tracer_id: str
    sink_name: str
    outcome: Literal["forwarded_to_sink"]
    signal_name: str
    source_topic: str
    record_kind: Literal["raw", "aggregate"]
    occurred_at: datetime
    source_session: str
    egress_suppressed: bool


@dataclass(frozen=True, slots=True)
class SinkWriteSummary:
    raw_records_written: int = 0
    aggregate_records_written: int = 0
    terminal_events_recorded: int = 0


class SinkBackend(Protocol):
    name: str

    def write_raw(self, record: RawSignalSinkRecord) -> None: ...

    def write_aggregate(self, record: AggregateSignalSinkRecord) -> None: ...

    def write_terminal(self, event: SinkTerminalEvent) -> None: ...


@dataclass(slots=True)
class InMemorySinkBackend:
    name: str = "in-memory"
    raw_records: list[RawSignalSinkRecord] = field(default_factory=list)
    aggregate_records: list[AggregateSignalSinkRecord] = field(default_factory=list)
    terminal_events: list[SinkTerminalEvent] = field(default_factory=list)

    def write_raw(self, record: RawSignalSinkRecord) -> None:
        self.raw_records.append(record)

    def write_aggregate(self, record: AggregateSignalSinkRecord) -> None:
        self.aggregate_records.append(record)

    def write_terminal(self, event: SinkTerminalEvent) -> None:
        self.terminal_events.append(event)


@dataclass(frozen=True, slots=True)
class JsonlFileSinkBackend:
    raw_path: Path
    aggregate_path: Path
    terminal_path: Path
    name: str = "jsonl-file"

    def write_raw(self, record: RawSignalSinkRecord) -> None:
        self._append_jsonl(self.raw_path, _to_jsonable_dict(record))

    def write_aggregate(self, record: AggregateSignalSinkRecord) -> None:
        self._append_jsonl(self.aggregate_path, _to_jsonable_dict(record))

    def write_terminal(self, event: SinkTerminalEvent) -> None:
        self._append_jsonl(self.terminal_path, _to_jsonable_dict(event))

    @staticmethod
    def _append_jsonl(path: Path, payload: dict[str, object]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("ab") as handle:
            handle.write(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))
            handle.write(b"\n")


class SinkEngine:
    def __init__(
        self,
        backend: SinkBackend,
        *,
        raw_source_topic: str = "signals.validated",
    ) -> None:
        self._backend = backend
        self._raw_source_topic = raw_source_topic

    def write(
        self,
        *,
        validated_events: list[SignalEvent],
        aggregated_events: list[AggregatedSignal],
    ) -> SinkWriteSummary:
        raw_written = 0
        aggregate_written = 0
        terminal_recorded = 0
        for event in validated_events:
            suppressed = _probe_suppresses_egress(event.probe)
            if suppressed:
                self._backend.write_terminal(
                    _terminal_event(
                        probe=event.probe,
                        sink_name=self._backend.name,
                        signal_name=event.signal_name,
                        source_topic=self._raw_source_topic,
                        record_kind="raw",
                        source_session=event.source_session,
                        egress_suppressed=True,
                    )
                )
                terminal_recorded += 1
                continue
            self._backend.write_raw(_raw_record(event, self._raw_source_topic))
            raw_written += 1
            if event.probe is not None:
                self._backend.write_terminal(
                    _terminal_event(
                        probe=event.probe,
                        sink_name=self._backend.name,
                        signal_name=event.signal_name,
                        source_topic=self._raw_source_topic,
                        record_kind="raw",
                        source_session=event.source_session,
                        egress_suppressed=False,
                    )
                )
                terminal_recorded += 1
        for item in aggregated_events:
            suppressed = _probe_suppresses_egress(item.probe)
            if suppressed:
                self._backend.write_terminal(
                    _terminal_event(
                        probe=item.probe,
                        sink_name=self._backend.name,
                        signal_name=item.signal_name,
                        source_topic=item.topic,
                        record_kind="aggregate",
                        source_session=item.source_session,
                        egress_suppressed=True,
                    )
                )
                terminal_recorded += 1
                continue
            self._backend.write_aggregate(_aggregate_record(item))
            aggregate_written += 1
            if item.probe is not None:
                self._backend.write_terminal(
                    _terminal_event(
                        probe=item.probe,
                        sink_name=self._backend.name,
                        signal_name=item.signal_name,
                        source_topic=item.topic,
                        record_kind="aggregate",
                        source_session=item.source_session,
                        egress_suppressed=False,
                    )
                )
                terminal_recorded += 1
        return SinkWriteSummary(
            raw_records_written=raw_written,
            aggregate_records_written=aggregate_written,
            terminal_events_recorded=terminal_recorded,
        )


def _raw_record(event: SignalEvent, source_topic: str) -> RawSignalSinkRecord:
    return RawSignalSinkRecord(
        signal_name=event.signal_name,
        source_topic=source_topic,
        value=event.value,
        unit=event.unit,
        raw_frame_id=event.raw_frame_id,
        captured_at=event.captured_at,
        processed_at=event.processed_at,
        source_session=event.source_session,
        probe=event.probe,
    )


def _aggregate_record(item: AggregatedSignal) -> AggregateSignalSinkRecord:
    return AggregateSignalSinkRecord(
        signal_name=item.signal_name,
        measurement=item.signal_name,
        domain=topic_domain(item.topic),
        source_topic=item.topic,
        unit=item.unit,
        window_start=item.window_start,
        window_end=item.window_end,
        sample_count=item.sample_count,
        min_value=item.min_value,
        max_value=item.max_value,
        mean_value=item.mean_value,
        stddev_value=item.stddev_value,
        source_session=item.source_session,
        kind=item.kind.value,
        probe=item.probe,
        derived_from=item.derived_from,
    )


def _probe_suppresses_egress(probe: ProbeContext | None) -> bool:
    return probe is not None and probe.suppress_egress


def _terminal_event(
    *,
    probe: ProbeContext | None,
    sink_name: str,
    signal_name: str,
    source_topic: str,
    record_kind: Literal["raw", "aggregate"],
    source_session: str,
    egress_suppressed: bool,
) -> SinkTerminalEvent:
    if probe is None:
        raise ValueError("probe is required when recording a terminal sink event")
    return SinkTerminalEvent(
        tracer_id=probe.tracer_id,
        sink_name=sink_name,
        outcome="forwarded_to_sink",
        signal_name=signal_name,
        source_topic=source_topic,
        record_kind=record_kind,
        occurred_at=utcnow(),
        source_session=source_session,
        egress_suppressed=egress_suppressed,
    )


def _to_jsonable_dict(value: object) -> dict[str, object]:
    data = asdict(value)
    return _jsonable(data)


def _jsonable(value: object) -> object:
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {key: _jsonable(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_jsonable(item) for item in value]
    if isinstance(value, tuple):
        return [_jsonable(item) for item in value]
    return value
