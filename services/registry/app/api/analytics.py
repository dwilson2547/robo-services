from fastapi import APIRouter, HTTPException

from app.config import settings
from app.schemas.analytics import (
    DeltaTimeRequest,
    DeltaTimeResponse,
    LapSummaryOut,
    SegmentStatsRequest,
    SegmentStatsResponse,
    SessionSummaryOut,
)
from app.services.analytics import (
    build_delta_trace,
    build_lap_series,
    build_segment_stats,
    fetch_session_rows,
    fetch_session_summaries,
)

router = APIRouter(prefix="/analytics", tags=["analytics"])


def _require_timescale_url() -> str:
    if not settings.timescale_database_url:
        raise HTTPException(status_code=503, detail="Timescale analytics database is not configured")
    return settings.timescale_database_url


@router.get("/sessions", response_model=list[SessionSummaryOut])
def list_sessions(limit: int = 100):
    database_url = _require_timescale_url()
    return fetch_session_summaries(database_url, limit=limit)


@router.get("/sessions/{source_session}/laps", response_model=list[LapSummaryOut])
def list_session_laps(source_session: str):
    database_url = _require_timescale_url()
    laps = build_lap_series(fetch_session_rows(database_url, source_session))
    if not laps:
        raise HTTPException(status_code=404, detail="Session not found")
    return [
        LapSummaryOut(
            lap_number=lap.lap_number,
            track_id=lap.track_id,
            track_name=lap.track_name,
            track_length_m=lap.track_length_m,
            started_at=lap.started_at,
            ended_at=lap.ended_at,
            duration_s=round(lap.duration_s, 3),
            sample_count=len(lap.samples),
        )
        for lap in laps
    ]


@router.post("/delta-time", response_model=DeltaTimeResponse)
def delta_time(body: DeltaTimeRequest):
    database_url = _require_timescale_url()
    reference_laps = build_lap_series(fetch_session_rows(database_url, body.reference_session))
    comparison_laps = build_lap_series(fetch_session_rows(database_url, body.comparison_session))
    if len(reference_laps) < body.reference_lap_number or len(comparison_laps) < body.comparison_lap_number:
        raise HTTPException(status_code=404, detail="Requested lap number not found in one or both sessions")
    reference = reference_laps[body.reference_lap_number - 1]
    comparison = comparison_laps[body.comparison_lap_number - 1]
    if reference.track_id != comparison.track_id:
        raise HTTPException(status_code=400, detail="Sessions are on different tracks")
    return DeltaTimeResponse(
        track_id=reference.track_id,
        track_name=reference.track_name,
        track_length_m=reference.track_length_m,
        reference_session=body.reference_session,
        comparison_session=body.comparison_session,
        reference_lap_number=body.reference_lap_number,
        comparison_lap_number=body.comparison_lap_number,
        reference_duration_s=round(reference.duration_s, 3),
        comparison_duration_s=round(comparison.duration_s, 3),
        delta_points=build_delta_trace(reference, comparison, body.step_m),
    )


@router.post("/segment-stats", response_model=SegmentStatsResponse)
def segment_stats(body: SegmentStatsRequest):
    database_url = _require_timescale_url()
    laps = build_lap_series(fetch_session_rows(database_url, body.source_session))
    if len(laps) < body.lap_number:
        raise HTTPException(status_code=404, detail="Requested lap number not found")
    lap = laps[body.lap_number - 1]
    results = build_segment_stats(lap, [segment.model_dump() for segment in body.segments])
    return SegmentStatsResponse(
        source_session=body.source_session,
        lap_number=body.lap_number,
        track_id=lap.track_id,
        track_name=lap.track_name,
        track_length_m=lap.track_length_m,
        segment_stats=results,
    )
