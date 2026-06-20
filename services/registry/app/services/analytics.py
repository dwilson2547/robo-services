from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from statistics import mean
from typing import Iterable

import psycopg2
from psycopg2.extras import RealDictCursor


@dataclass(frozen=True)
class TrackPositionRow:
    captured_at: datetime
    device_id: str
    source_session: str
    track_id: int
    track_name: str
    track_length_m: float | None
    s_m: float
    ground_speed_kph: float | None


@dataclass(frozen=True)
class LapSample:
    captured_at: datetime
    s_m: float
    elapsed_s: float
    ground_speed_kph: float | None


@dataclass(frozen=True)
class LapSeries:
    lap_number: int
    track_id: int
    track_name: str
    track_length_m: float
    started_at: datetime
    ended_at: datetime
    samples: tuple[LapSample, ...]

    @property
    def duration_s(self) -> float:
        return self.samples[-1].elapsed_s if self.samples else 0.0


def fetch_session_summaries(database_url: str, limit: int = 100) -> list[dict]:
    query = """
        SELECT
            source_session,
            device_id,
            track_id,
            max(track_name) AS track_name,
            max(track_length_m) AS track_length_m,
            min(captured_at) AS started_at,
            max(captured_at) AS ended_at,
            count(*) AS sample_count
        FROM telemetry.track_position_samples
        GROUP BY source_session, device_id, track_id
        ORDER BY max(captured_at) DESC
        LIMIT %s
    """
    with psycopg2.connect(database_url) as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(query, (limit,))
        return list(cur.fetchall())


def fetch_session_rows(database_url: str, source_session: str) -> list[TrackPositionRow]:
    query = """
        SELECT
            captured_at,
            device_id,
            source_session,
            track_id,
            track_name,
            track_length_m,
            s_m,
            ground_speed_kph
        FROM telemetry.track_position_samples
        WHERE source_session = %s
        ORDER BY captured_at ASC
    """
    with psycopg2.connect(database_url) as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
        cur.execute(query, (source_session,))
        rows = cur.fetchall()
    return [
        TrackPositionRow(
            captured_at=row["captured_at"],
            device_id=row["device_id"],
            source_session=row["source_session"],
            track_id=row["track_id"],
            track_name=row["track_name"],
            track_length_m=row["track_length_m"],
            s_m=float(row["s_m"]),
            ground_speed_kph=float(row["ground_speed_kph"]) if row["ground_speed_kph"] is not None else None,
        )
        for row in rows
    ]


def build_lap_series(rows: Iterable[TrackPositionRow]) -> list[LapSeries]:
    ordered = list(rows)
    if not ordered:
        return []
    inferred_track_length = max((row.track_length_m or row.s_m) for row in ordered)
    wrap_threshold = max(inferred_track_length * 0.4, 50.0)

    laps: list[LapSeries] = []
    lap_number = 1
    lap_rows: list[TrackPositionRow] = [ordered[0]]
    previous = ordered[0]

    for row in ordered[1:]:
        if previous.s_m - row.s_m > wrap_threshold:
            laps.append(_to_lap_series(lap_number, lap_rows, inferred_track_length))
            lap_number += 1
            lap_rows = [row]
        else:
            lap_rows.append(row)
        previous = row

    if lap_rows:
        laps.append(_to_lap_series(lap_number, lap_rows, inferred_track_length))
    return laps


def _to_lap_series(lap_number: int, rows: list[TrackPositionRow], inferred_track_length: float) -> LapSeries:
    start = rows[0].captured_at
    samples = tuple(
        LapSample(
            captured_at=row.captured_at,
            s_m=row.s_m,
            elapsed_s=(row.captured_at - start).total_seconds(),
            ground_speed_kph=row.ground_speed_kph,
        )
        for row in rows
    )
    return LapSeries(
        lap_number=lap_number,
        track_id=rows[0].track_id,
        track_name=rows[0].track_name,
        track_length_m=rows[0].track_length_m or inferred_track_length,
        started_at=rows[0].captured_at,
        ended_at=rows[-1].captured_at,
        samples=samples,
    )


def interpolate_elapsed(samples: tuple[LapSample, ...], target_s_m: float) -> float | None:
    if not samples:
        return None
    if target_s_m < samples[0].s_m or target_s_m > samples[-1].s_m:
        return None
    for left, right in zip(samples, samples[1:]):
        if left.s_m <= target_s_m <= right.s_m:
            if right.s_m == left.s_m:
                return right.elapsed_s
            ratio = (target_s_m - left.s_m) / (right.s_m - left.s_m)
            return left.elapsed_s + ratio * (right.elapsed_s - left.elapsed_s)
    if target_s_m == samples[-1].s_m:
        return samples[-1].elapsed_s
    return None


def interpolate_speed(samples: tuple[LapSample, ...], target_s_m: float) -> float | None:
    if not samples:
        return None
    if target_s_m <= samples[0].s_m:
        return samples[0].ground_speed_kph
    if target_s_m >= samples[-1].s_m:
        return samples[-1].ground_speed_kph
    for left, right in zip(samples, samples[1:]):
        if left.s_m <= target_s_m <= right.s_m:
            if left.ground_speed_kph is None:
                return right.ground_speed_kph
            if right.ground_speed_kph is None:
                return left.ground_speed_kph
            if right.s_m == left.s_m:
                return right.ground_speed_kph
            ratio = (target_s_m - left.s_m) / (right.s_m - left.s_m)
            return left.ground_speed_kph + ratio * (right.ground_speed_kph - left.ground_speed_kph)
    return None


def build_delta_trace(reference: LapSeries, comparison: LapSeries, step_m: float) -> list[dict]:
    max_s = min(reference.samples[-1].s_m, comparison.samples[-1].s_m)
    points: list[dict] = []
    current = 0.0
    while current <= max_s:
        ref_elapsed = interpolate_elapsed(reference.samples, current)
        cmp_elapsed = interpolate_elapsed(comparison.samples, current)
        if ref_elapsed is not None and cmp_elapsed is not None:
            points.append(
                {
                    "s_m": round(current, 3),
                    "reference_elapsed_s": round(ref_elapsed, 3),
                    "comparison_elapsed_s": round(cmp_elapsed, 3),
                    "delta_s": round(cmp_elapsed - ref_elapsed, 3),
                }
            )
        current += step_m
    return points


def build_segment_stats(lap: LapSeries, segments: list[dict]) -> list[dict]:
    results: list[dict] = []
    for segment in segments:
        s_start = float(segment["s_start_m"])
        s_end = float(segment["s_end_m"])
        if s_end <= s_start:
            continue
        t_start = interpolate_elapsed(lap.samples, s_start)
        t_end = interpolate_elapsed(lap.samples, s_end)
        if t_start is None or t_end is None:
            continue
        speeds = [
            sample.ground_speed_kph
            for sample in lap.samples
            if sample.ground_speed_kph is not None and s_start <= sample.s_m <= s_end
        ]
        entry_speed = interpolate_speed(lap.samples, s_start)
        exit_speed = interpolate_speed(lap.samples, s_end)
        result = {
            "name": segment["name"],
            "s_start_m": s_start,
            "s_end_m": s_end,
            "duration_s": round(t_end - t_start, 3),
            "entry_speed_kph": round(entry_speed, 3) if entry_speed is not None else None,
            "exit_speed_kph": round(exit_speed, 3) if exit_speed is not None else None,
            "min_speed_kph": round(min(speeds), 3) if speeds else None,
            "mean_speed_kph": round(mean(speeds), 3) if speeds else None,
            "max_speed_kph": round(max(speeds), 3) if speeds else None,
        }
        results.append(result)
    return results
