"""DBC decoding of kreceiver's raw CAN records.

kreceiver publishes one NormalizedIngressMessage per CAN frame to
telemetry.raw.can (capture_device_configs/gps-test-feed/kreceiver_proto):

    {"source_type": "can", "device_id": ..., "session_id": ..., "captured_at": ...,
     "payload": {"can_id": 201, "data_hex": "840c...", "dlc": 7, "dt_us": ..., ...}, ...}

decode_record() turns one of those into the decoded record, keeping every
envelope field so downstream consumers still have the device, session and
capture time.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

import cantools
from cantools.database.can import Message

_EXTENDED_FLAG = 0x80000000
_ID_MASK = 0x1FFFFFFF
_BO_LINE = re.compile(r"^BO_ (\d+) ", re.MULTILINE)


def load_dbc(text: str) -> dict[int, Message]:
    """Parse a DBC and return {raw CAN id: message}.

    phone-dbc-repo DBCs write 29-bit ids without the 0x80000000 extended flag on
    their BO_ lines, which cantools rejects. Set the flag on any id above 11 bits,
    then key by the bare id: the dongle sends ids without a flag.
    """

    def _flag_extended(match: re.Match[str]) -> str:
        frame_id = int(match.group(1))
        if 0x7FF < frame_id < _EXTENDED_FLAG:
            frame_id |= _EXTENDED_FLAG
        return f"BO_ {frame_id} "

    db = cantools.database.load_string(_BO_LINE.sub(_flag_extended, text), "dbc", strict=False)
    return {message.frame_id & _ID_MASK: message for message in db.messages}


@dataclass(frozen=True, slots=True)
class Decoded:
    record: dict[str, Any]


@dataclass(frozen=True, slots=True)
class Unknown:
    """No DBC message for this id, or the data does not fit the DBC's layout."""

    record: dict[str, Any]
    reason: str


def decode_record(record: dict[str, Any], db: dict[int, Message], dbc_name: str | None = None) -> Decoded | Unknown:
    payload = record["payload"]
    can_id = int(payload["can_id"])
    data = bytes.fromhex(payload["data_hex"])
    raw = {"can_id": can_id, "data_hex": data.hex(), "dt_us": payload.get("dt_us")}
    if dbc_name is not None:
        raw["dbc"] = dbc_name  # which DBC the decode (or the miss) is relative to

    message = db.get(can_id & _ID_MASK)
    if message is None:
        return Unknown(_with_payload(record, "can_unknown", raw), "no DBC message")
    try:
        signals = message.decode(data, decode_choices=False, allow_truncated=False)
    except Exception as exc:  # cantools raises several types for bad layouts
        return Unknown(_with_payload(record, "can_unknown", raw), f"{message.name}: {exc}")
    return Decoded(
        _with_payload(record, "can_decoded", {**raw, "message": message.name, "signals": signals})
    )


def _with_payload(record: dict[str, Any], source_type: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {**record, "source_type": source_type, "payload": payload}
