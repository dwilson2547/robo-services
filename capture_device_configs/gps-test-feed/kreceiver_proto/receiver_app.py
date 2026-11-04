from __future__ import annotations

import argparse
import json
import socket
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import asdict, replace
from datetime import UTC, datetime
from typing import Any

import msgpack
import paho.mqtt.client as mqtt
from google.protobuf.message import DecodeError

from can_pub_sub_probe import observability as obs

from .config import ReceiverSettings
from .models import IngestDiagnostic, NormalizedIngressMessage, utcnow_iso
from .telemetry_pb2 import TelemetryFrame

# Last registry heartbeat per (device, account), monotonic seconds; repeated every
# HEARTBEAT_EVERY_S so last_seen_at stays fresh and an account the registry learns
# about later (first app sign-in) still gets its device linked.
_last_heartbeat: dict[tuple[str, str | None], float] = {}
HEARTBEAT_EVERY_S = 600.0


class PacketError(ValueError):
    """Raised when an incoming datagram cannot be normalized."""


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="kreceiver-proto",
        description="Prototype receiver that normalizes device telemetry and publishes to Iggy.",
    )
    parser.add_argument("--bind-host", default=None)
    parser.add_argument("--bind-port", type=int, default=None)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--transport",
        choices=["udp", "mqtt", "both"],
        default=None,
        help="Ingest transport(s) to run (default: from KRECEIVER_TRANSPORT or 'udp').",
    )
    parser.add_argument(
        "--max-packets",
        type=int,
        default=None,
        help="Exit after processing this many datagrams (UDP only).",
    )
    parser.add_argument(
        "--print-payloads",
        action="store_true",
        help="Print normalized payloads in addition to publish summaries.",
    )
    return parser


def resolve_settings(args: argparse.Namespace) -> ReceiverSettings:
    defaults = ReceiverSettings()
    return ReceiverSettings(
        bind_host=args.bind_host or defaults.bind_host,
        bind_port=args.bind_port or defaults.bind_port,
        iggy_connection_string=defaults.iggy_connection_string,
        iggy_stream=defaults.iggy_stream,
        diagnostics_topic=defaults.diagnostics_topic,
        gps_topic=defaults.gps_topic,
        imu_topic=defaults.imu_topic,
        rtk_topic=defaults.rtk_topic,
        can_topic=defaults.can_topic,
        session_topic=defaults.session_topic,
        transport=args.transport or defaults.transport,
        mqtt_host=defaults.mqtt_host,
        mqtt_port=defaults.mqtt_port,
        mqtt_topic=defaults.mqtt_topic,
        registry_url=defaults.registry_url,
    )


def infer_source_type(source: str) -> str:
    lowered = source.lower()
    # checked first: session events must never reach a sensor topic (the lap and
    # track jobs read telemetry.raw.gps, which is also the fallback below)
    if "session" in lowered:
        return "session"
    if "imu" in lowered or "mpu" in lowered:
        return "imu"
    if "rtk" in lowered or "ntrip" in lowered or "base" in lowered:
        return "rtk"
    if "can" in lowered:
        return "can"
    if "gps" in lowered or "gnss" in lowered:
        return "gps"
    return "gps"


def topic_for_source_type(source_type: str, settings: ReceiverSettings) -> str:
    if source_type == "imu":
        return settings.imu_topic
    if source_type == "rtk":
        return settings.rtk_topic
    if source_type == "can":
        return settings.can_topic
    if source_type == "session":
        return settings.session_topic
    return settings.gps_topic


_MSGPACK_TP_TO_SOURCE_TYPE: dict[str, str] = {
    "imu": "imu",
    "gps": "gps",
    "can": "can",
}

_GRAVITY_M_S2 = 9.80665


