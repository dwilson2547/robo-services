# drive_summary

Writes a drive summary for each settled recording session into the registry's
`session_summaries` (migration 0009), from the session's rows in Timescale
`telemetry.vehicle_state`. Design: `docs/plans/2026-11-03-trip-pipelines.md` §4
and "Drive 1 findings".

Once a minute it picks up sessions that

- have ended (`session_end`) or gone 30 min without one,
- have had no frame for 2 min, and
- have no summary yet, one from an older metrics version, or more frames
  received since theirs was computed (late delivery).

Sessions without vehicle_state rows (recorded before samples were frame-tagged)
are skipped and reported once. Each summary is logged as one line.

Metrics (`app/metrics.py`, version 1):

- duration, moving, idle time, and stops;
- distance by GPS (the headline), by CAN speed, and by odometer;
- speeds; engine RPM; time in each selector position, and gears while in Drive;
- shifts, and brake applications;
- hard braking and acceleration from CAN speed, and cornering as speed × yaw
  rate, as event counts and peaks;
- coolant and oil temperatures;
- a data-quality block.

The roles behind the columns come from the vehicle's DBC (`prndl` needs a code
map).

Read through the registry:
`GET /api/sessions/{device}/{session}/summary` (LAN) and
`GET /api/app/sessions/{session}/summary` (the phone app, caller's own sessions).

Config (env): `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (the
registry's database), `TS_HOST`, `TS_PORT`, `TS_NAME`, `TS_USER`, `TS_PASSWORD`,
`TS_SCHEMA`, `TS_TABLE`, `DRIVE_SUMMARY_EVERY_S`, `DRIVE_SUMMARY_LOG_LEVEL`.

Test (`test_pass.py` needs a registry Postgres at alembic head and a TimescaleDB,
as in services/session_tracker and services/vehicle_state):

```bash
TEST_DATABASE_URL=postgresql://postgres@127.0.0.1:55432/postgres \
TEST_TIMESCALE_HOST=127.0.0.1 TEST_TIMESCALE_PORT=55433 PYTHONPATH=. python -m pytest tests
```

Build (from the robo-services repo root):
`docker build -f services/drive_summary/Dockerfile -t dwilson2547/robo-services-drive-summary:<tag> .`
