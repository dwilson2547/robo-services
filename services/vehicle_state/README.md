# vehicle_state

One row per recording session per interval (1 s by default) in Timescale
`telemetry.vehicle_state`: the vehicle's state from decoded CAN, GPS and IMU.
The drive summary and the racing pipeline read it. Design:
`docs/plans/2026-11-03-trip-pipelines.md` §3.

Inputs (one Iggy consumer group `vehicle-state` per topic, auto-commit off):
`telemetry.decoded.can`, `telemetry.unknown.can` (counted only),
`telemetry.raw.gps`, `telemetry.raw.imu`, and the frame receipts on
`telemetry.session`. Records need `payload.base_timestamp_us` (kreceiver
`20261103-proto-v7` and later); untagged ones are skipped.

How a frame is written:

- Samples are regrouped by `(source_session, device_id, base_timestamp_us)`.
- **complete** when the receipt's counts are all met (CAN = decoded + unknown).
- **incomplete** (`complete = false`) when a count is short but cannot change
  any more: that topic has a later frame of the session, is 30 s further on in
  event time for any device, or has had nothing to process for
  `VEHICLE_STATE_GRACE_S` (10 s).
- **empty** (a receipt and no samples) frames are dropped.
- A session's frames are written in time order. The vehicle, account and the
  DBC's signal roles come from the registry; the service waits up to 30 s for a
  session the registry does not know yet, then writes without them.

Restarts: rows are upserts on `(source_session, device_id, t)`; offsets are
stored per topic only below the oldest frame not yet written, so a restart
replays exactly the unwritten frames; frames already in the table are skipped
when replayed, and the carry-forward state is restored from the session's last
row. A new consumer group starts at the beginning of each topic, which only
yields rows for sessions recorded with tagged samples. The consumer watchdog
restarts the pod if a consumer stops after an Iggy restart.

Columns: role columns (`speed_kph`, `rpm`, `throttle_pct`, `brake_on`,
`brake_kpa`, `gear`, `steering_deg`, `odometer_km`, `coolant_c`, `oil_temp_c`;
NULL when the vehicle's DBC does not map the role), the last GPS fix and fix
count, IMU per-axis mean/min/max, `signals` (every signal's state at the
interval end), `signal_stats` (`[min, max, mean, count]` of signals seen in the
interval), `carried` (signals carried forward, age in ms), `dbc`, `complete`.

Config (env): `IGGY_CONNECTION_STRING`, `IGGY_STREAM`, `VEHICLE_STATE_GROUP`,
`VEHICLE_STATE_{GPS,IMU,CAN,UNKNOWN,SESSION}_TOPIC`, `REGISTRY_URL`,
`STATE_INTERVAL_MS` (must divide 1000), `VEHICLE_STATE_GRACE_S`, `TS_HOST`,
`TS_PORT`, `TS_NAME`, `TS_USER`, `TS_PASSWORD`, `TS_SCHEMA`, `TS_TABLE`,
`VEHICLE_STATE_LOG_LEVEL`.

Test (the store tests need a TimescaleDB):

```bash
docker run -d --name ts-test -p 55433:5432 -e POSTGRES_HOST_AUTH_METHOD=trust timescale/timescaledb:latest-pg16
TEST_TIMESCALE_HOST=127.0.0.1 TEST_TIMESCALE_PORT=55433 PYTHONPATH=.:../../src python -m pytest tests
```

Build (from the robo-services repo root):
`docker build -f services/vehicle_state/Dockerfile -t dwilson2547/robo-services-vehicle-state:<tag> .`
