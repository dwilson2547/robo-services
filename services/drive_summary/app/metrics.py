"""A drive's summary from its vehicle_state rows (docs/plans/2026-11-03-trip-pipelines.md §4,
adjusted by "Drive 1 findings"). Pure: rows in, metrics dict out.

- Distance: GPS (haversine, gaps over 5 s skipped) is the headline; CAN (speed x time)
  and the odometer are reported beside it.
- Longitudinal acceleration from the CAN speed (central difference); lateral as
  speed x yaw rate (the gyro's rotation rate, mostly yaw in a car). The IMU's linear
  acceleration is not used: it tracks the car poorly (BNO085 fusion).
- An event is a run of consecutive intervals beyond its threshold, counted once.
"""

from __future__ import annotations

import math
from typing import Any

VERSION = 1
G = 9.80665
MOVING_KPH = 1.0
LATERAL_MIN_KPH = 10.0
GPS_GAP_S = 5.0
THRESHOLDS_G = {"hard_braking": -0.35, "hard_acceleration": 0.30, "sharp_cornering": 0.40}
HIGH_RPM = 4000.0
ROLE_COLUMNS = ("speed_kph", "rpm", "throttle_pct", "brake_on", "brake_kpa", "gear", "steering_deg",
                "odometer_km", "coolant_c", "oil_temp_c", "prndl")


def _r(x: float | None, n: int = 3) -> float | None:
    return None if x is None else round(x, n)


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(a))


def _runs(flags: list[bool]) -> int:
    return sum(1 for i, f in enumerate(flags) if f and (i == 0 or not flags[i - 1]))


def _span(values: list[float]) -> dict[str, float | None] | None:
    vals = [v for v in values if v is not None]
    if not vals:
        return None
    return {"start": _r(vals[0], 1), "max": _r(max(vals), 1), "end": _r(vals[-1], 1)}


