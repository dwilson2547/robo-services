from __future__ import annotations

from dataclasses import dataclass, field
from fnmatch import fnmatch

from ..diagnostics import emit_drop
from ..interfaces import DiagnosticSink
from ..models import DropReason, SignalEvent


@dataclass(frozen=True, slots=True)
class ValidationConfig:
    ranges: dict[str, tuple[float, float]] = field(default_factory=dict)
    max_deltas: dict[str, float] = field(default_factory=dict)
    filter_patterns: tuple[str, ...] = ()


class ValidationEngine:
    def __init__(
        self,
        config: ValidationConfig,
        diagnostic_sink: DiagnosticSink,
        *,
        sample_real_traffic=None,
    ) -> None:
        self._config = config
        self._diagnostic_sink = diagnostic_sink
        self._previous_values: dict[str, float] = {}
        self._sample_real_traffic = sample_real_traffic

    def validate(self, events: list[SignalEvent]) -> list[SignalEvent]:
        validated: list[SignalEvent] = []
        for event in events:
            if self._is_filtered(event):
                emit_drop(
                    event,
                    DropReason.FILTER_REJECTED,
                    hop_name="validate",
                    diagnostic_sink=self._diagnostic_sink,
                    detail="signal matched an exclusion rule",
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            if self._is_out_of_range(event):
                lower, upper = self._config.ranges[event.signal_name]
                emit_drop(
                    event,
                    DropReason.RANGE_EXCEEDED,
                    hop_name="validate",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=f"value {event.value} outside [{lower}, {upper}]",
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            if self._is_rate_implausible(event):
                threshold = self._config.max_deltas[event.signal_name]
                previous = self._previous_values[event.signal_name]
                emit_drop(
                    event,
                    DropReason.RATE_IMPLAUSIBLE,
                    hop_name="validate",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=f"delta {abs(event.value - previous)} exceeded {threshold}",
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            self._previous_values[event.signal_name] = event.value
            validated.append(event)
        return validated

    def _is_filtered(self, event: SignalEvent) -> bool:
        return any(fnmatch(event.signal_name, pattern) for pattern in self._config.filter_patterns)

    def _is_out_of_range(self, event: SignalEvent) -> bool:
        bounds = self._config.ranges.get(event.signal_name)
        if bounds is None:
            return False
        lower, upper = bounds
        return not (lower <= event.value <= upper)

    def _is_rate_implausible(self, event: SignalEvent) -> bool:
        threshold = self._config.max_deltas.get(event.signal_name)
        previous = self._previous_values.get(event.signal_name)
        if threshold is None or previous is None:
            return False
        return abs(event.value - previous) > threshold
