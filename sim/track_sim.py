#!/usr/bin/env python3
"""
track_sim.py — Synthetic GPS/IMU track session emitter for robo-services.

Emits a replay-able sequence of GPS and IMU messages via UDP to kreceiver,
exercising the full telemetry pipeline through to the LapSegmentationJob.

Usage:
    python track_sim.py --scenario scenarios/clean.json [--receiver-host 127.0.0.1] [--receiver-port 9000]
    python track_sim.py --scenario scenarios/pit_stop.json --speed 10
    python track_sim.py --list-scenarios

The track is a synthetic ~2 km oval called "Gravel Creek".
"""

from __future__ import annotations

import argparse
import json
import math
import socket
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Synthetic track: "Gravel Creek Oval"
# 12 waypoints defining a closed circuit, ~2 km per lap.
# Format: (lat, lon, target_speed_kph)
# Center roughly at (37.00000, -112.00000)
# ---------------------------------------------------------------------------
TRACK_WAYPOINTS: list[tuple[float, float, float]] = [
    (37.00140, -112.00000,   0.0),   # 0: START/FINISH — staged, speed=0
    (37.00120, -112.00045,  60.0),   # 1: accelerating out of start
    (37.00080, -112.00090, 110.0),   # 2: front straight
    (37.00020, -112.00110, 105.0),   # 3: front straight end
    (36.99960, -112.00100,  75.0),   # 4: turn 1 entry
    (36.99910, -112.00070,  65.0),   # 5: turn 1 apex
    (36.99880, -112.00020,  80.0),   # 6: turn 1 exit
    (36.99870,  -111.99960,  95.0),   # 7: back straight
    (36.99880,  -111.99920,  90.0),   # 8: turn 2 entry
    (36.99940,  -111.99890,  70.0),   # 9: turn 2 apex
    (37.00000,  -111.99920,  80.0),   # 10: turn 2 exit
    (37.00060,  -111.99960, 100.0),   # 11: pit straight
    (37.00120,  -111.99980,  85.0),   # 12: approaching start
]

# IMU gravity baseline in m/s² (MPU-6050 style, raw, gravity not compensated)
GRAVITY_M_S2 = 9.81


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000.0
    lat1r, lat2r = math.radians(lat1), math.radians(lat2)
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1r) * math.cos(lat2r) * math.sin(dlon / 2) ** 2
    return r * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def interpolate_track(gps_hz: float) -> list[dict[str, Any]]:
    """
    Interpolate the waypoint sequence into individual GPS fixes at gps_hz.
    Returns a list of dicts with lat, lon, speed_kph, segment_time_s.
    """
    waypoints = TRACK_WAYPOINTS + [TRACK_WAYPOINTS[0]]  # close the loop
    fixes = []
    for i in range(len(waypoints) - 1):
        lat1, lon1, spd1 = waypoints[i]
        lat2, lon2, spd2 = waypoints[i + 1]
        dist_m = haversine_m(lat1, lon1, lat2, lon2)
        avg_speed_ms = ((spd1 + spd2) / 2) / 3.6
        segment_time_s = dist_m / avg_speed_ms if avg_speed_ms > 0 else 0.0
        n_fixes = max(1, int(segment_time_s * gps_hz))
        for j in range(n_fixes):
            t = j / n_fixes
            lat = lat1 + t * (lat2 - lat1)
            lon = lon1 + t * (lon2 - lon1)
            spd = spd1 + t * (spd2 - spd1)
            fixes.append({
                "lat": lat,
                "lon": lon,
                "speed_kph": spd,
                "fix_time_s": 1.0 / gps_hz,
            })
    return fixes


def simulate_imu(
    prev_speed_kph: float,
    curr_speed_kph: float,
    dt_s: float,
    gravity_compensated: bool = False,
) -> tuple[float, float, float]:
    """
    Simulate MPU-6050 style or BNO085 style accel readings.
    Returns (ax, ay, az) in m/s².
    """
    if dt_s <= 0:
        lon_accel = 0.0
    else:
        lon_accel = ((curr_speed_kph - prev_speed_kph) / 3.6) / dt_s

    if gravity_compensated:
        # BNO085: gravity removed, we only see dynamic acceleration
        ax, ay, az = lon_accel, 0.0, 0.0
    else:
        # MPU-6050: gravity on Z axis when flat, lon accel on X
        ax, ay, az = lon_accel, 0.0, GRAVITY_M_S2
    return ax, ay, az


def now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def send_udp(sock: socket.socket, host: str, port: int, payload: dict[str, Any]) -> None:
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    sock.sendto(data, (host, port))


