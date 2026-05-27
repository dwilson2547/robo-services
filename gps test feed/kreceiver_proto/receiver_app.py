from __future__ import annotations

import argparse
import json
import socket
import threading
from collections.abc import Callable
from dataclasses import asdict
from typing import Any

import paho.mqtt.client as mqtt

from can_pub_sub_probe.iggy_backend import IggyBackendConfig, IggyPubSubBackend
from can_pub_sub_probe.pubsub import InMemoryPubSubBackend

from .config import ReceiverSettings
from .models import IngestDiagnostic, NormalizedIngressMessage, utcnow_iso


class PacketError(ValueError):
    """Raised when an incoming UDP datagram cannot be normalized."""


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
        transport=args.transport or defaults.transport,
        mqtt_host=defaults.mqtt_host,
        mqtt_port=defaults.mqtt_port,
        mqtt_topic=defaults.mqtt_topic,
    )


def infer_source_type(source: str) -> str:
    lowered = source.lower()
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
    return settings.gps_topic


def normalize_packet(
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
    session_id = f"{source_session}:{device_id}"
    return NormalizedIngressMessage(
        source_type=source_type,
        topic=topic,
        device_id=device_id,
        source=source,
        source_session=source_session,
        message_type=str(packet.get("message_type", "telemetry")),
        captured_at=captured_at,
        received_at=received_at_value,
        session_id=session_id,
        sender_ip=sender[0],
        sender_port=sender[1],
        payload=packet,
    )


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


def build_iggy_backend(settings: ReceiverSettings) -> IggyPubSubBackend:
    backend = IggyPubSubBackend(
        IggyBackendConfig(
            connection_string=settings.iggy_connection_string,
            stream=settings.iggy_stream,
        )
    )
    backend.ping()
    return backend


def start_mqtt_receiver_background(
    settings: ReceiverSettings,
    *,
    backend: Any,
    print_payloads: bool = False,
) -> mqtt.Client:
    """Connect to Mosquitto and subscribe in a background thread. Returns the client."""

    def on_connect(client: mqtt.Client, userdata: Any, flags: Any, rc: int) -> None:
        if rc == 0:
            print(
                f"MQTT connected to {settings.mqtt_host}:{settings.mqtt_port}, "
                f"subscribing to {settings.mqtt_topic}",
                flush=True,
            )
            client.subscribe(settings.mqtt_topic)
        else:
            print(f"MQTT connection failed rc={rc}", flush=True)

    def on_message(client: mqtt.Client, userdata: Any, msg: mqtt.MQTTMessage) -> None:
        sender = (settings.mqtt_host, settings.mqtt_port)
        try:
            normalized = normalize_packet(msg.payload, sender, settings)
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
            publish_normalized(backend, normalized)
            print(
                f"published topic={normalized.topic} device={normalized.device_id} "
                f"source_type={normalized.source_type}",
                flush=True,
            )
            if print_payloads:
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
                normalized = normalize_packet(payload, sender, settings)
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
                publish_normalized(backend, normalized)
                print(
                    f"published topic={normalized.topic} device={normalized.device_id} "
                    f"source_type={normalized.source_type}",
                    flush=True,
                )
                if print_payloads:
                    print(
                        json.dumps(asdict(normalized), sort_keys=True),
                        flush=True,
                    )
            processed += 1
            if max_packets is not None and processed >= max_packets:
                return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    settings = resolve_settings(args)
    backend = InMemoryPubSubBackend() if args.dry_run else build_iggy_backend(settings)
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