def summarise(rows: list[dict[str, Any]], *, frames_received: int | None = None,
              completeness_pct: float | None = None) -> dict[str, Any]:
    rows = sorted(rows, key=lambda r: r["t"])
    if not rows:
        return {"version": VERSION, "empty": True}
    ts = [r["t"].timestamp() for r in rows]
    gaps = [b - a for a, b in zip(ts, ts[1:])]
    interval = min(gaps) if gaps else 1.0
    n = len(rows)
    speed = [r.get("speed_kph") for r in rows]
    rpm = [r.get("rpm") for r in rows]
    moving = [s is not None and s > MOVING_KPH for s in speed]

    # distances
    gps_km, last = 0.0, None
    for r, t in zip(rows, ts):
        if r.get("lat") is None or r.get("lon") is None:
            continue
        if last is not None and t - last[0] <= GPS_GAP_S:
            gps_km += haversine_km(last[1], last[2], r["lat"], r["lon"])
        last = (t, r["lat"], r["lon"])
    can_km = sum(s * interval / 3600 for s in speed if s is not None)
    odo = [r.get("odometer_km") for r in rows if r.get("odometer_km") is not None]

    # longitudinal acceleration from CAN speed, only across contiguous intervals
    lon_g: list[float | None] = [None] * n
    for i in range(1, n - 1):
        if speed[i - 1] is None or speed[i + 1] is None:
            continue
        if abs(ts[i + 1] - ts[i - 1] - 2 * interval) > interval / 2:
            continue
        lon_g[i] = (speed[i + 1] - speed[i - 1]) / 3.6 / (2 * interval) / G
    # lateral acceleration: speed x rotation rate
    lat_g: list[float | None] = [None] * n
    for i, r in enumerate(rows):
        gyro = r.get("gyro_mean")
        if gyro and speed[i] is not None and speed[i] >= LATERAL_MIN_KPH:
            rate = math.radians(math.sqrt(sum(gyro[a] ** 2 for a in ("x", "y", "z"))))
            lat_g[i] = speed[i] / 3.6 * rate / G

    # selector and gears
    selector: dict[str, float] = {}
    gears: dict[str, float] = {}
    shifts = 0
    has_prndl = any(r.get("prndl") is not None for r in rows)
    prev_gear = None
    for r in rows:
        pos = r.get("prndl")
        if pos is not None:
            selector[pos] = selector.get(pos, 0) + interval
        gear = r.get("gear")
        in_drive = pos == "D" if has_prndl else gear is not None
        if in_drive and gear is not None:
            key = str(int(gear))
            gears[key] = gears.get(key, 0) + interval
            if prev_gear is not None and gear != prev_gear:
                shifts += 1
            prev_gear = gear
        elif has_prndl and pos != "D":
            prev_gear = None

    brake = [bool(r.get("brake_on")) for r in rows]
    brake_kpa = [r["brake_kpa"] for r in rows if r.get("brake_kpa") is not None]
    moving_speeds = [s for s, m in zip(speed, moving) if m]
    moving_rpm = [p for p, m in zip(rpm, moving) if m and p is not None]
    lon_vals = [g for g in lon_g if g is not None]
    lat_vals = [g for g in lat_g if g is not None]
    gps_speeds = [r["gps_speed_kph"] for r in rows if r.get("gps_speed_kph") is not None]
    frames_used = len({r["frame_base_us"] for r in rows})

    return {
        "version": VERSION,
        "interval_s": interval,
        "started_at": rows[0]["t"].isoformat(),
        "duration_s": _r(ts[-1] - ts[0] + interval, 1),
        "moving_s": _r(sum(moving) * interval, 1),
        "idle_s": _r(sum(1 for s, p, m in zip(speed, rpm, moving) if not m and p) * interval, 1),
        "stops": sum(1 for i in range(1, n) if moving[i - 1] and not moving[i]),
        "distance_km": {
            "gps": _r(gps_km),
            "can": _r(can_km) if any(s is not None for s in speed) else None,
            "odometer": _r(max(odo) - min(odo)) if odo else None,
        },
        "speed_kph": {
            "avg_moving": _r(sum(moving_speeds) / len(moving_speeds), 1) if moving_speeds else None,
            "max_can": _r(max((s for s in speed if s is not None), default=None), 1),
            "max_gps": _r(max(gps_speeds, default=None), 1),
        },
        "engine": {
            "max_rpm": _r(max((p for p in rpm if p is not None), default=None), 0),
            "avg_rpm_moving": _r(sum(moving_rpm) / len(moving_rpm), 0) if moving_rpm else None,
            "seconds_above_4000": _r(sum(1 for p in rpm if p is not None and p > HIGH_RPM) * interval, 1),
        },
        "selector_s": {k: _r(v, 1) for k, v in sorted(selector.items())},
        "gears_s": {k: _r(v, 1) for k, v in sorted(gears.items(), key=lambda kv: int(kv[0]))},
        "shifts": shifts,
        "brakes": {
            "applications": sum(1 for i, b in enumerate(brake) if b and (i == 0 or not brake[i - 1])),
            "seconds": _r(sum(brake) * interval, 1),
            "max_kpa": _r(max(brake_kpa, default=None), 0),
        },
        "events": {
            "hard_braking": _runs([g is not None and g <= THRESHOLDS_G["hard_braking"] for g in lon_g]),
            "hard_acceleration": _runs([g is not None and g >= THRESHOLDS_G["hard_acceleration"] for g in lon_g]),
            "sharp_cornering": _runs([g is not None and g >= THRESHOLDS_G["sharp_cornering"] for g in lat_g]),
            "thresholds_g": THRESHOLDS_G,
        },
        "peaks_g": {
            "braking": _r(min(lon_vals, default=None)),
            "acceleration": _r(max(lon_vals, default=None)),
            "lateral": _r(max(lat_vals, default=None)),
        },
        "temperatures_c": {
            "coolant": _span([r.get("coolant_c") for r in rows]),
            "oil": _span([r.get("oil_temp_c") for r in rows]),
        },
        "quality": {
            "completeness_pct": completeness_pct,
            "frames_used": frames_used,
            "frames_received": frames_received,
            "incomplete_frames": len({r["frame_base_us"] for r in rows if not r.get("complete", True)}),
            "intervals_without_gps": sum(1 for r in rows if not r.get("gps_fixes")),
            "imu_points_filled": sum({r["frame_base_us"]: r.get("imu_filled_in_frame") or 0 for r in rows}.values()),
            "roles_unavailable": [c for c in ROLE_COLUMNS if all(r.get(c) is None for r in rows)],
        },
    }


def one_line(source_session: str, m: dict[str, Any]) -> str:
    if m.get("empty"):
        return f"session {source_session}: no vehicle state rows"
    d, s = m["distance_km"], m["speed_kph"]
    mins, secs = divmod(int(m["duration_s"]), 60)
    return (
        f"session {source_session}: {d['gps']} km (GPS; CAN {d['can']}) in {mins}m{secs:02d}s, "
        f"moving {m['moving_s']:.0f}s at avg {s['avg_moving']} km/h, max {s['max_can']} km/h, "
        f"{m['stops']} stops, {m['brakes']['applications']} brake applications, {m['shifts']} shifts, "
        f"events {m['events']['hard_braking']}/{m['events']['hard_acceleration']}/{m['events']['sharp_cornering']} "
        f"(brake/accel/corner), quality {m['quality']['frames_used']}/{m['quality']['frames_received']} frames"
    )
