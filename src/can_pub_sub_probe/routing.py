from __future__ import annotations

from dataclasses import dataclass
from fnmatch import fnmatch

from .diagnostics import emit_drop
from .interfaces import DiagnosticSink
from .models import DropReason, SignalEvent


@dataclass(frozen=True, slots=True)
class RouteRule:
    id: str
    signal_patterns: tuple[str, ...]
    topics: tuple[str, ...]

    def matches(self, signal_name: str) -> bool:
        return any(fnmatch(signal_name, pattern) for pattern in self.signal_patterns)


@dataclass(frozen=True, slots=True)
class RoutingTable:
    name: str
    version: int
    rules: tuple[RouteRule, ...]


@dataclass(frozen=True, slots=True)
class RoutedSignal:
    topic: str
    event: SignalEvent
    rule_id: str


class SignalRouter:
    def __init__(
        self,
        routing_table: RoutingTable,
        diagnostic_sink: DiagnosticSink,
        *,
        signal_versions: dict[str, int] | None = None,
        sample_real_traffic=None,
    ) -> None:
        self._routing_table = routing_table
        self._diagnostic_sink = diagnostic_sink
        self._signal_versions = signal_versions or {}
        self._sample_real_traffic = sample_real_traffic

    def route(self, events: list[SignalEvent]) -> list[RoutedSignal]:
        routed: list[RoutedSignal] = []
        for event in events:
            required_version = self._signal_versions.get(event.signal_name, 1)
            if self._routing_table.version < required_version:
                emit_drop(
                    event,
                    DropReason.ROUTING_TABLE_VERSION_MISMATCH,
                    hop_name="router",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=(
                        f"routing table version {self._routing_table.version} "
                        f"predates signal version {required_version}"
                    ),
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            matched_routes = self._matched_routes(event)
            if not matched_routes:
                emit_drop(
                    event,
                    DropReason.NO_ROUTE,
                    hop_name="router",
                    diagnostic_sink=self._diagnostic_sink,
                    detail=f"no routing rule matched {event.signal_name}",
                    sample_real_traffic=self._sample_real_traffic,
                )
                continue
            routed.extend(matched_routes)
        return routed

    def _matched_routes(self, event: SignalEvent) -> list[RoutedSignal]:
        routed: list[RoutedSignal] = []
        for rule in self._routing_table.rules:
            if not rule.matches(event.signal_name):
                continue
            for topic in rule.topics:
                routed.append(RoutedSignal(topic=topic, event=event, rule_id=rule.id))
        return routed


def build_default_routing_table() -> RoutingTable:
    return RoutingTable(
        name="default-routing-v1",
        version=1,
        rules=(
            RouteRule(
                id="route-powertrain-primary",
                signal_patterns=(
                    "Engine*",
                    "Throttle*",
                    "Transmission*",
                    "MassAirFlow",
                    "CoolantTemp",
                    "EngineOilTemp",
                    "IntakeAirTemp",
                ),
                topics=("signals.powertrain",),
            ),
            RouteRule(
                id="route-speed-to-powertrain",
                signal_patterns=("VehicleSpeed",),
                topics=("signals.powertrain",),
            ),
            RouteRule(
                id="route-chassis-primary",
                signal_patterns=("VehicleSpeed", "BrakePedalApplied"),
                topics=("signals.chassis",),
            ),
        ),
    )
