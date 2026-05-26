from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass

from .diagnostics import emit_drop
from .diagnostics import InMemoryDiagnosticSink
from .hops.aggregate import AggregationConfig, AggregationEngine
from .hops.ingest import IngestNormalizer
from .hops.validate import ValidationConfig, ValidationEngine
from .models import AggregatedSignal, SignalEvent
from .profiles import VehicleCanProfile
from .routing import RoutedSignal, RoutingTable, SignalRouter
from .sinks import SinkBackend, SinkEngine, SinkWriteSummary


@dataclass(frozen=True, slots=True)
class LocalPipelineResult:
    validated_events: list[SignalEvent]
    routed_events: list[RoutedSignal]
    aggregated_events: list[AggregatedSignal]
    diagnostics: InMemoryDiagnosticSink
    sink_summary: SinkWriteSummary | None = None


def run_local_pipeline(
    frames: Iterable,
    *,
    profile: VehicleCanProfile,
    source_session: str,
    validation_config: ValidationConfig | None = None,
    validate_sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
    routing_table: RoutingTable | None = None,
    route_sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
    aggregation_config: AggregationConfig | None = None,
    aggregate_sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
    diagnostic_sink: InMemoryDiagnosticSink | None = None,
    sink_backend: SinkBackend | None = None,
    ingest_sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
) -> LocalPipelineResult:
    sink = diagnostic_sink or InMemoryDiagnosticSink()
    validator = ValidationEngine(
        validation_config or ValidationConfig(),
        sink,
        sample_real_traffic=validate_sample_real_traffic,
    )
    normalizer = IngestNormalizer(profile)
    validated_events: list[SignalEvent] = []
    for frame in frames:
        try:
            normalized_events = normalizer.normalize_frame(frame, source_session=source_session)
        except Exception as exc:
            ingest_event = _ingest_drop_event(frame=frame, source_session=source_session)
            emit_drop(
                ingest_event,
                IngestNormalizer.classify_error(exc),
                hop_name="ingest",
                diagnostic_sink=sink,
                detail=str(exc),
                sample_real_traffic=ingest_sample_real_traffic,
            )
            continue
        validated_events.extend(validator.validate(normalized_events))
    routed_events: list[RoutedSignal] = []
    aggregated_events: list[AggregatedSignal] = []
    if routing_table is not None:
        router = SignalRouter(
            routing_table,
            sink,
            signal_versions=profile.signal_versions,
            sample_real_traffic=route_sample_real_traffic,
        )
        routed_events = router.route(validated_events)
    if aggregation_config is not None:
        aggregator = AggregationEngine(
            aggregation_config,
            sink,
            sample_real_traffic=aggregate_sample_real_traffic,
        )
        aggregated_events = aggregator.aggregate(routed_events)
    sink_summary = None
    if sink_backend is not None:
        sink_summary = SinkEngine(sink_backend).write(
            validated_events=validated_events,
            aggregated_events=aggregated_events,
        )
    return LocalPipelineResult(
        validated_events=validated_events,
        routed_events=routed_events,
        aggregated_events=aggregated_events,
        diagnostics=sink,
        sink_summary=sink_summary,
    )


def _ingest_drop_event(*, frame, source_session: str) -> SignalEvent:
    captured_at = frame.captured_at
    return SignalEvent(
        signal_name=f"can_id_0x{frame.can_id:X}",
        value=float(frame.dlc),
        unit="dlc",
        raw_frame_id=frame.can_id,
        captured_at=captured_at,
        processed_at=captured_at,
        source_session=source_session,
    )
