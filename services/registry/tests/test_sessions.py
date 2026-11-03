from datetime import UTC, datetime, timedelta

from app.services.sessions import derive_state

NOW = datetime(2026, 11, 3, 18, 0, tzinfo=UTC)


def state(**kw):
    args = dict(ended_at=None, frames_expected=None, frames_received=0, started_at=None,
                last_frame_received_at=None, now=NOW)
    args.update(kw)
    return derive_state(**args)


def test_ended_with_every_frame_is_complete() -> None:
    s = state(ended_at=NOW, frames_expected=28, frames_received=28)
    assert (s.state, s.completeness_pct) == ("complete", 100.0)


def test_ended_with_frames_missing_is_incomplete_with_how_much_arrived() -> None:
    s = state(ended_at=NOW, frames_expected=200, frames_received=197)
    assert (s.state, s.completeness_pct) == ("incomplete", 98.5)


def test_redelivered_frames_never_push_completeness_over_100() -> None:
    assert state(ended_at=NOW, frames_expected=10, frames_received=12).completeness_pct == 100.0


def test_no_end_but_frames_recent_is_open() -> None:
    s = state(started_at=NOW - timedelta(hours=2), last_frame_received_at=NOW - timedelta(seconds=5))
    assert (s.state, s.completeness_pct) == ("open", None)


def test_no_end_and_silent_for_30_minutes_is_unterminated() -> None:
    s = state(started_at=NOW - timedelta(hours=2), last_frame_received_at=NOW - timedelta(minutes=31))
    assert s.state == "unterminated"


def test_session_whose_start_was_lost_still_tracks_by_frames() -> None:
    assert state(last_frame_received_at=NOW - timedelta(seconds=2)).state == "open"
