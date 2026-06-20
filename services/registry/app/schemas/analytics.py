from datetime import datetime

from pydantic import BaseModel, Field


class SessionSummaryOut(BaseModel):
    source_session: str
    device_id: str
    track_id: int
    track_name: str
    track_length_m: float | None
    started_at: datetime
    ended_at: datetime
    sample_count: int


class LapSummaryOut(BaseModel):
    lap_number: int
    track_id: int
    track_name: str
    track_length_m: float
    started_at: datetime
    ended_at: datetime
    duration_s: float
    sample_count: int


class DeltaTimeRequest(BaseModel):
    reference_session: str
    comparison_session: str
    reference_lap_number: int = Field(default=1, ge=1)
    comparison_lap_number: int = Field(default=1, ge=1)
    step_m: float = Field(default=10.0, gt=0)


class DeltaTimePointOut(BaseModel):
    s_m: float
    reference_elapsed_s: float
    comparison_elapsed_s: float
    delta_s: float


class DeltaTimeResponse(BaseModel):
    track_id: int
    track_name: str
    track_length_m: float
    reference_session: str
    comparison_session: str
    reference_lap_number: int
    comparison_lap_number: int
    reference_duration_s: float
    comparison_duration_s: float
    delta_points: list[DeltaTimePointOut]


class SegmentDefinition(BaseModel):
    name: str
    s_start_m: float = Field(gt=0)
    s_end_m: float = Field(gt=0)


class SegmentStatsRequest(BaseModel):
    source_session: str
    lap_number: int = Field(default=1, ge=1)
    segments: list[SegmentDefinition]


class SegmentStatsOut(BaseModel):
    name: str
    s_start_m: float
    s_end_m: float
    duration_s: float
    entry_speed_kph: float | None
    exit_speed_kph: float | None
    min_speed_kph: float | None
    mean_speed_kph: float | None
    max_speed_kph: float | None


class SegmentStatsResponse(BaseModel):
    source_session: str
    lap_number: int
    track_id: int
    track_name: str
    track_length_m: float
    segment_stats: list[SegmentStatsOut]
