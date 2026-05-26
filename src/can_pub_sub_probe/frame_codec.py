from __future__ import annotations

import struct
from collections.abc import Iterator
from pathlib import Path

from .models import RawFrame

FRAME_SIZE = 17
_FRAME_STRUCT = struct.Struct(">IIB8s")
_EXTENDED_FLAG = 0x80000000


def serialize_frame(frame: RawFrame) -> bytes:
    can_word = frame.can_id | (_EXTENDED_FLAG if frame.is_extended else 0)
    return _FRAME_STRUCT.pack(
        frame.timestamp_ms,
        can_word,
        frame.dlc,
        frame.padded_data,
    )


def parse_frame(payload: bytes) -> RawFrame:
    if len(payload) != FRAME_SIZE:
        raise ValueError(f"payload must be exactly {FRAME_SIZE} bytes")
    timestamp_ms, can_word, dlc, padded_data = _FRAME_STRUCT.unpack(payload)
    is_extended = bool(can_word & _EXTENDED_FLAG)
    can_id = can_word & ~_EXTENDED_FLAG
    return RawFrame(
        timestamp_ms=timestamp_ms,
        can_id=can_id,
        dlc=dlc,
        data=padded_data[:dlc],
        is_extended=is_extended,
    )


def iter_frame_bytes(payload: bytes) -> Iterator[RawFrame]:
    remainder = len(payload) % FRAME_SIZE
    if remainder != 0:
        raise ValueError("binary frame payload is not aligned to 17-byte records")
    for offset in range(0, len(payload), FRAME_SIZE):
        yield parse_frame(payload[offset : offset + FRAME_SIZE])


def iter_frame_file(path: str | Path) -> Iterator[RawFrame]:
    with Path(path).open("rb") as handle:
        while True:
            chunk = handle.read(FRAME_SIZE)
            if not chunk:
                return
            if len(chunk) != FRAME_SIZE:
                raise ValueError("truncated frame record at end of file")
            yield parse_frame(chunk)


def write_frame_file(path: str | Path, frames: Iterator[RawFrame] | list[RawFrame]) -> None:
    with Path(path).open("wb") as handle:
        for frame in frames:
            handle.write(serialize_frame(frame))