def build_gps_msg(
    device_id: str,
    source_session: str,
    lat: float,
    lon: float,
    speed_kph: float,
    message_type: str = "telemetry",
    seq: int = 0,
) -> dict[str, Any]:
    return {
        "source": "gps",
        "source_session": source_session,
        "device_id": device_id,
        "message_type": message_type,
        "sequence": seq,
        "captured_at": now_iso(),
        "has_fix": True,
        "fix_quality": 1,
        "satellites": 9,
        "latitude": round(lat, 7),
        "longitude": round(lon, 7),
        "altitude_m": 1500.0,
        "ground_speed_kph": round(speed_kph, 2),
        "heading_deg": 0.0,
        "hdop": 1.2,
        "wifi_rssi_dbm": -55,
        "uptime_ms": seq * 1000,
    }


def build_imu_msg(
    device_id: str,
    source_session: str,
    ax: float,
    ay: float,
    az: float,
    gravity_compensated: bool = False,
    seq: int = 0,
) -> dict[str, Any]:
    if gravity_compensated:
        accel_key = "linear_accel"
    else:
        accel_key = "accel_m_s2"

    return {
        "source": "imu",
        "source_session": source_session,
        "device_id": device_id,
        "message_type": "imu",
        "sequence": seq,
        "captured_at": now_iso(),
        accel_key: {
            "x": round(ax, 3),
            "y": round(ay, 3),
            "z": round(az, 3),
        },
        "gyro_rad_s": {"x": 0.0, "y": 0.0, "z": 0.01},
        "temperature_c": 25.0,
        "wifi_rssi_dbm": -55,
        "uptime_ms": seq * 500,
    }