def _normalize_msgpack(
    packet: dict[str, Any],
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> NormalizedIngressMessage:
    device_id = packet.get("did")
    if not isinstance(device_id, str) or not device_id.strip():
        raise PacketError("did must be a non-empty string")

    sid = packet.get("sid")
    source_session = str(sid) if sid is not None else ""
    if not source_session:
        raise PacketError("sid must be present and non-empty")

    tp = packet.get("tp")
    source_type = _MSGPACK_TP_TO_SOURCE_TYPE.get(tp)  # type: ignore[arg-type]
    if source_type is None:
        raise PacketError(f"unknown tp value: {tp!r}")

    topic = topic_for_source_type(source_type, settings)
    received_at_value = received_at or utcnow_iso()

    return NormalizedIngressMessage(
        source_type=source_type,
        topic=topic,
        device_id=device_id,
        source=tp,
        source_session=source_session,
        message_type="telemetry",
        captured_at=None,  # t field is millis-since-boot, not wall clock
        received_at=received_at_value,
        session_id=f"{source_session}:{device_id}",
        sender_ip=sender[0],
        sender_port=sender[1],
        payload=packet,
    )


def _normalize_json(
    raw_payload: bytes,
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> NormalizedIngressMessage:
    try:
        decoded = raw_payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise PacketError("payload is not valid UTF-8") from exc

    try:
        packet = json.loads(decoded)
    except json.JSONDecodeError as exc:
        raise PacketError("payload is not valid JSON") from exc

    if not isinstance(packet, dict):
        raise PacketError("payload JSON must be an object")

    source = _required_string(packet, "source")
    device_id = _required_string(packet, "device_id")
    source_session = _required_string(packet, "source_session")
    source_type = infer_source_type(source)
    topic = topic_for_source_type(source_type, settings)

    captured_at = packet.get("captured_at")
    if captured_at is not None and not isinstance(captured_at, str):
        raise PacketError("captured_at must be a string when provided")

    received_at_value = received_at or utcnow_iso()
    return NormalizedIngressMessage(
        source_type=source_type,
        topic=topic,
        device_id=device_id,
        source=source,
        source_session=source_session,
        message_type=str(packet.get("message_type", "telemetry")),
        captured_at=captured_at,
        received_at=received_at_value,
        session_id=f"{source_session}:{device_id}",
        sender_ip=sender[0],
        sender_port=sender[1],
        payload=packet,
    )


def _build_proto_metadata(frame: TelemetryFrame) -> dict[str, Any]:
    # base_timestamp_us names the frame each sample came from, so consumers can
    # regroup a frame's samples (and match them against its receipt)
    payload: dict[str, Any] = {"frame_version": frame.version, "base_timestamp_us": frame.base_timestamp_us}
    if frame.session_id:
        payload["race_logger_session_id"] = frame.session_id
    if frame.lap_id:
        payload["race_logger_lap_id"] = frame.lap_id
    return payload


def _source_session_from_frame(frame: TelemetryFrame) -> str:
    if frame.session_id:
        return str(frame.session_id)
    return "proto-live"


def _iso_from_unix_micros(timestamp_us: int) -> str:
    return datetime.fromtimestamp(timestamp_us / 1_000_000, tz=UTC).isoformat()


def _build_normalized_message(
    *,
    settings: ReceiverSettings,
    sender: tuple[str, int],
    device_id: str,
    source_session: str,
    source_type: str,
    source: str,
    captured_at: str,
    received_at: str,
    payload: dict[str, Any],
    message_type: str = "telemetry",
) -> NormalizedIngressMessage:
    return NormalizedIngressMessage(
        source_type=source_type,
        topic=topic_for_source_type(source_type, settings),
        device_id=device_id,
        source=source,
        source_session=source_session,
        message_type=message_type,
        captured_at=captured_at,
        received_at=received_at,
        session_id=f"{source_session}:{device_id}",
        sender_ip=sender[0],
        sender_port=sender[1],
        payload=payload,
    )


def _normalize_telemetry_frame(
    frame: TelemetryFrame,
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> list[NormalizedIngressMessage]:
    if not frame.device_id.strip():
        raise PacketError("telemetry frame device_id must be a non-empty string")
    if frame.base_timestamp_us <= 0:
        raise PacketError("telemetry frame base_timestamp_us must be present")

    source_session = _source_session_from_frame(frame)
    received_at_value = received_at or utcnow_iso()
    base_payload = _build_proto_metadata(frame)
    normalized_packets: list[NormalizedIngressMessage] = []

    gps = frame.gps
    gps_count = min(gps.count, len(gps.lat_1e7), len(gps.lon_1e7))
    for index in range(gps_count):
        payload = {
            **base_payload,
            "latitude": gps.lat_1e7[index] / 1e7,
            "longitude": gps.lon_1e7[index] / 1e7,
        }
        if index < len(gps.alt_mm):
            payload["altitude_m"] = gps.alt_mm[index] / 1000.0
        if index < len(gps.speed_cms):
            payload["ground_speed_kph"] = gps.speed_cms[index] * 0.036
        if index < len(gps.heading_cdeg):
            payload["heading_deg"] = gps.heading_cdeg[index] / 100.0
        if index < len(gps.fix_type):
            payload["fix_type"] = gps.fix_type[index]
        if index < len(gps.num_sats):
            payload["num_sats"] = gps.num_sats[index]
        if index < len(gps.hdop_x100):
            payload["hdop"] = gps.hdop_x100[index] / 100.0

        # dt_us, when present, is each fix's real offset; the period is only a nominal grid
        if len(gps.dt_us) == gps.count:
            sample_timestamp_us = frame.base_timestamp_us + gps.dt_us[index]
        else:
            sample_timestamp_us = frame.base_timestamp_us + index * gps.sample_period_us
        normalized_packets.append(
            _build_normalized_message(
                settings=settings,
                sender=sender,
                device_id=frame.device_id,
                source_session=source_session,
                source_type="gps",
                source="race-logger-telemetry-proto:gps",
                captured_at=_iso_from_unix_micros(sample_timestamp_us),
                received_at=received_at_value,
                payload=payload,
            )
        )

    imu = frame.imu
    imu_count = min(
        imu.count,
        len(imu.accel_x),
        len(imu.accel_y),
        len(imu.accel_z),
        len(imu.gyro_x),
        len(imu.gyro_y),
        len(imu.gyro_z),
    )
    for index in range(imu_count):
        payload = {
            **base_payload,
            "accel_m_s2": {
                "x": imu.accel_x[index] / 1000.0 * _GRAVITY_M_S2,
                "y": imu.accel_y[index] / 1000.0 * _GRAVITY_M_S2,
                "z": imu.accel_z[index] / 1000.0 * _GRAVITY_M_S2,
            },
            "gyro_deg_s": {
                "x": imu.gyro_x[index] / 100.0,
                "y": imu.gyro_y[index] / 100.0,
                "z": imu.gyro_z[index] / 100.0,
            },
        }

        if imu.quat_period_us > 0:
            quat_index, remainder = divmod(index * imu.sample_period_us, imu.quat_period_us)
            if remainder == 0 and quat_index < min(
                len(imu.quat_w), len(imu.quat_x), len(imu.quat_y), len(imu.quat_z)
            ):
                payload["quaternion"] = {
                    "w": imu.quat_w[quat_index] / 32767.0,
                    "x": imu.quat_x[quat_index] / 32767.0,
                    "y": imu.quat_y[quat_index] / 32767.0,
                    "z": imu.quat_z[quat_index] / 32767.0,
                }

        sample_timestamp_us = frame.base_timestamp_us + index * imu.sample_period_us
        normalized_packets.append(
            _build_normalized_message(
                settings=settings,
                sender=sender,
                device_id=frame.device_id,
                source_session=source_session,
                source_type="imu",
                source="race-logger-telemetry-proto:imu",
                captured_at=_iso_from_unix_micros(sample_timestamp_us),
                received_at=received_at_value,
                payload=payload,
            )
        )

    for message in frame.can.messages:
        payload = {
            **base_payload,
            "can_id": message.can_id,
            "data": list(message.data),
            "data_hex": message.data.hex(),
            "dlc": len(message.data),
            "dt_us": message.dt_us,
        }
        sample_timestamp_us = frame.base_timestamp_us + message.dt_us
        normalized_packets.append(
            _build_normalized_message(
                settings=settings,
                sender=sender,
                device_id=frame.device_id,
                source_session=source_session,
                source_type="can",
                source="race-logger-telemetry-proto:can",
                captured_at=_iso_from_unix_micros(sample_timestamp_us),
                received_at=received_at_value,
                payload=payload,
            )
        )

    if not normalized_packets:
        raise PacketError("telemetry frame did not contain any publishable samples")

    # One receipt per frame of a recording session, so the session tracker can
    # count frames received (against session_end's count) from telemetry.session
    # alone instead of reading every sample topic.
    if frame.session_id:
        normalized_packets.append(
            _build_normalized_message(
                settings=settings,
                sender=sender,
                device_id=frame.device_id,
                source_session=source_session,
                source_type="session",
                source="race-logger-telemetry-proto:session",
                captured_at=_iso_from_unix_micros(frame.base_timestamp_us),
                received_at=received_at_value,
                payload={
                    "base_timestamp_us": frame.base_timestamp_us,
                    "gps": gps_count,
                    "imu": imu_count,
                    "imu_missing": len(imu.missing),
                    "can": len(frame.can.messages),
                },
                message_type="frame",
            )
        )
    return normalized_packets


def _normalize_protobuf(
    raw_payload: bytes,
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> list[NormalizedIngressMessage] | None:
    frame = TelemetryFrame()
    try:
        frame.ParseFromString(raw_payload)
    except DecodeError:
        return None

    if not frame.device_id.strip() or frame.base_timestamp_us <= 0:
        return None
    if frame.gps.count == 0 and frame.imu.count == 0 and len(frame.can.messages) == 0:
        return None
    return _normalize_telemetry_frame(
        frame, sender, settings, received_at=received_at
    )


def normalize_packets(
    raw_payload: bytes,
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> list[NormalizedIngressMessage]:
    try:
        candidate = msgpack.unpackb(raw_payload, raw=False)
        if isinstance(candidate, dict) and {"did", "sid", "tp"} & set(candidate):
            return [_normalize_msgpack(candidate, sender, settings, received_at=received_at)]
    except PacketError:
        raise
    except Exception:
        pass

    protobuf_packets = _normalize_protobuf(
        raw_payload, sender, settings, received_at=received_at
    )
    if protobuf_packets is not None:
        return protobuf_packets

    return [_normalize_json(raw_payload, sender, settings, received_at=received_at)]


def normalize_packet(
    raw_payload: bytes,
    sender: tuple[str, int],
    settings: ReceiverSettings,
    *,
    received_at: str | None = None,
) -> NormalizedIngressMessage:
    normalized_packets = normalize_packets(
        raw_payload, sender, settings, received_at=received_at
    )
    if len(normalized_packets) != 1:
        raise PacketError(
            "payload expanded into multiple messages; use normalize_packets for batched formats"
        )
    return normalized_packets[0]


def _register_device(device_id: str, user_sub: str | None, settings: ReceiverSettings) -> None:
    """Heartbeat to the registry: creates the device, and links it to the account it
    published as (user_sub from the MQTT topic, never from the payload)."""
    key = (device_id, user_sub)
    now = time.monotonic()
    last = _last_heartbeat.get(key)
    if not settings.registry_url or (last is not None and now - last < HEARTBEAT_EVERY_S):
        return
    _last_heartbeat[key] = now
    try:
        body = json.dumps({"device_id": device_id, "user_sub": user_sub}).encode("utf-8")
        req = urllib.request.Request(
            f"{settings.registry_url}/api/devices/heartbeat",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=3) as resp:
            result = json.loads(resp.read())
        action = "created" if result.get("created") else "updated"
        owner = result.get("owner_email") or "no owner"
        conflict = " (published as another account; owner unchanged)" if result.get("owner_conflict") else ""
        print(f"registry: device {device_id} {action}, {owner}{conflict}", flush=True)
    except Exception as exc:
        print(f"[warn] registry heartbeat failed for {device_id}: {exc}", flush=True)


FRAME_US = 1_000_000  # the phone's frames are 1 s long


def frame_lags(packets: list[NormalizedIngressMessage], now: datetime | None = None) -> tuple[float, float] | None:
    """(upload lag, kreceiver lag) in seconds for a published frame, from its receipt; None if it has none.

    Upload lag runs from the end of the frame on the phone's clock to when kreceiver took it from
    MQTT; kreceiver lag from then to now (normalized and published).
    """
    for p in packets:
        if p.source_type == "session" and p.message_type == "frame":
            base = (p.payload or {}).get("base_timestamp_us")
            if base is None:
                return None
            end = datetime.fromtimestamp((int(base) + FRAME_US) / 1_000_000, tz=UTC)
            upload = obs.seconds_since(p.received_at, end)
            done = obs.seconds_since(p.received_at, now)
            if upload is None or done is None:
                return None
            return -upload, done
    return None


def publish_normalized(
    backend: Any,
    normalized: NormalizedIngressMessage,
) -> None:
    headers = {
        "x-device-id": normalized.device_id,
        "x-source-type": normalized.source_type,
        "x-message-type": normalized.message_type,
        "x-session-id": normalized.session_id,
        "x-received-at": normalized.received_at,
    }
    if normalized.user_sub is not None:
        headers["x-user-sub"] = normalized.user_sub
    if normalized.captured_at is not None:
        headers["x-captured-at"] = normalized.captured_at
    backend.publish(
        normalized.topic,
        json.dumps(normalized.to_dict(), separators=(",", ":"), sort_keys=True).encode(
            "utf-8"
        ),
        headers,
    )


def publish_diagnostic(
    backend: Any,
    settings: ReceiverSettings,
    reason: str,
    detail: str,
    sender: tuple[str, int],
    raw_payload: bytes,
) -> None:
    diagnostic = IngestDiagnostic(
        reason=reason,
        detail=detail,
        sender_ip=sender[0],
        sender_port=sender[1],
        received_at=utcnow_iso(),
        raw_payload=raw_payload.decode("utf-8", errors="replace"),
    )
    backend.publish(
        settings.diagnostics_topic,
        json.dumps(diagnostic.to_dict(), separators=(",", ":"), sort_keys=True).encode(
            "utf-8"
        ),
        {"x-diagnostic-reason": reason},
    )


def build_iggy_backend(settings: ReceiverSettings) -> Any:
    from can_pub_sub_probe.iggy_backend import IggyBackendConfig, IggyPubSubBackend

    backend = IggyPubSubBackend(
        IggyBackendConfig(
            connection_string=settings.iggy_connection_string,
            stream=settings.iggy_stream,
        )
    )
    backend.ping()
    return backend


def user_from_topic(topic: str, base: str) -> str | None:
    """The user in a per-user ingest topic (<base>/<sub>), else None.

    Trusted because the broker only lets a user publish to their own topic (mqtt-auth
    ACL on the authenticated listener) and the anonymous listener cannot write there.
    """
    prefix = base.rstrip("/") + "/"
    if not topic.startswith(prefix):
        return None
    user = topic[len(prefix):]
    return user if user and "/" not in user else None


def start_mqtt_receiver_background(
    settings: ReceiverSettings,
    *,
    backend: Any,
    print_payloads: bool = False,
) -> mqtt.Client:
    """Connect to Mosquitto and subscribe in a background thread. Returns the client."""

    def on_connect(client: mqtt.Client, userdata: Any, flags: Any, rc: int) -> None:
        if rc == 0:
            # the shared topic (LAN loggers) and every user's own topic
            topics = [settings.mqtt_topic, settings.mqtt_topic.rstrip("/") + "/+"]
            print(
                f"MQTT connected to {settings.mqtt_host}:{settings.mqtt_port}, "
                f"subscribing to {', '.join(topics)}",
                flush=True,
            )
            client.subscribe([(t, 0) for t in topics])
        else:
            print(f"MQTT connection failed rc={rc}", flush=True)

    def on_message(client: mqtt.Client, userdata: Any, msg: mqtt.MQTTMessage) -> None:
        sender = (settings.mqtt_host, settings.mqtt_port)
        try:
            normalized_packets = normalize_packets(msg.payload, sender, settings)
        except PacketError as exc:
            publish_diagnostic(
                backend,
                settings,
                reason="INVALID_PAYLOAD",
                detail=str(exc),
                sender=sender,
                raw_payload=msg.payload,
            )
            print(
                f"diagnostic published sender={sender[0]}:{sender[1]} detail={exc}",
                flush=True,
            )
        else:
            user_sub = user_from_topic(msg.topic, settings.mqtt_topic)
            if user_sub is not None:
                normalized_packets = [replace(p, user_sub=user_sub) for p in normalized_packets]
            for normalized in normalized_packets:
                publish_normalized(backend, normalized)
            lags = frame_lags(normalized_packets)
            if lags is not None:
                obs.upload_lag.record(lags[0])
                obs.pipeline_lag.record(lags[1], {"stage": "kreceiver"})
            _register_device(normalized_packets[0].device_id, normalized_packets[0].user_sub, settings)
            print(
                f"published count={len(normalized_packets)} device={normalized_packets[0].device_id} "
                f"topics={sorted({packet.topic for packet in normalized_packets})}",
                flush=True,
            )
            if print_payloads:
                for normalized in normalized_packets:
                    print(json.dumps(asdict(normalized), sort_keys=True), flush=True)

    client = mqtt.Client()
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(settings.mqtt_host, settings.mqtt_port, keepalive=60)
    client.loop_start()
    return client


def run_receiver(
    settings: ReceiverSettings,
    *,
    backend: Any,
    print_payloads: bool = False,
    max_packets: int | None = None,
    socket_factory: Callable[[], socket.socket] = lambda: socket.socket(
        socket.AF_INET, socket.SOCK_DGRAM
    ),
) -> int:
    processed = 0
    with socket_factory() as udp_socket:
        udp_socket.bind((settings.bind_host, settings.bind_port))
        print(f"Listening on udp://{settings.bind_host}:{settings.bind_port}")
        while True:
            payload, sender = udp_socket.recvfrom(65535)
            try:
                normalized_packets = normalize_packets(payload, sender, settings)
            except PacketError as exc:
                publish_diagnostic(
                    backend,
                    settings,
                    reason="INVALID_PAYLOAD",
                    detail=str(exc),
                    sender=sender,
                    raw_payload=payload,
                )
                print(
                    f"diagnostic published sender={sender[0]}:{sender[1]} detail={exc}",
                    flush=True,
                )
            else:
                for normalized in normalized_packets:
                    publish_normalized(backend, normalized)
                _register_device(normalized_packets[0].device_id, normalized_packets[0].user_sub, settings)
                print(
                    f"published count={len(normalized_packets)} device={normalized_packets[0].device_id} "
                    f"topics={sorted({packet.topic for packet in normalized_packets})}",
                    flush=True,
                )
                if print_payloads:
                    for normalized in normalized_packets:
                        print(json.dumps(asdict(normalized), sort_keys=True), flush=True)
            processed += 1
            if max_packets is not None and processed >= max_packets:
                return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    settings = resolve_settings(args)
    obs.setup("kreceiver")
    if args.dry_run:
        from can_pub_sub_probe.pubsub import InMemoryPubSubBackend
        backend: Any = InMemoryPubSubBackend()
    else:
        backend = build_iggy_backend(settings)
    transport = settings.transport

    if transport in ("mqtt", "both"):
        start_mqtt_receiver_background(
            settings, backend=backend, print_payloads=args.print_payloads
        )

    if transport in ("udp", "both"):
        return run_receiver(
            settings,
            backend=backend,
            print_payloads=args.print_payloads,
            max_packets=args.max_packets,
        )

    # mqtt-only: MQTT loop runs in background thread; block main thread
    import time
    print("Running in MQTT-only mode", flush=True)
    while True:
        time.sleep(60)


def _required_string(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise PacketError(f"{key} must be a non-empty string")
    return value


if __name__ == "__main__":
    raise SystemExit(main())
