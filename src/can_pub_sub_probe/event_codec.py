from __future__ import annotations

import json
from datetime import datetime

from .models import DropEvent, DropReason, ProbeContext, SignalEvent

PROBE_HEADER_PREFIX = "x-probe"


def encode_signal_event(event: SignalEvent) -> bytes:
    payload = {
        "signal_name": event.signal_name,
        "value": event.value,
        "unit": event.unit,
        "raw_frame_id": event.raw_frame_id,
        "captured_at": event.captured_at.isoformat(),
        "processed_at": event.processed_at.isoformat(),
        "source_session": event.source_session,
        "probe": _encode_probe(event.probe),
    }
    return json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")


def decode_signal_event(payload: bytes, headers: dict[str, str] | None = None) -> SignalEvent:
    data = json.loads(payload.decode("utf-8"))
    probe = _decode_probe(data["probe"])
    if probe is None and headers is not None:
        probe = probe_from_headers(headers)
    return SignalEvent(
        signal_name=data["signal_name"],
        value=float(data["value"]),
        unit=data["unit"],
        raw_frame_id=int(data["raw_frame_id"]),
        captured_at=datetime.fromisoformat(data["captured_at"]),
        processed_at=datetime.fromisoformat(data["processed_at"]),
        source_session=data["source_session"],
        probe=probe,
    )


def encode_drop_event(event: DropEvent) -> bytes:
    payload = {
        "tracer_id": event.tracer_id,
        "hop": event.hop,
        "outcome": event.outcome,
        "reason_code": event.reason_code.value,
        "reason_detail": event.reason_detail,
        "signal": event.signal,
        "timestamp": event.timestamp.isoformat(),
    }
    return json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")


def decode_drop_event(payload: bytes) -> DropEvent:
    data = json.loads(payload.decode("utf-8"))
    return DropEvent(
        tracer_id=data["tracer_id"],
        hop=data["hop"],
        outcome=data["outcome"],
        reason_code=DropReason(data["reason_code"]),
        reason_detail=data["reason_detail"],
        signal=data["signal"],
        timestamp=datetime.fromisoformat(data["timestamp"]),
    )


def probe_headers(probe: ProbeContext | None) -> dict[str, str]:
    if probe is None:
        return {}
    return {
        f"{PROBE_HEADER_PREFIX}": "true",
        f"{PROBE_HEADER_PREFIX}-id": probe.tracer_id,
        f"{PROBE_HEADER_PREFIX}-flow": str(probe.flow_id),
        f"{PROBE_HEADER_PREFIX}-injected-at": probe.injected_at.isoformat(),
        f"{PROBE_HEADER_PREFIX}-suppress-egress": str(probe.suppress_egress).lower(),
    }


def probe_from_headers(headers: dict[str, str]) -> ProbeContext | None:
    if headers.get(PROBE_HEADER_PREFIX) != "true":
        return None
    return ProbeContext(
        tracer_id=headers[f"{PROBE_HEADER_PREFIX}-id"],
        flow_id=int(headers[f"{PROBE_HEADER_PREFIX}-flow"]),
        injected_at=datetime.fromisoformat(headers[f"{PROBE_HEADER_PREFIX}-injected-at"]),
        suppress_egress=headers.get(f"{PROBE_HEADER_PREFIX}-suppress-egress", "true") == "true",
    )


def _encode_probe(probe: ProbeContext | None) -> dict[str, object] | None:
    if probe is None:
        return None
    return {
        "tracer_id": probe.tracer_id,
        "flow_id": probe.flow_id,
        "injected_at": probe.injected_at.isoformat(),
        "suppress_egress": probe.suppress_egress,
    }


def _decode_probe(data: dict[str, object] | None) -> ProbeContext | None:
    if data is None:
        return None
    return ProbeContext(
        tracer_id=str(data["tracer_id"]),
        flow_id=int(data["flow_id"]),
        injected_at=datetime.fromisoformat(str(data["injected_at"])),
        suppress_egress=bool(data["suppress_egress"]),
    )
