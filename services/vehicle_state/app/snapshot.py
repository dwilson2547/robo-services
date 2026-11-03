"""One frame's samples -> vehicle state rows, one per interval
(docs/plans/2026-11-03-trip-pipelines.md §3).

Pure: no I/O. `Carry` is the session's signal state across frames; build_rows
updates it, so frames of a session must be built in time order.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from .roles import apply_roles

AXES = ("x", "y", "z")


@dataclass
class CanSample:
    t_us: int
    signals: dict[str, float]


@dataclass
class GpsFix:
    t_us: int
    payload: dict[str, Any]


@dataclass
class ImuSample:
    t_us: int
    accel: dict[str, float]
    gyro: dict[str, float]


@dataclass
class FrameData:
    source_session: str
    device_id: str
    base_us: int
    can: list[CanSample] = field(default_factory=list)
    gps: list[GpsFix] = field(default_factory=list)
    imu: list[ImuSample] = field(default_factory=list)
    imu_filled: int | None = None  # from the frame receipt
    dbc: str | None = None


# signal -> (value, time it was last seen, us)
Carry = dict[str, tuple[float, int]]


def _axis_stats(samples: list[dict[str, float]]) -> tuple[dict, dict, dict] | tuple[None, None, None]:
    if not samples:
        return None, None, None
    mean = {a: sum(s[a] for s in samples) / len(samples) for a in AXES}
    low = {a: min(s[a] for s in samples) for a in AXES}
    high = {a: max(s[a] for s in samples) for a in AXES}
    return mean, low, high


def _iso(t_us: int) -> datetime:
    return datetime.fromtimestamp(t_us / 1_000_000, tz=UTC)


def build_rows(
    frame: FrameData, interval_us: int, carry: Carry, roles: dict[str, dict], complete: bool, frame_us: int = 1_000_000
) -> list[dict[str, Any]]:
    """Rows for every interval of the frame. Updates `carry` in place."""
    can = sorted(frame.can, key=lambda s: s.t_us)
    gps = sorted(frame.gps, key=lambda s: s.t_us)
    imu = sorted(frame.imu, key=lambda s: s.t_us)
    rows = []
    ci = gi = ii = 0
    for start in range(frame.base_us, frame.base_us + frame_us, interval_us):
        end = start + interval_us
        stats: dict[str, list[float]] = {}  # name -> [min, max, sum, count]
        while ci < len(can) and can[ci].t_us < end:
            sample = can[ci]
            for name, value in sample.signals.items():
                if value is None:
                    continue
                v = float(value)
                carry[name] = (v, sample.t_us)
                s = stats.get(name)
                if s is None:
                    stats[name] = [v, v, v, 1]
                else:
                    s[0], s[1], s[2], s[3] = min(s[0], v), max(s[1], v), s[2] + v, s[3] + 1
            ci += 1
        fixes = []
        while gi < len(gps) and gps[gi].t_us < end:
            fixes.append(gps[gi])
            gi += 1
        samples = []
        while ii < len(imu) and imu[ii].t_us < end:
            samples.append(imu[ii])
            ii += 1

        signals = {name: value for name, (value, _) in carry.items()}
        carried = {name: (end - seen) // 1000 for name, (_, seen) in carry.items() if seen < start}
        last_fix = fixes[-1].payload if fixes else {}
        accel_mean, accel_min, accel_max = _axis_stats([s.accel for s in samples])
        gyro_mean, gyro_min, gyro_max = _axis_stats([s.gyro for s in samples])
        rows.append({
            "t": _iso(start),
            "source_session": frame.source_session,
            "device_id": frame.device_id,
            "frame_base_us": frame.base_us,
            **apply_roles(roles, signals),
            "lat": last_fix.get("latitude"),
            "lon": last_fix.get("longitude"),
            "alt_m": last_fix.get("altitude_m"),
            "gps_speed_kph": last_fix.get("ground_speed_kph"),
            "heading_deg": last_fix.get("heading_deg"),
            "num_sats": last_fix.get("num_sats"),
            "hdop": last_fix.get("hdop"),
            "gps_fixes": len(fixes),
            "accel_mean": accel_mean, "accel_min": accel_min, "accel_max": accel_max,
            "gyro_mean": gyro_mean, "gyro_min": gyro_min, "gyro_max": gyro_max,
            "imu_samples": len(samples),
            "imu_filled_in_frame": frame.imu_filled,
            "signals": signals,
            "signal_stats": {n: [s[0], s[1], s[2] / s[3], s[3]] for n, s in stats.items()},
            "carried": carried,
            "dbc": frame.dbc,
            "complete": complete,
        })
    return rows
