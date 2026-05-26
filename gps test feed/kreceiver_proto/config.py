from __future__ import annotations

import os
from dataclasses import dataclass


def _env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    return default if value is None else int(value)


@dataclass(frozen=True, slots=True)
class ReceiverSettings:
    bind_host: str = os.getenv("KRECEIVER_BIND_HOST", "0.0.0.0")
    bind_port: int = _env_int("KRECEIVER_BIND_PORT", 5514)
    iggy_connection_string: str = os.getenv(
        "KRECEIVER_IGGY_CONNECTION_STRING",
        "iggy+tcp://iggy:iggy@127.0.0.1:8090",
    )
    iggy_stream: str = os.getenv("KRECEIVER_IGGY_STREAM", "can-pub-sub-probe")
    diagnostics_topic: str = os.getenv(
        "KRECEIVER_DIAGNOSTICS_TOPIC", "telemetry.diagnostics.ingest"
    )
    gps_topic: str = os.getenv("KRECEIVER_GPS_TOPIC", "telemetry.raw.gps")
    rtk_topic: str = os.getenv("KRECEIVER_RTK_TOPIC", "telemetry.raw.rtk")
    can_topic: str = os.getenv("KRECEIVER_CAN_TOPIC", "telemetry.raw.can")

