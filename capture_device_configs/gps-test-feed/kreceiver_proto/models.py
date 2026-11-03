from __future__ import annotations

from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import Any


@dataclass(frozen=True, slots=True)
class NormalizedIngressMessage:
    source_type: str
    topic: str
    device_id: str
    source: str
    source_session: str
    message_type: str
    captured_at: str | None
    received_at: str
    session_id: str
    sender_ip: str
    sender_port: int
    payload: dict[str, Any]
    # authentik sub of the publisher, from the per-user MQTT topic the broker
    # checked (telemetry/ingest/<sub>); None for the shared topic and for UDP
    user_sub: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class IngestDiagnostic:
    reason: str
    detail: str
    sender_ip: str
    sender_port: int
    received_at: str
    raw_payload: str

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def utcnow_iso() -> str:
    return datetime.now(tz=UTC).isoformat()

