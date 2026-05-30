# lap-flink-job

Flink streaming job that consumes GPS and IMU telemetry from [Apache Iggy](https://iggy.apache.org/)
and emits structured lap records to a derived topic. Part of the
[robo-services](../../README.md) telemetry pipeline.

---

## How It Works

```
Iggy: telemetry.raw.gps  ──┐
                            ├─► LapCoProcessFunction (keyed by session)
Iggy: telemetry.raw.imu  ──┘         │
                                      ▼
                           Iggy: telemetry.derived.laps
```

**State machine per session:**

```
UNANCHORED ──(lap_anchor message)──► STAGED ──(launch detected)──► LAPPING
                                                                        │
                                                  (geofence + bearing)──► emit lap record → lapNumber++
```

- **UNANCHORED** — waiting for a `lap_anchor` GPS message marking the start/finish line.
- **STAGED** — anchor is set; waiting for the vehicle to launch. IMU baseline is collected
  during this phase for raw (non-gravity-compensated) sensors.
- **LAPPING** — actively accumulating distance, max speed, and GPS point count. A lap
  completes when the vehicle re-enters the anchor geofence from the same direction it
  departed (bearing filter prevents counting the outbound pass).

Sessions are keyed by `"{source_session}:{device_id}"`, taken from the `x-session-id`
header on each Iggy message.

---

## Output Schema

Each completed lap is published as a JSON object:

```json
{
  "session_id":       "sim-clean-001:SCRAPS-001",
  "device_id":        "SCRAPS-001",
  "source_session":   "sim-clean-001",
  "lap_number":       1,
  "lap_start_ms":     1748592067023,
  "lap_end_ms":       1748592131023,
  "lap_duration_ms":  64000,
  "distance_m":       795.2,
  "max_speed_kph":    92.5,
  "gps_point_count":  32,
  "profile_id":       "scraps-v1"
}
```

---

## Device Profiles

The job supports multiple device specs via `LAP_JOB_PROFILES_JSON`. Each profile declares:

| Field                    | Purpose                                              |
|--------------------------|------------------------------------------------------|
| `device_id_prefix`       | Matched as a prefix of the incoming `device_id`      |
| `gps.lat_field` etc.     | Sensor field names in the GPS payload                |
| `imu.accel_fields`       | Field paths for accelerometer components             |
| `imu.gravity_compensated`| Whether the IMU already removes gravity              |
| `geofence_radius_m`      | Radius of the start/finish geofence                  |
| `bearing_tolerance_deg`  | Max bearing delta allowed for a valid crossing       |
| `min_lap_time_ms`        | Minimum elapsed time before a crossing counts        |
| `launch_speed_floor_kph` | GPS speed threshold for launch (when IMU is absent)  |
| `launch_accel_threshold` | Acceleration threshold for IMU-based launch          |

Reference profiles are in [`profiles/profiles.json`](profiles/profiles.json):

| Profile        | Prefix  | Notes                                    |
|----------------|---------|------------------------------------------|
| `scraps-v1`    | SCRAPS  | GT-U7 GPS + MPU-6050 IMU, 1Hz/2Hz        |
| `mid-tier-v1`  | MID     | NEO-M9N + BNO085 (gravity-compensated)   |
| `personal-v1`  | RTK     | F9P RTK GPS, 10Hz+, tight tolerances     |

When `LAP_JOB_PROFILES_JSON` is empty the default profile is used: 1Hz GPS, speed-only
launch detection (≥ 3.0 kph), 40m geofence, 35° bearing tolerance.

---

## Environment Variables

| Variable                       | Default                    | Description                              |
|-------------------------------|----------------------------|------------------------------------------|
| `IGGY_CONNECTION_STRING`       | *(required)*               | `iggy+tcp://user:pass@host:port`         |
| `IGGY_STREAM`                  | `can-pub-sub-probe`        | Iggy stream name                         |
| `LAP_JOB_GPS_INPUT_TOPIC`      | `telemetry.raw.gps`        | Inbound GPS topic                        |
| `LAP_JOB_IMU_INPUT_TOPIC`      | `telemetry.raw.imu`        | Inbound IMU topic                        |
| `LAP_JOB_OUTPUT_TOPIC`         | `telemetry.derived.laps`   | Output lap records topic                 |
| `LAP_JOB_GPS_CONSUMER_GROUP`   | `lap-segmentation-gps`     | Iggy consumer group for GPS              |
| `LAP_JOB_IMU_CONSUMER_GROUP`   | `lap-segmentation-imu`     | Iggy consumer group for IMU              |
| `LAP_JOB_PROFILES_JSON`        | `""`                       | JSON array of device profiles            |
| `LAP_JOB_SOURCE_POLL_BATCH_SIZE` | `100`                    | Messages per Iggy poll                   |
| `LAP_JOB_CHECKPOINT_INTERVAL_MS` | `60000`                  | Flink checkpoint interval (ms)           |

---

## Build

Requires Java 17 and Maven.

```bash
# From repo root
mvn package -DskipTests -f derivations/lap_flink_job/pom.xml

# Docker image
docker build -t dwilson2547/robo-services-lap-flink:<tag> \
  -f derivations/lap_flink_job/Dockerfile .
```

---

## Deployment

Deployed as a `FlinkDeployment` CRD via the robo-services Helm chart. Enable and configure
in `helm/robo-services/values.yaml`:

```yaml
lapJob:
  enabled: true
  tag: 20260530-lap-v5
  profilesJson: ""       # leave empty for default profile, or paste JSON array
```

**Fast image rollout during development** (skips ArgoCD sync wait):

```bash
kubectl patch flinkdeployment lap-segmentation -n robo-services --type=merge \
  -p '{"spec":{"job":{"upgradeMode":"stateless"},"image":"dwilson2547/robo-services-lap-flink:<tag>"}}'
```

Watch for the TaskManager to become Ready:

```bash
kubectl get pods -n robo-services -l app=lap-segmentation -w
```

---

## Testing with the Simulator

`sim/track_sim.py` runs a synthetic 3-lap session (1 Hz GPS, 2 Hz IMU) against the live
kreceiver endpoint:

```bash
python3 sim/track_sim.py \
  --scenario sim/scenarios/clean.json \
  --receiver-host 192.168.0.70 \
  --receiver-port 5514 \
  --speed 10
```

The simulator uses a **simulated clock** (not wall time) so Flink event-time timestamps
are correct. The `clean.json` scenario uses `device_id: "SCRAPS-001"` and
`source_session: "sim-clean-001"`.

After the sim completes, verify in TM logs:

```bash
kubectl logs -n robo-services -l component=taskmanager --tail=200 | grep -E "Lap [0-9]+ complete"
# Expected: Lap 1 complete ... 64000ms, 795m
#           Lap 2 complete ... 72000ms, 841m
#           Lap 3 complete ... 72000ms, 841m
```

---

## Debugging Checklist

| Symptom                        | Likely cause                                                          |
|-------------------------------|-----------------------------------------------------------------------|
| `session=unknown`              | b64 envelope not decoded — `decodeEnvelope()` failing or null        |
| `elapsed=2ms`                  | `captured_at` null, falling back to wall clock                       |
| No geofence hits at all        | Anchor lat/lon doesn't match track; check `lap_anchor` log line      |
| Hits with `diff=119°`          | Outbound pass — expected, not a bug; bearing filter correctly ignores |
| Hits with `elapsed < 30000ms`  | Post-launch crosses before min lap time; correctly suppressed        |
| No `Lap N complete` log line   | Bearing diff exceeds tolerance or min_lap_time_ms not reached        |

---

## Known Gotchas

- **SLF4J format strings:** only `{}` is substituted. Never use `{:.1f}` — it is emitted
  literally and shifts subsequent arguments. Use `String.format("%.1f", val)` instead.
- **iggy_backend.py wraps messages:** every kreceiver message arrives as
  `{"payload_b64": "...", "headers": {...}}`. Always call `decodeEnvelope()` before reading
  fields. See [`docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md`](../../docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md).
- **IggySource log flood:** the Iggy Flink connector 0.8.0 busy-polls on empty fetches.
  Log level for `IggyPartitionSplitReader` is suppressed to WARN in the FlinkDeployment
  `logConfiguration`. See [`docs/issues/2026_05_26_iggy_split_reader_polling_log_flood.md`](../../docs/issues/2026_05_26_iggy_split_reader_polling_log_flood.md).

---

## Further Reading

- [`docs/lap_segmentation_pipeline.md`](../../docs/lap_segmentation_pipeline.md) — architecture, state machine detail, full debugging guide
- [`docs/flink_patterns_and_pitfalls.md`](../../docs/flink_patterns_and_pitfalls.md) — general Flink patterns for this project
- [`sim/`](../../sim/) — simulator and scenarios
