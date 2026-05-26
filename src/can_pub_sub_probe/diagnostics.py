from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .event_codec import encode_drop_event, probe_headers
from .interfaces import PubSubBackend
from .interfaces import DiagnosticSink
from .models import DropEvent, DropReason, ProbeContext, SignalEvent


def utcnow() -> datetime:
    return datetime.now(tz=timezone.utc)


@dataclass(slots=True)
class InMemoryDiagnosticSink(DiagnosticSink):
    events: list[DropEvent] = field(default_factory=list)

    def publish(self, event: DropEvent) -> None:
        self.events.append(event)


@dataclass(slots=True)
class PubSubDiagnosticSink(DiagnosticSink):
    backend: PubSubBackend
    topic: str = "diagnostics.drop_events"

    def publish(self, event: DropEvent) -> None:
        headers = {
            "x-diagnostic-hop": event.hop,
            "x-diagnostic-reason": event.reason_code.value,
            "x-diagnostic-outcome": event.outcome,
        }
        if event.tracer_id is not None:
            headers.update(
                probe_headers(
                    ProbeContext(
                        tracer_id=event.tracer_id,
                        flow_id=0,
                        injected_at=event.timestamp,
                        suppress_egress=True,
                    )
                )
            )
        self.backend.publish(self.topic, encode_drop_event(event), headers)


def emit_drop(
    msg: SignalEvent,
    reason: DropReason,
    *,
    hop_name: str,
    diagnostic_sink: DiagnosticSink,
    detail: str = "",
    sample_real_traffic: Callable[[SignalEvent], bool] | None = None,
) -> DropEvent | None:
    should_emit_real_traffic = sample_real_traffic or (lambda _: False)
    if msg.probe is None and not should_emit_real_traffic(msg):
        return None
    event = DropEvent(
        tracer_id=msg.probe.tracer_id if msg.probe is not None else None,
        hop=hop_name,
        outcome="dropped",
        reason_code=reason,
        reason_detail=detail,
        signal=msg.signal_name,
        timestamp=utcnow(),
    )
    diagnostic_sink.publish(event)
    return event
