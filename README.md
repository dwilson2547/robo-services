---
tier: system
domain: robotics
secondary: [automotive]
---

# robo-services

Vehicle telemetry pipeline. A capture device in the car sends CAN frames, GPS and IMU samples
over MQTT. kreceiver puts them on Apache Iggy topics. Services and Flink jobs then decode the CAN,
track recording sessions, derive vehicle state, speed, laps and track position, and archive the
raw stream to Parquet. A registry (API and web UI) holds users, devices, vehicles, DBCs, tracks and
sessions.

Everything runs on the home k8s cluster. This chart deploys to the `robo-services` namespace. The
brokers (Mosquitto, Iggy) and kreceiver run in `pub-sub`, from `infra/cluster-config/pub-sub/`.

## Data flow

```
dongle (embedded/dbc-forge) --BLE--> phone gateway --MQTT--> Mosquitto
race logger (embedded/race-logger) ---------------MQTT--> Mosquitto
                                                            |
                                              kreceiver (ingest/kreceiver)
                                                            |
               Iggy: telemetry.raw.{can,gps,imu,rtk}, telemetry.session
                                                            |
   can_decode ----> telemetry.decoded.can / telemetry.unknown.can
   can_discovery    (vehicles with no DBC: correlates CAN against GPS/IMU)
   session_tracker  -> registry sessions
   vehicle_state    -> Timescale telemetry.vehicle_state -> drive_summary -> registry
   Flink: speed, lap segmentation, track position (+ Timescale sink)
   archive writer   -> Parquet in the race-logger bucket
```

## Layout

| Path | What |
|---|---|
| `ingest/kreceiver/` | MQTT/UDP ingest: validates frames, stamps the user, publishes to Iggy. Deployed from cluster-config |
| `services/registry/` | FastAPI + React + Postgres: users, devices, vehicles, DBCs, tracks, sessions, analytics. Public at robo.danwils.com |
| `services/can_decode/` | DBC decode of raw CAN |
| `services/can_decode_rs/` | Rust shadow of can_decode, an experiment; not deployed |
| `services/can_discovery/` | DBC discovery for vehicles without a DBC (Rust) |
| `services/session_tracker/` | Recording-session bookkeeping in the registry |
| `services/vehicle_state/` | Per-interval vehicle state into Timescale |
| `services/drive_summary/` | Per-session drive summaries |
| `services/mqtt_auth/` | Auth backend for the public MQTT listener the phone gateway uses |
| `services/archive_writer/` | Dockerfile only; the code is `src/can_pub_sub_probe/archive_writer.py` |
| `src/can_pub_sub_probe/`, `tests/` | Shared Python package (Iggy backend, frame codecs, pipeline hops, archive writer) and its tests |
| `derivations/` | Flink jobs (Java): speed, lap segmentation, track position and its Timescale sink, plus shared Iggy source support |
| `helm/robo-services/` | The chart Argo deploys (`infra/cluster-config/argocd/robo-services.yaml`) |
| `telemetry.proto` | The `TelemetryFrame` wire format devices send |
| `capture_device_configs/` | Phone gateway app (`mid_tier_device/phone_gateway/`), early CAN captures (`can_caps/`) |
| `can_data/` | DBCs (opendbc and custom) and CAN analysis |
| `track-poly-poc/` | Track geometry from OpenStreetMap (GeoJSON) |
| `sim/` | Synthetic GPS/IMU track sessions for exercising the lap pipeline |
| `iggy/` | Local Iggy stack for development |

## Docs

- `docs/hardware/`, `docs/tooling/`: shared reference library for parts and tools (see CLAUDE.md)
- `docs/plans/`, `docs/decisions/`, `docs/issues/`, `docs/notes/`: design work, decisions, incident write-ups, gotchas
- `TODO.md`: backlog
- `docs/history/2026-05_poc_design.md`: the original May 2026 proof-of-concept design (superseded)
