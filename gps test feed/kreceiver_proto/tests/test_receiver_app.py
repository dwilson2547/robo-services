from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from can_pub_sub_probe.pubsub import InMemoryPubSubBackend
from kreceiver_proto.config import ReceiverSettings
from kreceiver_proto.receiver_app import (
    PacketError,
    normalize_packet,
    publish_diagnostic,
    publish_normalized,
)


def test_normalize_packet_routes_gps_payload_to_gps_topic() -> None:
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "gps-test-feed",
            "source_session": "esp32-gps-bench",
            "device_id": "gps-01",
            "captured_at": "2026-05-25T22:57:46Z",
        }
    ).encode("utf-8")

    normalized = normalize_packet(
        payload,
        ("192.168.0.113", 40000),
        settings,
        received_at="2026-05-25T22:57:47+00:00",
    )

    assert normalized.source_type == "gps"
    assert normalized.topic == "telemetry.raw.gps"
    assert normalized.session_id == "esp32-gps-bench:gps-01"


def test_normalize_packet_routes_rtk_payload_to_rtk_topic() -> None:
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "rtk-base-roof",
            "source_session": "roof-survey-day-001",
            "device_id": "rtk-base-01",
        }
    ).encode("utf-8")

    normalized = normalize_packet(payload, ("192.168.0.50", 40000), settings)

    assert normalized.source_type == "rtk"
    assert normalized.topic == "telemetry.raw.rtk"


def test_normalize_packet_routes_imu_payload_to_imu_topic() -> None:
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "imu-test-feed",
            "source_session": "esp32-gps-bench",
            "device_id": "imu-01",
            "message_type": "imu",
        }
    ).encode("utf-8")

    normalized = normalize_packet(payload, ("192.168.0.60", 40000), settings)

    assert normalized.source_type == "imu"
    assert normalized.topic == "telemetry.raw.imu"


def test_normalize_packet_rejects_missing_device_id() -> None:
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "gps-test-feed",
            "source_session": "esp32-gps-bench",
        }
    ).encode("utf-8")

    try:
        normalize_packet(payload, ("192.168.0.113", 40000), settings)
    except PacketError as exc:
        assert str(exc) == "device_id must be a non-empty string"
    else:
        raise AssertionError("PacketError not raised")


def test_publish_normalized_uses_backend_abstraction() -> None:
    backend = InMemoryPubSubBackend()
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "can-trip-feed",
            "source_session": "trip-001",
            "device_id": "can-01",
            "message_type": "frame",
        }
    ).encode("utf-8")
    normalized = normalize_packet(payload, ("10.0.0.5", 12345), settings)

    publish_normalized(backend, normalized)

    message = next(backend.subscribe("telemetry.raw.can"))
    decoded = json.loads(message.payload.decode("utf-8"))
    assert decoded["device_id"] == "can-01"
    assert message.headers["x-source-type"] == "can"
    assert message.headers["x-message-type"] == "frame"


def test_publish_diagnostic_routes_to_diagnostics_topic() -> None:
    backend = InMemoryPubSubBackend()
    settings = ReceiverSettings()

    publish_diagnostic(
        backend,
        settings,
        reason="INVALID_PAYLOAD",
        detail="payload is not valid JSON",
        sender=("10.0.0.5", 12345),
        raw_payload=b"not-json",
    )

    message = next(backend.subscribe("telemetry.diagnostics.ingest"))
    decoded = json.loads(message.payload.decode("utf-8"))
    assert decoded["reason"] == "INVALID_PAYLOAD"
    assert decoded["detail"] == "payload is not valid JSON"