def run_scenario(scenario: dict[str, Any], args: argparse.Namespace) -> None:
    host = args.receiver_host
    port = args.receiver_port
    speed_mult = args.speed
    device_id = scenario["device_id"]
    source_session = scenario["source_session"]
    n_laps = scenario.get("laps", 3)
    gps_hz = float(scenario.get("gps_hz", 1.0))
    imu_hz = float(scenario.get("imu_hz", 2.0))
    pre_anchor_s = float(scenario.get("pre_anchor_seconds", 30))
    staged_s = float(scenario.get("staged_seconds", 10))
    pit_after_lap = scenario.get("pit_after_lap")
    pit_duration_s = float(scenario.get("pit_duration_seconds", 60))
    garage_wanders = scenario.get("garage_wanders", False)
    gravity_compensated = scenario.get("gravity_compensated", False)

    gps_interval = 1.0 / gps_hz / speed_mult
    imu_interval = 1.0 / imu_hz / speed_mult

    fixes = interpolate_track(gps_hz)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    gps_seq = 0
    imu_seq = 0
    imu_acc = 0.0  # accumulated time since last IMU
    prev_speed = 0.0

    print(f"[sim] scenario={scenario['name']} device={device_id} session={source_session}")
    print(f"[sim] receiver={host}:{port} gps_hz={gps_hz} imu_hz={imu_hz} speed_mult={speed_mult}x")
    print(f"[sim] laps={n_laps} pre_anchor={pre_anchor_s}s staged={staged_s}s")
    print()

    # ------------------------------------------------------------------
    # Phase 1: pre-anchor (garage / pit noise before driver is staged)
    # ------------------------------------------------------------------
    print("[sim] Phase 1: pre-anchor (garage noise)")
    garage_lat, garage_lon = 37.00200, -112.00050
    garage_fixes = int(pre_anchor_s * gps_hz)
    for i in range(garage_fixes):
        if garage_wanders:
            # Random tiny drift around garage position
            import random
            jlat = garage_lat + random.uniform(-0.0001, 0.0001)
            jlon = garage_lon + random.uniform(-0.0001, 0.0001)
        else:
            jlat, jlon = garage_lat, garage_lon
        msg = build_gps_msg(device_id, source_session, jlat, jlon, 0.0, seq=gps_seq)
        send_udp(sock, host, port, msg)
        gps_seq += 1
        # IMU
        imu_acc += 1.0 / gps_hz
        while imu_acc >= 1.0 / imu_hz:
            ax, ay, az = simulate_imu(0.0, 0.0, 1.0 / imu_hz, gravity_compensated)
            imsg = build_imu_msg(device_id, source_session, ax, ay, az, gravity_compensated, imu_seq)
            send_udp(sock, host, port, imsg)
            imu_seq += 1
            imu_acc -= 1.0 / imu_hz
        print(f"  gps #{gps_seq} garage ({jlat:.5f}, {jlon:.5f}) speed=0")
        time.sleep(gps_interval)

    # ------------------------------------------------------------------
    # Phase 2: staged at start line — send lap_anchor event
    # ------------------------------------------------------------------
    anchor_lat, anchor_lon = TRACK_WAYPOINTS[0][0], TRACK_WAYPOINTS[0][1]
    print(f"\n[sim] Phase 2: staged at start/finish ({anchor_lat}, {anchor_lon})")
    anchor_msg = build_gps_msg(
        device_id, source_session, anchor_lat, anchor_lon, 0.0,
        message_type="lap_anchor", seq=gps_seq,
    )
    send_udp(sock, host, port, anchor_msg)
    gps_seq += 1
    print(f"  [lap_anchor] sent at ({anchor_lat}, {anchor_lon})")

    # Hold still for staged_seconds, IMU baseline builds up here
    staged_fixes = max(1, int(staged_s * gps_hz))
    for i in range(staged_fixes):
        msg = build_gps_msg(device_id, source_session, anchor_lat, anchor_lon, 0.0, seq=gps_seq)
        send_udp(sock, host, port, msg)
        gps_seq += 1
        imu_acc += 1.0 / gps_hz
        while imu_acc >= 1.0 / imu_hz:
            ax, ay, az = simulate_imu(0.0, 0.0, 1.0 / imu_hz, gravity_compensated)
            imsg = build_imu_msg(device_id, source_session, ax, ay, az, gravity_compensated, imu_seq)
            send_udp(sock, host, port, imsg)
            imu_seq += 1
            imu_acc -= 1.0 / imu_hz
        time.sleep(gps_interval)

    # ------------------------------------------------------------------
    # Phase 3: laps
    # ------------------------------------------------------------------
    for lap in range(1, n_laps + 1):
        print(f"\n[sim] Lap {lap}/{n_laps} starting")
        for fix_idx, fix in enumerate(fixes):
            lat = fix["lat"]
            lon = fix["lon"]
            spd = fix["speed_kph"]

            msg = build_gps_msg(device_id, source_session, lat, lon, spd, seq=gps_seq)
            send_udp(sock, host, port, msg)
            gps_seq += 1

            imu_acc += 1.0 / gps_hz
            while imu_acc >= 1.0 / imu_hz:
                ax, ay, az = simulate_imu(prev_speed, spd, 1.0 / imu_hz, gravity_compensated)
                imsg = build_imu_msg(device_id, source_session, ax, ay, az, gravity_compensated, imu_seq)
                send_udp(sock, host, port, imsg)
                imu_seq += 1
                imu_acc -= 1.0 / imu_hz

            prev_speed = spd
            if fix_idx % 5 == 0:
                print(f"  fix #{gps_seq} ({lat:.5f}, {lon:.5f}) speed={spd:.1f} kph")
            time.sleep(gps_interval)

        print(f"  [lap {lap} complete — crossed start/finish]")

        # Pit stop scenario
        if pit_after_lap is not None and lap == pit_after_lap:
            print(f"\n[sim] Pit stop after lap {lap} ({pit_duration_s}s)")
            pit_fixes = max(1, int(pit_duration_s * gps_hz))
            for _ in range(pit_fixes):
                msg = build_gps_msg(device_id, source_session, anchor_lat, anchor_lon, 0.0, seq=gps_seq)
                send_udp(sock, host, port, msg)
                gps_seq += 1
                imu_acc += 1.0 / gps_hz
                while imu_acc >= 1.0 / imu_hz:
                    ax, ay, az = simulate_imu(0.0, 0.0, 1.0 / imu_hz, gravity_compensated)
                    imsg = build_imu_msg(device_id, source_session, ax, ay, az, gravity_compensated, imu_seq)
                    send_udp(sock, host, port, imsg)
                    imu_seq += 1
                    imu_acc -= 1.0 / imu_hz
                time.sleep(gps_interval)

    print(f"\n[sim] Session complete. GPS messages: {gps_seq}, IMU messages: {imu_seq}")
    sock.close()


def list_scenarios(scenarios_dir: Path) -> None:
    for p in sorted(scenarios_dir.glob("*.json")):
        try:
            data = json.loads(p.read_text())
            print(f"  {p.name:30s}  laps={data.get('laps', '?')} device={data.get('device_id', '?')}")
        except Exception:
            print(f"  {p.name:30s}  (unreadable)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Robo-services synthetic track session emitter")
    parser.add_argument("--scenario", help="Path to scenario JSON file")
    parser.add_argument("--receiver-host", default="127.0.0.1")
    parser.add_argument("--receiver-port", type=int, default=9000)
    parser.add_argument("--speed", type=float, default=1.0, help="Playback speed multiplier (e.g. 5 = 5x faster)")
    parser.add_argument("--list-scenarios", action="store_true", help="List available scenarios and exit")
    args = parser.parse_args()

    scenarios_dir = Path(__file__).parent / "scenarios"

    if args.list_scenarios:
        print("Available scenarios:")
        list_scenarios(scenarios_dir)
        return

    if not args.scenario:
        parser.error("--scenario is required unless --list-scenarios is used")

    scenario_path = Path(args.scenario)
    if not scenario_path.is_absolute():
        scenario_path = Path(__file__).parent / scenario_path
    scenario = json.loads(scenario_path.read_text())

    run_scenario(scenario, args)


if __name__ == "__main__":
    main()
