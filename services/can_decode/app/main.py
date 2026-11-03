"""can_decode: Iggy telemetry.raw.can -> DBC decode -> telemetry.decoded.can.

The parquet archive writer also reads telemetry.raw.can with the SDK's default
consumer and auto-commit, so this service keeps its own offset in memory and
never commits (see IggyPubSubBackend.subscribe). On start it begins after the
current tail (CAN_DECODE_START=latest) or from the first retained message
(earliest); decoded output is derived data, so a restart gap is replayable.
"""

from __future__ import annotations

import json
import logging
import os
import time

from apache_iggy import PollingStrategy

from can_pub_sub_probe.iggy_backend import IggyBackendConfig, IggyPubSubBackend

from .decode import Decoded, decode_record, load_dbc

LOG = logging.getLogger("can-decode")


def main() -> int:
    logging.basicConfig(level=os.getenv("CAN_DECODE_LOG_LEVEL", "INFO"))
    in_topic = os.getenv("CAN_DECODE_INPUT_TOPIC", "telemetry.raw.can")
    out_topic = os.getenv("CAN_DECODE_OUTPUT_TOPIC", "telemetry.decoded.can")
    unknown_topic = os.getenv("CAN_DECODE_UNKNOWN_TOPIC", "")
    dbc_path = os.environ["DBC_PATH"]
    start = os.getenv("CAN_DECODE_START", "latest").strip().lower()
    idle_sleep = float(os.getenv("CAN_DECODE_IDLE_SLEEP_SECONDS", "0.5"))

    with open(dbc_path, encoding="utf-8") as fh:
        db = load_dbc(fh.read())

    backend = IggyPubSubBackend(
        IggyBackendConfig(
            connection_string=os.environ["IGGY_CONNECTION_STRING"],
            stream=os.getenv("IGGY_STREAM", "can-pub-sub-probe"),
        )
    )
    backend.ping()
    for topic in filter(None, (in_topic, out_topic, unknown_topic)):
        backend.ensure_topic(topic)

    next_offset = _start_offset(backend, in_topic, start)
    LOG.info(
        "%s -> %s (unknown: %s), %d DBC messages from %s, starting at offset %d",
        in_topic, out_topic, unknown_topic or "dropped", len(db), dbc_path, next_offset,
    )

    decoded = unknown = failed = 0
    last_report = time.monotonic()
    while True:
        batch = list(
            backend.subscribe(
                in_topic,
                polling_strategy=PollingStrategy.Offset(next_offset),
                auto_commit=False,
            )
        )
        for message in batch:
            if message.offset is not None:
                next_offset = message.offset + 1
            try:
                result = decode_record(json.loads(message.payload), db)
            except (ValueError, KeyError, TypeError):
                failed += 1
                LOG.warning("unreadable record at offset %s", message.offset, exc_info=True)
                continue
            if isinstance(result, Decoded):
                decoded += 1
                backend.publish(out_topic, _encode(result.record), _headers(message.headers, "can_decoded"))
            else:
                unknown += 1
                if unknown_topic:
                    backend.publish(unknown_topic, _encode(result.record), _headers(message.headers, "can_unknown"))
        if time.monotonic() - last_report >= 60:
            LOG.info("decoded=%d unknown=%d unreadable=%d offset=%d", decoded, unknown, failed, next_offset)
            last_report = time.monotonic()
        if not batch:
            time.sleep(idle_sleep)


def _start_offset(backend: IggyPubSubBackend, topic: str, start: str) -> int:
    if start == "earliest":
        return 0
    if start == "latest":
        latest = backend.latest_offset(topic)
        return 0 if latest is None else latest + 1
    raise ValueError("CAN_DECODE_START must be 'latest' or 'earliest'")


def _encode(record: dict) -> bytes:
    return json.dumps(record, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _headers(headers: dict[str, str], source_type: str) -> dict[str, str]:
    return {**headers, "x-source-type": source_type}


if __name__ == "__main__":
    raise SystemExit(main())
