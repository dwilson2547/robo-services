from __future__ import annotations

import unittest

from can_pub_sub_probe.models import RawFrame
from can_pub_sub_probe.replay import ReplayFrameSource, replay_frames


class ReplayTests(unittest.TestCase):
    def test_replay_scales_delays_by_speed_multiplier(self) -> None:
        frames = [
            RawFrame(timestamp_ms=1_000, can_id=0x100, dlc=1, data=b"\x00"),
            RawFrame(timestamp_ms=1_250, can_id=0x101, dlc=1, data=b"\x01"),
            RawFrame(timestamp_ms=1_350, can_id=0x102, dlc=1, data=b"\x02"),
        ]
        sleeps: list[float] = []

        replayed = list(
            replay_frames(
                frames,
                speed_multiplier=5.0,
                sleeper=sleeps.append,
            )
        )

        self.assertEqual(replayed, frames)
        self.assertEqual(sleeps, [0.05, 0.02])

    def test_replay_frame_source_wraps_replay_iterator(self) -> None:
        frames = [
            RawFrame(timestamp_ms=1_000, can_id=0x100, dlc=1, data=b"\x00"),
            RawFrame(timestamp_ms=1_100, can_id=0x101, dlc=1, data=b"\x01"),
        ]
        sleeps: list[float] = []

        replayed = list(ReplayFrameSource(frames, speed_multiplier=2.0, sleeper=sleeps.append))

        self.assertEqual(replayed, frames)
        self.assertEqual(sleeps, [0.05])
