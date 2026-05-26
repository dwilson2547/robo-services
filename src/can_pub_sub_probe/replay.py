from __future__ import annotations

import json
import time
from collections.abc import Callable
from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from pathlib import Path

from .frame_codec import iter_frame_file, write_frame_file
from .interfaces import FrameSource
from .models import RawFrame


@dataclass(frozen=True, slots=True)
class ReplaySessionMetadata:
    session_id: str
    vehicle: str
    capture_start_utc: str
    bus: str
    baud: int
    frame_count: int
    duration_seconds: int

    @classmethod
    def from_path(cls, path: str | Path) -> "ReplaySessionMetadata":
        data = json.loads(Path(path).read_text())
        return cls(
            session_id=data["session_id"],
            vehicle=data["vehicle"],
            capture_start_utc=data["capture_start_utc"],
            bus=data["bus"],
            baud=int(data["baud"]),
            frame_count=int(data["frame_count"]),
            duration_seconds=int(data["duration_seconds"]),
        )


def replay_frames(
    frames: Iterable[RawFrame],
    *,
    speed_multiplier: float = 1.0,
    sleeper: Callable[[float], None] = time.sleep,
) -> Iterator[RawFrame]:
    if speed_multiplier <= 0:
        raise ValueError("speed_multiplier must be greater than zero")
    previous_timestamp_ms: int | None = None
    for frame in frames:
        if previous_timestamp_ms is not None:
            gap_seconds = (frame.timestamp_ms - previous_timestamp_ms) / 1000
            if gap_seconds < 0:
                raise ValueError("frame timestamps must be non-decreasing for replay")
            sleeper(gap_seconds / speed_multiplier)
        yield frame
        previous_timestamp_ms = frame.timestamp_ms


def load_frame_log(path: str | Path) -> list[RawFrame]:
    return list(iter_frame_file(path))


def write_frame_log(path: str | Path, frames: Iterable[RawFrame]) -> None:
    write_frame_file(path, list(frames))


@dataclass(slots=True)
class ReplayFrameSource(FrameSource):
    frames: Iterable[RawFrame]
    speed_multiplier: float = 1.0
    sleeper: Callable[[float], None] = field(default=time.sleep, repr=False)

    def __iter__(self) -> Iterator[RawFrame]:
        return replay_frames(
            self.frames,
            speed_multiplier=self.speed_multiplier,
            sleeper=self.sleeper,
        )
