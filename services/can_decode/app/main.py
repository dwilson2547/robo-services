"""can_decode: Iggy telemetry.raw.can -> DBC-decoded -> telemetry.decoded.can.

Skeleton: consume raw CAN messages, decode with cantools against the vehicle
DBC, republish with decoded signals. Unknown ids go to an optional sink topic
for the reverse-engineering side.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import re

import cantools

# PYTHONPATH=/app in the image; the pipeline package is copied to /app/src
from can_pub_sub_probe.iggy_backend import IggyBackendConfig, IggyPubSubBackend  # noqa: E402

LOG = logging.getLogger("can-decode")
logging.basicConfig(level=os.getenv("CAN_DECODE_LOG_LEVEL", "INFO"))


def _decode_envelope(raw: bytes) -> tuple[dict, dict[str, str]]:
    """kreceiver wraps messages as {"payload_b64": ..., "headers": {...}}."""
    env = json.loads(raw)
    if "payload_b64" in env:
        return json.loads(base64.b64decode(env["payload_b64"])), env.get("headers", {})
    return env, {}


def load_dbc(path: str) -> dict[int, object]:
    """Load a phone-dbc-repo DBC and return {raw_can_id: message}.

    These DBCs carry 29-bit ids WITHOUT the 0x80000000 extended flag in BO_
    lines, which cantools rejects even with strict=False. Set the flag on any
    id > 0x7FF, then key the lookup by the raw 29-bit id so both STD and EXT
    dongle frames resolve.
    """
    text = open(path).read()

    def _fix(m: re.Match) -> str:
        fid = int(m.group(1))
        if 0x7FF < fid < 0x80000000:
            fid |= 0x80000000
        return f"BO_ {fid} {m.group(2)}"

    db = cantools.database.load_string(re.sub(r"BO_ (\d+) (\S+)", _fix, text), "dbc", strict=False)
    return {(m.frame_id & 0x1FFFFFFF): m for m in db.messages}


def main() -> None:
    cfg = IggyBackendConfig(
        connection_string=os.environ["IGGY_CONNECTION_STRING"],
        stream=os.getenv("IGGY_STREAM", "can-pub-sub-probe"),
    )
    backend = IggyPubSubBackend(config=cfg)
    db = load_dbc(os.getenv("DBC_PATH", "/dbcs/gm_global_a_lowspeed.dbc"))

    in_topic = os.getenv("CAN_DECODE_INPUT_TOPIC", "telemetry.raw.can")
    out_topic = os.getenv("CAN_DECODE_OUTPUT_TOPIC", "telemetry.decoded.can")
    unknown_topic = os.getenv("CAN_DECODE_UNKNOWN_TOPIC", "")

    backend.ensure_topic(in_topic)
    backend.ensure_topic(out_topic)
    if unknown_topic:
        backend.ensure_topic(unknown_topic)

    LOG.info("can_decode: %s -> %s (dbc messages: %d)", in_topic, out_topic, len(db.messages))
    for msg in backend.subscribe(in_topic):
        with msg:
            try:
                inner, headers = _decode_envelope(msg.payload)
                frames = inner.get("payload") or inner.get("frames") or []
                for f in frames:
                    can_id = int(f.get("id", f.get("can_id", 0)), 0)
                    data = bytes.fromhex(f.get("data_hex", "")) if f.get("data_hex") else bytes(f.get("data_bytes", []))
                    try:
                        m = db[can_id]
                        signals = m.decode(data, decode_choices=False)
                        out = {"can_id": can_id, "message": m.name, "signals": signals, "raw_hex": data.hex()}
                        backend.publish(out_topic, json.dumps(out).encode(), {**headers, "x-source-type": "can_decoded"})
                    except KeyError:
                        if unknown_topic:
                            backend.publish(unknown_topic, json.dumps({"can_id": can_id, "raw_hex": data.hex()}).encode(), headers)
            except Exception:
                LOG.exception("failed to decode message")


if __name__ == "__main__":
    main()
