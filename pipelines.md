# Race Logger — Data Pipeline & Metrics Implementation Handoff

This document is the build brief for the backend telemetry pipeline and the
driver-facing metrics layer. It is written to be consumed directly by an agentic
coding assistant. Build the pieces in the order given; each section names its
inputs, outputs, and the keystone invariant that everything downstream depends on.

---

## 0. The Keystone Invariant (read this first)

**Every telemetry sample carries an `s`-coordinate: arc-length distance along the
track polyline, computed at Flink ingest, stored as an indexed column.**

`s` is the single decision that turns "a pile of timestamped samples" into a
"queryable track-relative dataset." Do **not** defer it and snap GPS to the
polyline at query time — that re-runs the expensive geometry on every analysis
query. Compute it once, during ingest, in the Flink job that already touches
every sample.

Once `s` exists and `(lap_id, s)` is indexed, every driver metric in Section 3
collapses into a cheap indexed range query. If you implement nothing else
correctly, implement this.

---

## 1. Storage Architecture

```
SD card (device, source of truth)
    │
    ├──► MinIO / AIStore  ── raw immutable Parquet archive
    │        (partitioned by session, lap_id as filterable column)
    │        NEVER deleted — this is the replay/reprocess source of truth
    │
    └──► MQTT (flespi broker, best-effort streaming path)
             │
             ▼
        Apache Flink  ── enrich + compute s-coordinate
             │
             ├──► TimescaleDB        (telemetry hypertable, indexed query layer)
             └──► Postgres / PostGIS (tracks, laps, segments — relational metadata)
```

### Layer responsibilities

**MinIO / AIStore — raw immutable archive.** Parquet, partitioned at session
level, `lap_id` as a filterable column. This is the immutable source of truth you
can always replay/reprocess from. Never throw raw away. (AIStore on the small
TrueNAS node, legacy MinIO on Unraid — either is a valid sink; pick one as the
canonical archive.)

**TimescaleDB — indexed analytical layer.** Hypertable for telemetry,
time-partitioned, with `s` baked in as a column during Flink ingest. Index on
`(lap_id, s)` and time. Use continuous aggregates for per-segment/per-lap rollup
stats so the common "compare segment across laps" queries hit precomputed data.

