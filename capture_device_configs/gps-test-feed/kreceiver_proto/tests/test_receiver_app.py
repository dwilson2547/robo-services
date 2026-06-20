from __future__ import annotations

import json
import sys
from pathlib import Path

import msgpack

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(__file__).resolve().parents[4] / "src"
for _p in (ROOT, SRC):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

# Load can_pub_sub_probe.pubsub directly to skip __init__.py (which requires apache_iggy).
import importlib.util as _ilu
import types as _types

if "can_pub_sub_probe" not in sys.modules:
    _pkg_stub = _types.ModuleType("can_pub_sub_probe")
    _pkg_stub.__path__ = [str(SRC / "can_pub_sub_probe")]
    _pkg_stub.__package__ = "can_pub_sub_probe"
    sys.modules["can_pub_sub_probe"] = _pkg_stub

_pubsub_spec = _ilu.spec_from_file_location(
    "can_pub_sub_probe.pubsub",
    SRC / "can_pub_sub_probe/pubsub.py",
)
_pubsub_mod = _ilu.module_from_spec(_pubsub_spec)  # type: ignore[arg-type]
sys.modules["can_pub_sub_probe.pubsub"] = _pubsub_mod
_pubsub_spec.loader.exec_module(_pubsub_mod)  # type: ignore[union-attr]
InMemoryPubSubBackend = _pubsub_mod.InMemoryPubSubBackend
from kreceiver_proto.config import ReceiverSettings
from kreceiver_proto.receiver_app import (
    PacketError,
    normalize_packet,
    normalize_packets,
    publish_diagnostic,
    publish_normalized,
)
from kreceiver_proto.telemetry_pb2 import TelemetryFrame


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


# --- MessagePack (race logger) tests ---


def _make_msgpack(tp: str = "gps", **extra: object) -> bytes:
    packet = {"did": "mid-tier-01", "sid": "s001", "tp": tp, **extra}
    return msgpack.packb(packet, use_bin_type=True)


def test_normalize_packet_msgpack_gps_routes_to_gps_topic() -> None:
    settings = ReceiverSettings()
    payload = _make_msgpack("gps", lat=35.1, lon=-80.9, fix=3)

    normalized = normalize_packet(
        payload,
        ("192.168.0.200", 1883),
        settings,
        received_at="2026-06-11T01:00:00+00:00",
    )

    assert normalized.source_type == "gps"
    assert normalized.topic == "telemetry.raw.gps"
    assert normalized.device_id == "mid-tier-01"
    assert normalized.source_session == "s001"
    assert normalized.session_id == "s001:mid-tier-01"
    assert normalized.captured_at is None


def test_normalize_packet_msgpack_imu_routes_to_imu_topic() -> None:
    settings = ReceiverSettings()
    payload = _make_msgpack("imu", ax=0.01, ay=-0.02, az=9.81)

    normalized = normalize_packet(payload, ("192.168.0.200", 1883), settings)

    assert normalized.source_type == "imu"
    assert normalized.topic == "telemetry.raw.imu"


def test_normalize_packet_msgpack_can_routes_to_can_topic() -> None:
    settings = ReceiverSettings()
    payload = _make_msgpack("can", id=0x7DF, data=[2, 1, 0, 0, 0, 0, 0, 0])

    normalized = normalize_packet(payload, ("192.168.0.200", 1883), settings)

    assert normalized.source_type == "can"
    assert normalized.topic == "telemetry.raw.can"


def test_normalize_packet_msgpack_rejects_missing_did() -> None:
    settings = ReceiverSettings()
    payload = msgpack.packb({"sid": "s001", "tp": "gps"}, use_bin_type=True)

    try:
        normalize_packet(payload, ("192.168.0.200", 1883), settings)
    except PacketError as exc:
        assert "did" in str(exc)
    else:
        raise AssertionError("PacketError not raised")


def test_normalize_packet_msgpack_rejects_unknown_tp() -> None:
    settings = ReceiverSettings()
    payload = msgpack.packb({"did": "mid-tier-01", "sid": "s001", "tp": "radar"}, use_bin_type=True)

    try:
        normalize_packet(payload, ("192.168.0.200", 1883), settings)
    except PacketError as exc:
        assert "tp" in str(exc)
    else:
        raise AssertionError("PacketError not raised")


