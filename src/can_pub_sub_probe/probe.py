from __future__ import annotations

from .models import ProbeContext, RawFrame

PROBE_CAN_ID = 0x7FF
PROBE_MAGIC = 0xCA
PROBE_PAYLOAD_BYTES = 8
_SUPPRESS_EGRESS_MASK = 0x01


def detect_probe(frame: RawFrame) -> ProbeContext | None:
    if frame.can_id != PROBE_CAN_ID:
        return None
    if frame.dlc != PROBE_PAYLOAD_BYTES:
        return None
    if frame.data[0] != PROBE_MAGIC:
        return None
    flags = frame.data[6]
    return ProbeContext(
        tracer_id=frame.data[1:5].hex(),
        flow_id=frame.data[5],
        injected_at=frame.captured_at,
        suppress_egress=bool(flags & _SUPPRESS_EGRESS_MASK),
    )


def build_probe_frame(
    *,
    timestamp_ms: int,
    tracer_fragment: bytes,
    flow_id: int,
    suppress_egress: bool = True,
) -> RawFrame:
    if len(tracer_fragment) != 4:
        raise ValueError("tracer_fragment must be exactly 4 bytes")
    flags = _SUPPRESS_EGRESS_MASK if suppress_egress else 0
    payload = bytes([PROBE_MAGIC]) + tracer_fragment + bytes([flow_id, flags, 0x00])
    return RawFrame(
        timestamp_ms=timestamp_ms,
        can_id=PROBE_CAN_ID,
        dlc=PROBE_PAYLOAD_BYTES,
        data=payload,
    )