**Postgres / PostGIS — relational metadata + derived layer.** `sessions`, `laps`,
`segments` tables. Track geometry in PostGIS (spatial index → "which track is
closest to this GPS coord" is trivial). PostGIS and Timescale coexist on the same
instance (different databases or schemas).

**Scale-out note:** ClickHouse is the migration path **only if** Timescale's query
performance is outgrown (many cars, large shared history, heavy analytical load).
Keep the Parquet archive and a small Postgres for metadata in that scenario. **Do
not start on ClickHouse** — Timescale is correct for where this project is now.

### MQTT publish contract

- Batched intervals of **500ms–1s**.
- **Per-sample capture-time timestamps preserved inside each batch.** Publish
  frequency is decoupled from data fidelity — never collapse multiple samples to
  the batch time.
- SD card is the source of truth; MQTT is best-effort. The pipeline must be
  resilient to dropped/duplicated batches (idempotent ingest keyed on
  device + capture timestamp).

---

## 2. Flink Ingest Job

Inputs: raw MQTT sample batches (and/or Parquet replay from the archive).
Output: enriched samples written to TimescaleDB; lap/segment metadata to Postgres.

Responsibilities, in order:

1. **Unbatch & preserve timestamps.** Expand each MQTT batch into individual
   samples, each retaining its capture-time timestamp.
2. **Snap to polyline → compute `s`.** For each GPS fix, snap to the active
   track polyline and compute arc-length `s`. Interpolate `s` for higher-rate
   non-GPS channels (IMU, wheel speed) between GPS fixes using their capture
   timestamps. This is the keystone step.
3. **Assign `lap_id`.** Use the existing lap-tracking / staging-button workflow.
   A lap boundary is a crossing of the start/finish `s` wrap.
4. **Write telemetry** to the Timescale hypertable, indexed on `(lap_id, s)` and
   time.
5. **Maintain metadata** in Postgres (`sessions`, `laps`, `segments`).

The track polyline comes from the existing OSM extraction pipeline (works for
simple circuits; complex multi-config venues like Indianapolis still need work —
not a blocker for v1).

---

## 3. Driver Metrics Layer

Everything below is a query against `(lap_id, s)`. Build in priority order.

### Tier 1 — the backbone (build first)

**Delta-time vs. reference.** The number drivers stare at. Pick a reference lap
(best lap, or a chosen baseline). For each `s`, show cumulative time
gained/lost vs. the reference. Falls directly out of `s` + lap tracking; almost
free once `s` exists.

**Segment / sector stats.** For any `[s_start, s_end]`: time-in-segment,
min/mean/max speed, entry speed, exit speed — ranked across all laps.
User-defined segments are just stored `s`-ranges in the `segments` table.
"Compare this segment across laps" is a filtered delta-time computation. **This
is the differentiator versus a basic lap timer.** Back the rollups with Timescale
continuous aggregates.

### Tier 2 — traction / dynamics (decide before finalizing capture rates)

This tier is where yaw + wheel speed + IMU earn their place, and it determines
whether those channels need to exceed 10Hz. Decide up front whether you want it.

**Understeer / oversteer balance.** Compare *expected* yaw rate (from speed +
steering angle via a bicycle model) against *measured* yaw rate:
- measured < expected → understeer (plowing)
- measured > expected → oversteer (rotating / loose)

Plot per-`s`; a driver instantly sees "I'm understeering into turn 4." Requires
the steering-angle signal — mapped as `0x1E5` (PSCM) on the Impala. Without
steering angle, infer balance more crudely from yaw-vs-lateral-accel.

**Wheel slip / traction events.** Driven-wheel speed vs. undriven-wheel speed
(or vs. GPS ground speed). Divergence = wheelspin under power or lockup under
braking. Attribute to `s` → a "traction map" of where the car breaks loose. This
is the sub-100ms behaviour that wants wheel speed **faster than 10Hz** (wheel
speed IDs `0x348` / `0x34A`).

**g-g diagram.** Lateral vs. longitudinal acceleration scatter from the IMU —
how much of the tire grip envelope the driver is actually using. Color points by
track location via `s`. Cheap from data already captured.

**Friction circle / combined-grip.** Follows naturally from the g-g data. Shows
where time is being left on the table — e.g. never combining braking and
cornering (no trail-braking).

### Tier 3 — natural extensions (lower priority)

**Theoretical best lap.** Stitch together the best time in each segment — the "if
you put it all together" lap. Trivial once per-segment bests exist; hugely
motivating.

**Consistency metrics.** Stddev of segment times across a session. Separates
"fast once" from "fast repeatably."

**Driven-line visualization.** Color the track map by speed, or by delta vs.
reference, so the racing line literally shows where time is lost.

### Scope guardrail

**Resist building real-time driver feedback** (live coaching, predictive lap
time) until the post-session analysis above is solid. That is the trap that
swallows the interesting work.

---

## 4. Capture Rates (rate is dictated by the metric, not the other way around)

Principle: sample at ~2–3× the fastest event you want to resolve in that channel
(anti-aliasing), and no faster — over-sampling a slow channel wastes bus and
storage for no analytical gain.

| Channel | Rate | Feeds |
|---|---|---|
| GPS (`s`-source) | 10 Hz | lap timing, segment stats, delta-time, driven line |
| Vehicle speed | 10 Hz | lap/segment timing (integration of speed over distance) |
| RPM / AFR | 10 Hz | engine/tuning context |
| Temperatures | 1–2 Hz | slow-moving, no benefit to faster |
| IMU raw accel/gyro | 50 Hz | g-g, friction circle, balance |
| Wheel speeds | 50 Hz / native | traction events, slip (sub-100ms) |
| Yaw rate | 50 Hz / native | understeer/oversteer balance |

GPS at 10Hz is the honest sweet spot — most consumer modules (M9N included)
output 10Hz cleanly and degrade fix quality above it; at racing speeds 10Hz is a
fix every ~2.5–4m, fine for meter-scale segment boundaries after interpolation.

**BNO085 constraint:** it cannot run three fused reports at 50Hz simultaneously.
The validated allocation is raw accel/gyro at 50Hz + rotation vector at a reduced
rate. BNO085 is standardized across all logger tiers.

The Tier-2 decision feeds back here: if you commit to the traction/dynamics
layer, wheel speed and yaw must be at native bus rate, not 10Hz.

---

## 5. Build Order Summary

1. **Flink: compute and store `s`** on every sample, index `(lap_id, s)`. (Keystone.)
2. **Storage sinks:** Parquet archive + Timescale hypertable + Postgres metadata.
3. **Tier 1 metrics:** delta-time vs. reference, segment stats (with continuous aggregates).
4. **Decide Tier 2 scope** → set wheel-speed/yaw capture rates accordingly.
5. **Tier 2 metrics:** balance, wheel slip, g-g, friction circle.
6. **Tier 3 metrics:** theoretical best lap, consistency, driven-line viz.
7. Hold the line on the real-time-feedback guardrail until post-session is solid.