def test_normalize_packet_json_still_works_after_msgpack_added() -> None:
    """JSON senders (SCRAPS-001) must still parse correctly."""
    settings = ReceiverSettings()
    payload = json.dumps(
        {
            "source": "gps-test-feed",
            "source_session": "esp32-gps-bench",
            "device_id": "gps-01",
            "captured_at": "2026-05-25T22:57:46Z",
        }
    ).encode("utf-8")

    normalized = normalize_packet(payload, ("192.168.0.113", 40000), settings)

    assert normalized.source_type == "gps"
    assert normalized.device_id == "gps-01"


def test_normalize_packets_protobuf_expands_frame_into_raw_topics() -> None:
    settings = ReceiverSettings()
    frame = TelemetryFrame(
        version=1,
        device_id="MID-001",
        base_timestamp_us=1_718_948_400_000_000,
        session_id=42,
    )
    frame.gps.count = 2
    frame.gps.sample_period_us = 100_000
    frame.gps.lat_1e7.extend([351234567, 351234890])
    frame.gps.lon_1e7.extend([-801234567, -801234120])
    frame.gps.alt_mm.extend([123450, 123550])
    frame.gps.speed_cms.extend([2500, 2600])
    frame.gps.heading_cdeg.extend([9000, 9150])

    frame.imu.count = 1
    frame.imu.sample_period_us = 20_000
    frame.imu.accel_x.extend([100])
    frame.imu.accel_y.extend([-200])
    frame.imu.accel_z.extend([1000])
    frame.imu.gyro_x.extend([1234])
    frame.imu.gyro_y.extend([-567])
    frame.imu.gyro_z.extend([0])
    frame.imu.quat_period_us = 20_000
    frame.imu.quat_w.extend([32767])
    frame.imu.quat_x.extend([0])
    frame.imu.quat_y.extend([0])
    frame.imu.quat_z.extend([0])

    can_msg = frame.can.messages.add()
    can_msg.dt_us = 33_000
    can_msg.can_id = 0x1E5
    can_msg.data = bytes([1, 2, 3, 4])

    normalized_packets = normalize_packets(
        frame.SerializeToString(),
        ("192.168.0.71", 1883),
        settings,
        received_at="2026-06-20T09:00:00+00:00",
    )

    assert len(normalized_packets) == 4
    assert [packet.source_type for packet in normalized_packets] == ["gps", "gps", "imu", "can"]

    gps_packet = normalized_packets[0]
    assert gps_packet.topic == "telemetry.raw.gps"
    assert gps_packet.source_session == "42"
    assert gps_packet.session_id == "42:MID-001"
    assert gps_packet.payload["latitude"] == 35.1234567
    assert gps_packet.payload["ground_speed_kph"] == 90.0
    assert gps_packet.payload["heading_deg"] == 90.0
    assert gps_packet.captured_at == "2024-06-21T05:40:00+00:00"

    imu_packet = normalized_packets[2]
    assert imu_packet.topic == "telemetry.raw.imu"
    assert round(imu_packet.payload["accel_m_s2"]["x"], 6) == round(0.1 * 9.80665, 6)
    assert imu_packet.payload["quaternion"]["w"] == 1.0

    can_packet = normalized_packets[3]
    assert can_packet.topic == "telemetry.raw.can"
    assert can_packet.payload["can_id"] == 0x1E5
    assert can_packet.payload["data"] == [1, 2, 3, 4]
    assert can_packet.payload["dlc"] == 4


def test_normalize_packet_rejects_batched_protobuf_single_message_api() -> None:
    settings = ReceiverSettings()
    frame = TelemetryFrame(
        version=1,
        device_id="MID-001",
        base_timestamp_us=1_718_948_400_000_000,
    )
    frame.gps.count = 2
    frame.gps.sample_period_us = 100_000
    frame.gps.lat_1e7.extend([351234567, 351234890])
    frame.gps.lon_1e7.extend([-801234567, -801234120])

    try:
        normalize_packet(frame.SerializeToString(), ("192.168.0.71", 1883), settings)
    except PacketError as exc:
        assert "multiple messages" in str(exc)
    else:
        raise AssertionError("PacketError not raised")
