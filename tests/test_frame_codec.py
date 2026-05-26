from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from can_pub_sub_probe.frame_codec import iter_frame_file, parse_frame, serialize_frame
from can_pub_sub_probe.models import RawFrame


class FrameCodecTests(unittest.TestCase):
    def test_round_trip_preserves_frame(self) -> None:
        original = RawFrame(
            timestamp_ms=123456,
            can_id=0x1EF,
            dlc=4,
            data=b"\x00\x00\x27\x10",
            is_extended=False,
        )

        parsed = parse_frame(serialize_frame(original))

        self.assertEqual(parsed, original)

    def test_iter_frame_file_reads_multiple_records(self) -> None:
        frames = [
            RawFrame(timestamp_ms=1, can_id=0x101, dlc=1, data=b"\x01"),
            RawFrame(timestamp_ms=2, can_id=0x102, dlc=2, data=b"\x02\x03"),
        ]

        with tempfile.TemporaryDirectory() as tmp_dir:
            path = Path(tmp_dir) / "frames.bin"
            path.write_bytes(b"".join(serialize_frame(frame) for frame in frames))
            parsed = list(iter_frame_file(path))

        self.assertEqual(parsed, frames)
