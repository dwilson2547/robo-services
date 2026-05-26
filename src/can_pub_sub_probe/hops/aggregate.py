from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from math import floor
from statistics import pstdev
from typing import Callable

from ..diagnostics import emit_drop
from ..interfaces import DiagnosticSink
from ..models import AggregatedSignal, AggregationKind, DropReason, SignalEvent
from ..routing import RoutedSignal


@dataclass(frozen=True, slots=True)
class DerivedSignalDefinition:
    name: str
    topic: str
    input_signals: tuple[str, ...]
    unit: str
    compute: Callable[[dict[str, AggregatedSignal]], float]


@dataclass(frozen=True, slots=True)
class AggregationConfig:
    default_window_seconds: int = 5
    signal_window_seconds: dict[str, int] = field(default_factory=dict)
    min_samples: dict[str, int] = field(default_factory=dict)
    max_samples: dict[str, int] = field(default_factory=dict)
    derived_signals: tuple[DerivedSignalDefinition, ...] = field(
        default_factory=lambda: (
            DerivedSignalDefinition(
                name="PowertrainLoadIndex",
                topic="signals.powertrain",
                input_signals=("EngineRPM", "VehicleSpeed"),
                unit="rpm*mph",
                compute=lambda signals: (
                    signals["EngineRPM"].mean_value * signals["VehicleSpeed"].mean_value
                ),
            ),
        )
    )


@dataclass(slots=True)
class _WindowBucket:
    topic: str
    signal_name: str
    unit: str
    window_start: datetime
    representative_event: SignalEvent
    values: list[float] = field(default_factory=list)
    overflowed: bool = False


class AggregationEngine:
    def __init__(
        self,
        config: AggregationConfig,
        diagnostic_sink: DiagnosticSink,
        *,
        sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
    ) -> None:
        self._config = config
        self._diagnostic_sink = diagnostic_sink
        self._sample_real_traffic = sample_real_traffic

    def aggregate(self, routed_events: list[RoutedSignal]) -> list[AggregatedSignal]:
        passthrough_events: list[AggregatedSignal] = []
        buckets: dict[tuple[str, str, datetime], _WindowBucket] = {}
        for routed_signal in sorted(routed_events, key=self._sort_key):
            event = routed_signal.event
            if event.probe is not None:
                passthrough_events.append(self._probe_passthrough(routed_signal))
                continue
            window_start = self._window_start(event, routed_signal.topic)
            bucket_key = (routed_signal.topic, event.signal_name, window_start)
            bucket = buckets.get(bucket_key)
            if bucket is None:
                bucket = _WindowBucket(
                    topic=routed_signal.topic,
                    signal_name=event.signal_name,
                    unit=event.unit,
                    window_start=window_start,
                    representative_event=event,
                )
                buckets[bucket_key] = bucket
            bucket.values.append(event.value)
            if len(bucket.values) > self._max_samples(event.signal_name):
                bucket.overflowed = True
        aggregated = passthrough_events + self._finalize_buckets(buckets)
        aggregated.extend(self._derive_signals(aggregated))
        return sorted(aggregated, key=lambda item: (item.window_start, item.topic, item.signal_name))

    def _finalize_buckets(
        self,
        buckets: dict[tuple[str, str, datetime], _WindowBucket],
    ) -> list[AggregatedSignal]:
        aggregated: list[AggregatedSignal] = []
        for bucket in buckets.values():
            event = bucket.representative_event
            if bucket.overflowed:
                emit_drop(
                    event,
                    DropReason.WINDOW_OVERFLOW,
                    hop_name="aggregate",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=(
                        f"window accepted more than {self._max_samples(event.signal_name)} "
                        f"samples for {event.signal_name}"
                    ),
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            if len(bucket.values) < self._min_samples(event.signal_name):
                emit_drop(
                    event,
                    DropReason.INSUFFICIENT_SAMPLES,
                    hop_name="aggregate",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=(
                        f"window closed with {len(bucket.values)} samples; "
                        f"requires {self._min_samples(event.signal_name)}"
                    ),
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            aggregated.append(self._build_windowed_signal(bucket))
        return aggregated

    def _derive_signals(self, aggregated: list[AggregatedSignal]) -> list[AggregatedSignal]:
        grouped: dict[tuple[str, datetime, datetime], dict[str, AggregatedSignal]] = defaultdict(dict)
        for item in aggregated:
            if item.kind != AggregationKind.WINDOWED:
                continue
            grouped[(item.topic, item.window_start, item.window_end)][item.signal_name] = item
        derived: list[AggregatedSignal] = []
        for definition in self._config.derived_signals:
            for (topic, window_start, window_end), signals in grouped.items():
                if topic != definition.topic:
                    continue
                if not all(name in signals for name in definition.input_signals):
                    continue
                source_session = signals[definition.input_signals[0]].source_session
                value = float(definition.compute(signals))
                derived.append(
                    AggregatedSignal(
                        signal_name=definition.name,
                        topic=topic,
                        unit=definition.unit,
                        window_start=window_start,
                        window_end=window_end,
                        sample_count=min(signals[name].sample_count for name in definition.input_signals),
                        min_value=value,
                        max_value=value,
                        mean_value=value,
                        stddev_value=0.0,
                        source_session=source_session,
                        kind=AggregationKind.DERIVED,
                        derived_from=definition.input_signals,
                    )
                )
        return derived

    def _build_windowed_signal(self, bucket: _WindowBucket) -> AggregatedSignal:
        values = bucket.values
        sample_count = len(values)
        window_seconds = self._window_seconds(bucket.signal_name)
        return AggregatedSignal(
            signal_name=bucket.signal_name,
            topic=bucket.topic,
            unit=bucket.unit,
            window_start=bucket.window_start,
            window_end=bucket.window_start + timedelta(seconds=window_seconds),
            sample_count=sample_count,
            min_value=min(values),
            max_value=max(values),
            mean_value=sum(values) / sample_count,
            stddev_value=pstdev(values) if sample_count > 1 else 0.0,
            source_session=bucket.representative_event.source_session,
            kind=AggregationKind.WINDOWED,
        )

    def _probe_passthrough(self, routed_signal: RoutedSignal) -> AggregatedSignal:
        event = routed_signal.event
        return AggregatedSignal(
            signal_name=event.signal_name,
            topic=routed_signal.topic,
            unit=event.unit,
            window_start=event.captured_at,
            window_end=event.captured_at,
            sample_count=1,
            min_value=event.value,
            max_value=event.value,
            mean_value=event.value,
            stddev_value=0.0,
            source_session=event.source_session,
            kind=AggregationKind.PROBE_PASSTHROUGH,
            probe=event.probe,
        )

    def _window_start(self, event: SignalEvent, topic: str) -> datetime:
        seconds = self._window_seconds(event.signal_name)
        unix_seconds = event.captured_at.astimezone(timezone.utc).timestamp()
        bucket_start = floor(unix_seconds / seconds) * seconds
        return datetime.fromtimestamp(bucket_start, tz=timezone.utc)

    def _window_seconds(self, signal_name: str) -> int:
        return self._config.signal_window_seconds.get(
            signal_name,
            self._config.default_window_seconds,
        )

    def _min_samples(self, signal_name: str) -> int:
        return self._config.min_samples.get(signal_name, 1)

    def _max_samples(self, signal_name: str) -> int:
        return self._config.max_samples.get(signal_name, 10_000)

    @staticmethod
    def _sort_key(routed_signal: RoutedSignal) -> tuple[datetime, str, str]:
        return (
            routed_signal.event.captured_at,
            routed_signal.topic,
            routed_signal.event.signal_name,
        )
