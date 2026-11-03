# Trip pipelines: vehicle state snapshots and the drive summary

Status: **proposed** (2026-11-03). Comes before track selection (step 5 of
[recording sessions](2026-11-03-recording-sessions.md)): there is no track nearby
to test racing against, and every drive exercises a trip pipeline.

Two pieces, in order:

1. **Vehicle state snapshots**: one row per session per interval (1 s for phone
   captures; the race loggers will need 10–20 Hz) holding the vehicle's state:
   CAN signals, GPS, IMU. This is the base the racing pipeline needs too: lap
   splits, corner analysis and comparisons all read state over time.
2. **Drive summary**: computed from a session's snapshots once the session has
   settled, stored on the registry session, logged, and later shown in the app's
   history tab.

## What exists today (✅ verified 2026-11-03)

- kreceiver explodes each 1 s `TelemetryFrame` into one record per sample:
  GPS fixes on `telemetry.raw.gps`, IMU samples on `telemetry.raw.imu`, CAN
  frames on `telemetry.raw.can`, each with `captured_at` = frame base + the
  sample's offset. Records carry `source_session` and `device_id` but **not**
  the frame's `base_timestamp_us`. After the samples it publishes one frame
  receipt on `telemetry.session` with the frame's counts: `{base_timestamp_us,
  gps, imu, imu_missing, can}`.
- can-decode turns every raw CAN record into exactly one record on either
  `telemetry.decoded.can` (`payload.message`, `payload.signals`, `payload.dbc`)
  or `telemetry.unknown.can`, so decoded + unknown per frame = the receipt's
  `can`.
- All 758 frames recorded so far start on a whole second (`base_timestamp_us %
  1e6 = 0`). Per frame: 0–3 GPS fixes (some seconds have none), 50 IMU samples,
  ~530 CAN frames on average (max 737) with the Impala's 23-id filter.
- The Impala DBC (`chevy_impala_2008_hscan`, 23 messages) decodes:
  `VehicleSpeed` (mph), four wheel speeds (km/h), `EngineRPM`,
  `AcceleratorPedalPos` (%), `BrakePedalPressed`, `BrakePressure` (kPa),
  `BrakeApplied`, `CurrentGear`, `PRNDL`, `SteeringWheelAngle` (deg),
  `Odometer` (km), coolant / intake / outside / oil temperatures, oil pressure,
  three injector pulse widths (µs), fuel rail pressures, two TPMS pressures,
  cruise state and set speed, doors and seat belts, power mode, park brake, and
  the VIN in two parts.
- TimescaleDB runs at `timescaledb.timescaledb.svc.cluster.local` (database
  `postgres`, schema `telemetry`), written today by the track-position sink and
  read by the registry (`TIMESCALE_DATABASE_URL`).
- The registry derives each session's state on read: `open`, `unterminated` (no
  frame for 30 min, no end), `complete`, `incomplete`.

## 1. Frame membership on every sample (kreceiver)

Add `base_timestamp_us` to the metadata every sample record carries (next to
`frame_version`). can-decode rebuilds the payload from a fixed set of fields
(`can_id`, `data_hex`, `dt_us`), so it has to pass the new field through too,
on both decoded and unknown records. Frame bases are whole seconds today, so the frame could be inferred by
flooring `captured_at`. That only holds while the phone keeps building frames
that way, though, and the race loggers may not; an explicit field costs nothing.

## 2. Signal roles (registry, per DBC)

Summaries and the racing pipeline need "speed", "RPM" and "throttle" whatever a
vehicle's DBC calls them, in fixed units. Each registry DBC gets a `roles`
mapping, edited with the DBC:

```json
{
  "speed_kph":      {"signal": "VehicleSpeed", "scale": 1.609344},
  "rpm":            {"signal": "EngineRPM"},
  "throttle_pct":   {"signal": "AcceleratorPedalPos"},
  "brake_on":       {"signal": "BrakePedalPressed"},
  "brake_kpa":      {"signal": "BrakePressure"},
  "gear":           {"signal": "CurrentGear"},
  "steering_deg":   {"signal": "SteeringWheelAngle"},
  "odometer_km":    {"signal": "Odometer"},
  "coolant_c":      {"signal": "EngineCoolantTemp"},
  "oil_temp_c":     {"signal": "EngineOilTemp", "unit": "degF"}
}
```

A role names a decoded signal plus an optional `scale` / `offset`, or a `unit`
the service knows how to convert. Roles a DBC doesn't map stay empty, and
everything downstream treats an empty role as "not available". It is not an
error. The role set is fixed in code and grows when a consumer needs a new role.
Migration `0008` adds `dbcs.roles` (JSONB), and the Impala mapping is entered
through the API.

## 3. Vehicle state service (`services/vehicle_state`)

A Python consumer, built the same way as can-decode and session-tracker:
committed-offset consumer groups and the consumer watchdog.

**Inputs:** `telemetry.decoded.can`, `telemetry.unknown.can` (counted only),
`telemetry.raw.gps`, `telemetry.raw.imu`, and `telemetry.session` (frame
receipts and `session_end`).

**Grouping:** by `(source_session, device_id, base_timestamp_us)`, i.e. one
frame. A frame is **complete** when its receipt has arrived and every count
matches it: GPS, IMU, and decoded + unknown CAN. A frame that is still short 15 s
after its receipt is written anyway, with `complete = false`. Within a frame,
samples fall into intervals of `STATE_INTERVAL_MS` (default 1000, so one row per
frame). At 100 ms the same frame gives ten rows; that is the race-logger setting.

**One row per interval** in Timescale `telemetry.vehicle_state` (a hypertable on
`t`, primary key `(source_session, device_id, t)`):

| Column | Content |
|---|---|
| `t` | interval start |
| `source_session`, `device_id`, `vehicle_id`, `user_sub` | from the session (registry) |
| role columns: `speed_kph`, `rpm`, `throttle_pct`, `brake_on`, `brake_kpa`, `gear`, `steering_deg`, `odometer_km`, `coolant_c`, `oil_temp_c` | last value in the interval, through the DBC's roles; NULL when unmapped |
| `lat`, `lon`, `alt_m`, `gps_speed_kph`, `heading_deg`, `num_sats`, `hdop`, `gps_fixes` | last fix in the interval, and how many fixes there were |
| `accel_mean`, `accel_min`, `accel_max`, `gyro_mean`, `gyro_min`, `gyro_max` | per axis (JSONB `{x, y, z}`), over the interval's IMU samples |
| `imu_samples`, `imu_filled` | samples, and how many were filled grid points (`ImuSection.missing`) |
| `signals` | JSONB: every decoded signal's state at the interval end, `{name: value}` |
| `signal_stats` | JSONB: per numeric signal seen in the interval, `[min, max, mean, count]` |
| `carried` | JSONB: signals not seen in this interval but carried forward from earlier in the session, with their age in ms (slow messages such as TPMS and the VIN broadcast less than once a second) |
| `dbc`, `complete` | the DBC the CAN was decoded with; whether every sample of the frame arrived |

The service does not publish snapshots to Iggy yet. Nothing would read them
live; a `telemetry.state` topic gets added when a live consumer exists (a
dashboard, or the racing pipeline).

**Restart safety** without Flink checkpoints:

- Writes are upserts on the primary key, so writing a frame twice gives the same
  row.
- A frame that was already written is skipped if its samples are replayed, so a
  restart can never overwrite a full row with a partial one.
- Each topic's offset is committed only up to the first message of the oldest
  frame still unwritten. A crash replays at most the open frames.

**Why Python rather than a Flink job:** the join is per frame, and the receipt
says when a frame is whole. That leaves no open-ended event-time windowing and no
watermarks across topics, which is the part Flink would earn its keep on.
Idempotent writes plus skipping written frames make restarts safe without
checkpoints. The existing Flink jobs all run without watermarks anyway. Revisit
if racing needs joins that span frames.

**Volume (estimate):** one row per second of driving, a few kB each with the
JSONB columns, so roughly 10 MB per hour of driving. Rows are kept indefinitely,
like the raw parquet archive.

## 4. Drive summary (`services/drive_summary`)

A worker that looks once a minute for sessions that are settled and not yet
summarised:

- **Settled** means: registry state `complete`, `incomplete` or `unterminated`,
  every received frame has a snapshot row, and no new row for 2 min.
- If the session gains frames after its summary was written (late delivery),
  it is recomputed. `frames_used` is stored with the summary, so the worker can
  tell.

It reads the session's `vehicle_state` rows and writes a registry
`session_summaries` row (`session_pk`, `version`, `frames_used`, `metrics`
JSONB, `computed_at`; migration `0009`). It logs one line per summary and
exposes it as `GET /api/sessions/{device}/{session}/summary` and
`GET /api/app/sessions/{session}/summary` (scoped to the caller).

**Metrics, version 1:**

| Metric | Definition |
|---|---|
| duration, moving time, idle time | first to last row; rows with `speed_kph > 1`; rows with speed ≤ 1 and `rpm > 0` |
| distance: GPS / CAN / odometer | haversine sum over consecutive fixes (skipping gaps longer than 5 s), the headline figure; sum of `speed_kph × interval`; last − first `odometer_km` (whole km on the Impala). All three are reported (drive 1 findings) |
| speed | average (moving time only) and maximum, CAN; GPS maximum alongside |
| engine | max RPM, time above 4000 RPM, mean RPM while moving |
| gears | time in each `gear` value while `prndl` says Drive, time in P / R / N; number of shifts (needs a `prndl` role, drive 1 findings) |
| brakes | brake applications (rising edges of `brake_on`), maximum `brake_kpa` |
| hard events | hard braking / hard acceleration: longitudinal acceleration from the change in CAN speed between intervals, beyond −0.35 g / +0.30 g. Sharp cornering: lateral acceleration = speed × yaw rate (gyro), beyond 0.40 g (drive 1 findings: the IMU's linear acceleration is not reliable for this). Thresholds are ⚠ starting values, to tune on real drives |
| temperatures | coolant and oil: start, max, end |
| data quality | completeness %, frames short, IMU points filled, intervals without a GPS fix, roles unavailable for this vehicle |

The dongle's IMU orientation in the car is unknown and differs with every
install. So longitudinal acceleration comes from the CAN speed, which needs no
orientation. Cornering uses the gyro's rotation rate, which is mostly yaw in a
car, times the speed. This also needs no orientation, and it avoids the linear
acceleration channel (drive 1 findings).

**Fuel** (from injector pulse width × RPM × cylinder count × injector flow rate)
waits for the injector flow rate. The role names are reserved
(`injector_pw_us`, `cylinders`) so that adding it later doesn't change the
schema.

## Drive 1 findings (session 1793745445702, ✅ measured 2026-11-03)

- **Distance:** GPS 7.50 km (haversine over 752 fixes, no gap over 3 s), CAN
  7.81 km, odometer 8 km. The Impala's `Odometer` signal counts whole kilometres
  only (225405 → 225413), so it gives a ±1 km check at best.
- **CAN speed reads high:** 2.75 km/h above GPS on average above 10 km/h (mean
  absolute difference 3.28 km/h), about 5.6 % at the drive's 48.7 km/h moving
  average. That is in line with a speedometer over-reading. Scaled down by that
  amount, CAN distance (7.40 km) agrees with GPS. So the summary reports GPS
  distance first. A per-vehicle CAN speed correction, learned from GPS over each
  drive, is a later refinement.
- **Gear:** `CurrentGear` is 1–4 in Drive (4-speed automatic), 14 in Reverse and
  15 in Park; `PRNDL` is 4, 2 and 1 for those. Neutral was not seen. The summary
  needs a `prndl` role, and counts gears only while in Drive.
- **IMU:** the dongle's BNO085 reports linear acceleration with gravity already
  removed (firmware `enableLinearAccelerometer`; 0.03 m/s² while stopped). That
  makes the plan's gravity estimate unnecessary.
  - Gyro yaw lines up with the GPS heading rate (correlation −0.64, peak at
    0 to −1 s lag), so the IMU's timing is right.
  - The 1 s means of linear acceleration track CAN-derived acceleration poorly:
    correlation 0.17 on the best axis, 0.38 for the magnitude at its best lag,
    and half the spread. ⚠ Plausible cause, unverified: the BNO's fusion has no
    GPS aiding, so it absorbs sustained acceleration into its tilt estimate.
  - So for the summary, braking and acceleration come from CAN speed (as
    planned), and cornering is lateral acceleration = speed × yaw rate (gyro),
    not the IMU's acceleration.
- **Other:** 19 brake applications; coolant 66 → 95 °C; maximum 2,773 RPM;
  maximum speed 81.7 km/h CAN, 78.0 km/h GPS.

## Build order

1. kreceiver `base_timestamp_us` on samples, passed through by can-decode; registry `dbcs.roles` (0008) and
   the Impala's roles. Verify: a capture's sample records carry their frame;
   `GET /api/dbcs/1` returns the roles.
   ✅ Done 2026-11-03 (robo-services dcab94c: registry `20261103-roles-v1` at 0008,
   kreceiver `20261103-proto-v7`, can-decode `20261103-v6`; Impala roles entered).
   Verified on session 1793743684361 (40 frames): all 44 GPS, 1,950 IMU, 28,384 raw
   and 28,384 decoded CAN records carried their frame, and every frame's counts
   matched its receipt (decoded + unknown CAN = raw; no unknown CAN with the
   Impala filter).
2. Vehicle state service and the `telemetry.vehicle_state` table. Verify on a
   short drive: one row per received frame, `complete` on all but the frames
   the receipts say were short, and a few rows checked by hand against the raw
   records (speed, RPM, a GPS fix, IMU means).
   Built and deployed 2026-11-03 (vehicle-state `20261103-v3`, robo-services c6fa86e;
   reads each topic at its own saved position in `telemetry.vehicle_state_offsets`
   rather than through consumer groups, see the service README). Backfill verified on
   session 1793743684361: 40 rows for 40 frames, all `complete`, vehicle 1 and the
   account set, DBC `chevy_impala_2008_hscan`; RPM, speed (mph→km/h), oil temperature
   (°F→°C), the last GPS fix, GPS and IMU counts, IMU x mean and the full signal state
   matched a recompute from the raw topics on every row.
   ✅ Live drive verified 2026-11-03 (session 1793745445702, 12.5 min around a 1-mile
   block): 756 frames → 756 rows, all `complete`, written live; every row matched the
   raw-topic recompute. Findings that change step 3 are under "Drive 1 findings".
3. Drive summary, `session_summaries` (0009) and the two endpoints. Verify on a
   drive with a known route: GPS and odometer distance against the route's
   length, the stops counted, the brake applications plausible.
4. App: after Stop, show the session's summary once it exists. This is the
   first piece of the history tab.

## Later

- Snapshot rate per session mode (1 s for trip, 100 ms for race with the
  10–20 Hz loggers), chosen at `session_start`.
- A `telemetry.state` topic once something consumes snapshots live.
- Self-registering pipelines: vehicle-state and drive-summary are the first
  candidates to register themselves (name, inputs, outputs, settings) when that
  catalogue is designed.
- Fuel economy once the injector flow rate is known.
- Trip mode in the app (trip-specific screen, no start-line anchor).

## Open questions

- Hard-event thresholds: starting values above; tune after a few drives.
- Should `incomplete` sessions get a summary marked with their completeness, or
  wait for a retry? Proposed: summarise them, with the completeness shown in
  the quality block.
