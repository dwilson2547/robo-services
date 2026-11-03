from datetime import UTC, datetime, timedelta

from app.metrics import haversine_km, one_line, summarise

T0 = datetime(2026, 11, 3, 22, 37, 25, tzinfo=UTC)
BASE = 1_793_745_445_000_000


def row(i, speed, **kw):
    r = {"t": T0 + timedelta(seconds=i), "frame_base_us": BASE + i * 1_000_000, "speed_kph": speed, "rpm": 800.0,
         "brake_on": False, "brake_kpa": 0.0, "gear": 1.0, "prndl": "D", "odometer_km": 100.0, "coolant_c": 70.0,
         "oil_temp_c": None, "lat": None, "lon": None, "gps_speed_kph": None, "gps_fixes": 0,
         "gyro_mean": {"x": 0.0, "y": 0.0, "z": 0.0}, "imu_filled_in_frame": 0, "complete": True}
    r.update(kw)
    return r


def drive():
    rows = [row(i, 0.0, prndl="P", gear=15.0) for i in range(3)]                       # parked
    rows += [row(3 + i, 0.0, prndl="R", gear=14.0) for i in range(2)]                  # reverse
    speeds = [0, 10, 20, 30, 40, 50, 50, 50, 50, 50, 30, 10, 0, 0]                     # pull away, brake hard
    for j, s in enumerate(speeds):
        i = 5 + j
        rows.append(row(i, float(s), gear=1.0 if s < 25 else 2.0, rpm=800.0 + s * 40,
                        brake_on=j in (10, 11), brake_kpa=900.0 if j in (10, 11) else 0.0,
                        coolant_c=70.0 + j, odometer_km=100.0 + (1 if j > 8 else 0),
                        lat=42.0 + j * 0.0001, lon=-83.0, gps_fixes=1, gps_speed_kph=float(s),
                        gyro_mean={"x": 0.0, "y": 0.0, "z": 30.0 if j == 7 else 0.0}))
    return rows


def test_a_short_drive():
    m = summarise(drive(), frames_received=19, completeness_pct=100.0)
    assert m["duration_s"] == 19.0 and m["moving_s"] == 11.0 and m["stops"] == 1
    assert m["idle_s"] == 8.0  # parked, reversing at 0 km/h, and the two stopped seconds, engine running
    assert m["selector_s"] == {"D": 14.0, "P": 3.0, "R": 2.0}
    assert m["gears_s"] == {"1": 6.0, "2": 8.0} and m["shifts"] == 2  # up to 2nd, back down to 1st
    assert m["brakes"] == {"applications": 1, "seconds": 2.0, "max_kpa": 900.0}
    assert m["distance_km"]["odometer"] == 1.0
    # the last two fixes are stopped: segments ending at a standstill add nothing
    assert abs(m["distance_km"]["gps"] - haversine_km(42.0, -83.0, 42.0011, -83.0)) < 1e-3
    assert m["distance_km"]["can"] == round(sum([0, 10, 20, 30, 40, 50, 50, 50, 50, 50, 30, 10, 0, 0]) / 3600, 3)
    # 50 -> 10 km/h over 2 s is -0.57 g: one hard-braking event spanning two intervals
    assert m["events"]["hard_braking"] == 1 and m["peaks_g"]["braking"] < -0.35
    assert m["events"]["hard_acceleration"] == 0  # +10 km/h per second is 0.28 g
    # 50 km/h at 30 deg/s is 0.74 g lateral
    assert m["events"]["sharp_cornering"] == 1 and abs(m["peaks_g"]["lateral"] - 13.889 * 0.5236 / 9.80665) < 1e-3
    assert m["temperatures_c"]["coolant"] == {"start": 70.0, "max": 83.0, "end": 83.0}
    assert m["temperatures_c"]["oil"] is None
    assert m["quality"]["frames_used"] == 19 and m["quality"]["intervals_without_gps"] == 5
    assert m["quality"]["roles_unavailable"] == ["throttle_pct", "steering_deg", "oil_temp_c"]
    assert "km (GPS" in one_line("s1", m)


def test_without_prndl_every_gear_counts_and_nothing_is_reported_for_missing_roles():
    rows = [row(i, 20.0, prndl=None, gear=float(1 + i // 2)) for i in range(4)]
    m = summarise(rows)
    assert m["selector_s"] == {} and m["gears_s"] == {"1": 2.0, "2": 2.0} and m["shifts"] == 1


def test_no_rows():
    assert summarise([])["empty"] is True


def test_parked_gps_drift_adds_no_distance():
    rows = [row(i, 0.0, prndl="P", lat=42.0 + (i % 2) * 0.0002, lon=-83.0, gps_fixes=1) for i in range(10)]
    assert summarise(rows)["distance_km"]["gps"] == 0.0
    no_can = [row(i, None, lat=42.0 + i * 0.0002, lon=-83.0, gps_fixes=1, gps_speed_kph=80.0) for i in range(3)]
    assert summarise(no_can)["distance_km"]["gps"] > 0.04  # without CAN speed, GPS speed decides
