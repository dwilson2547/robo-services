from datetime import UTC, datetime, timedelta

from app.services.analytics import build_delta_trace, build_lap_series, build_segment_stats, TrackPositionRow


def _row(seconds: int, s_m: float, *, speed: float = 80.0, track_length_m: float = 100.0) -> TrackPositionRow:
    return TrackPositionRow(
        captured_at=datetime(2026, 6, 20, 12, 0, tzinfo=UTC) + timedelta(seconds=seconds),
        device_id="device-1",
        source_session="session-1",
        track_id=21,
        track_name="Test Track",
        track_length_m=track_length_m,
        s_m=s_m,
        ground_speed_kph=speed,
    )


def test_build_lap_series_splits_on_large_wrap() -> None:
    laps = build_lap_series([
        _row(0, 0.0),
        _row(10, 45.0),
        _row(20, 95.0),
        _row(30, 3.0),
        _row(40, 50.0),
    ])
    assert len(laps) == 2
    assert laps[0].lap_number == 1
    assert laps[1].lap_number == 2


def test_build_delta_trace_interpolates_elapsed_time() -> None:
    laps = build_lap_series([
        _row(0, 0.0),
        _row(10, 50.0),
        _row(20, 100.0),
    ])
    faster = build_lap_series([
        _row(0, 0.0),
        _row(8, 50.0),
        _row(16, 100.0),
    ])
    points = build_delta_trace(laps[0], faster[0], 50.0)
    assert points[1]["delta_s"] == -2.0


def test_build_segment_stats_reports_duration_and_speed() -> None:
    lap = build_lap_series([
        _row(0, 0.0, speed=40.0),
        _row(5, 25.0, speed=60.0),
        _row(10, 50.0, speed=80.0),
        _row(15, 75.0, speed=100.0),
    ])[0]
    stats = build_segment_stats(lap, [{"name": "sector-1", "s_start_m": 25.0, "s_end_m": 75.0}])
    assert stats[0]["duration_s"] == 10.0
    assert stats[0]["entry_speed_kph"] == 60.0
    assert stats[0]["exit_speed_kph"] == 100.0
