from app.roles import apply_roles
from app.snapshot import CanSample, FrameData, GpsFix, ImuSample, build_rows

BASE = 1_793_743_684_000_000
ROLES = {
    "speed_kph": {"signal": "VehicleSpeed", "unit": "mph"},
    "oil_temp_c": {"signal": "EngineOilTemp", "unit": "degF"},
    "brake_on": {"signal": "BrakePedalPressed"},
    "rpm": {"signal": "EngineRPM"},
}


def frame(**kw) -> FrameData:
    return FrameData("s1", "phone-1", BASE, **kw)


def test_roles_convert_units_and_leave_unmapped_roles_empty():
    out = apply_roles(ROLES, {"VehicleSpeed": 10.0, "EngineOilTemp": 212.0, "BrakePedalPressed": 1})
    assert round(out["speed_kph"], 4) == 16.0934
    assert round(out["oil_temp_c"], 6) == 100.0
    assert out["brake_on"] is True
    assert out["rpm"] is None and out["gear"] is None  # signal absent / role unmapped


def test_one_second_interval_gives_one_row_with_last_values_and_stats():
    f = frame(
        can=[CanSample(BASE + 900_000, {"EngineRPM": 2000.0}), CanSample(BASE + 100_000, {"EngineRPM": 1000.0}),
             CanSample(BASE + 500_000, {"VehicleSpeed": 30.0})],
        gps=[GpsFix(BASE + 200_000, {"latitude": 1.0, "longitude": 2.0, "num_sats": 9}),
             GpsFix(BASE + 700_000, {"latitude": 1.5, "longitude": 2.5, "num_sats": 10})],
        imu=[ImuSample(BASE + i * 20_000, {"x": i, "y": 0.0, "z": 9.8}, {"x": 0.0, "y": 0.0, "z": -i}) for i in range(50)],
        imu_filled=2, dbc="impala",
    )
    carry = {}
    [row] = build_rows(f, 1_000_000, carry, ROLES, complete=True)
    assert row["frame_base_us"] == BASE and row["t"].timestamp() * 1e6 == BASE
    assert row["rpm"] == 2000.0  # the latest, though it arrived first
    assert row["signal_stats"]["EngineRPM"] == [1000.0, 2000.0, 1500.0, 2]
    assert (row["lat"], row["lon"], row["num_sats"], row["gps_fixes"]) == (1.5, 2.5, 10, 2)
    assert row["imu_samples"] == 50 and row["imu_filled_in_frame"] == 2
    assert row["accel_mean"]["x"] == 24.5 and row["accel_min"]["x"] == 0 and row["gyro_min"]["z"] == -49
    assert row["carried"] == {} and row["dbc"] == "impala" and row["complete"] is True
    assert carry["EngineRPM"] == (2000.0, BASE + 900_000)


def test_signals_not_seen_are_carried_forward_with_their_age():
    carry = {"Odometer": (12345.0, BASE - 2_500_000)}
    [row] = build_rows(frame(can=[CanSample(BASE + 10, {"EngineRPM": 900.0})]), 1_000_000, carry, ROLES, True)
    assert row["signals"] == {"Odometer": 12345.0, "EngineRPM": 900.0}
    assert row["carried"] == {"Odometer": 3500}  # ms from last seen to the interval end
    assert "Odometer" not in row["signal_stats"]


def test_sub_second_intervals_split_the_frame():
    f = frame(can=[CanSample(BASE + 50_000, {"EngineRPM": 800.0}), CanSample(BASE + 250_000, {"EngineRPM": 900.0})],
              gps=[GpsFix(BASE + 150_000, {"latitude": 1.0})])
    rows = build_rows(f, 100_000, {}, ROLES, True)
    assert len(rows) == 10
    assert [r["rpm"] for r in rows[:4]] == [800.0, 800.0, 900.0, 900.0]
    assert rows[1]["carried"] == {"EngineRPM": 150}
    assert [r["gps_fixes"] for r in rows[:3]] == [0, 1, 0]
    assert rows[0]["accel_mean"] is None and rows[0]["imu_samples"] == 0
