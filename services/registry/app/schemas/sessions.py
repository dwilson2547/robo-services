from datetime import datetime

from pydantic import BaseModel


class SessionOut(BaseModel):
    source_session: str
    device_id: str
    user_sub: str | None
    mode: str | None
    vehicle_id: int | None
    track_id: int | None
    started_at: datetime | None
    ended_at: datetime | None
    end_reason: str | None
    frames_expected: int | None
    frames_received: int
    first_frame_us: int | None
    last_frame_us: int | None
    last_frame_received_at: datetime | None
    state: str
    completeness_pct: float | None


class SessionDetailOut(SessionOut):
    start_event: dict | None
    end_event: dict | None


class SessionSummaryOut(BaseModel):
    source_session: str
    device_id: str
    version: int
    frames_used: int
    frames_received: int
    metrics: dict
    computed_at: datetime


class SummaryHeadline(BaseModel):
    """The few summary numbers a history list shows per session."""

    distance_km: float | None
    duration_s: float | None
    moving_s: float | None
    avg_moving_kph: float | None
    max_kph: float | None


class AppSessionOut(BaseModel):
    """One of the caller's sessions in the app's history list."""

    source_session: str
    device_id: str
    mode: str | None
    started_at: datetime | None
    ended_at: datetime | None
    state: str
    completeness_pct: float | None
    vehicle_id: int | None
    vehicle_name: str | None
    summary: SummaryHeadline | None


class TrackOut(BaseModel):
    """A session's route: one row per point, values in `columns` order."""

    source_session: str
    columns: list[str]
    rows: list[list[float | bool | str | None]]
