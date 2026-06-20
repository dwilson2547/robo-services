# track-position-flink-job

Flink job that consumes `telemetry.raw.gps`, matches each sample to the nearest
registry track whose geometry is already a `LineString`/`MultiLineString`, and
publishes a derived per-sample record with track-relative `s`.

This is the first step toward the `pipelines.md` keystone invariant. It is
deliberately **GPS-only** for now, so downstream interpolation of IMU/CAN onto
`s` can happen in a later job.

## Current scope

- input: `telemetry.raw.gps`
- output: `telemetry.derived.track_position`
- track source: robo-registry `/api/tracks`
- supported track geometry: `LineString`, `MultiLineString`, or those wrapped in
  a GeoJSON `Feature` / `FeatureCollection`
- skipped for now: polygon-only tracks, Timescale writes, IMU/CAN interpolation

## Why this order

The repo already has raw GPS ingress and downstream Flink deployment patterns.
What it does **not** yet have is a persistent analytical sink or a complete
centerline model for every track. This job lets us start computing `s` anywhere
the registry already holds a usable polyline, while making the remaining
geometry gap explicit instead of hiding it.

