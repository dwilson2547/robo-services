# speed-flink-job

Deployable Flink application for deriving average GPS speed from the live
`telemetry.raw.gps` feed in Iggy.

## What it does

- consumes the normalized GPS envelope published by `kreceiver`
- extracts `payload.ground_speed_kph`
- uses `captured_at` with `received_at` fallback for event time
- computes a per-device 10 second tumbling average
- publishes derived JSON events to `telemetry.derived.speed`

## Build

```bash
cd derivations/speed_flink_job
mvn clean package
```

The shaded job jar is written to:

```text
target/speed-flink-job.jar
```

## Container image

Build the Flink image from the repo root:

```bash
docker build -f derivations/speed_flink_job/Dockerfile -t dwilson2547/robo-services-speed-flink:dev .
```

The runtime image copies the shaded jar into `/opt/flink/usrlib/` so the
Kubernetes operator can run it via `local:///opt/flink/usrlib/speed-flink-job.jar`.

## Configuration

The job reads these environment variables:

- `IGGY_CONNECTION_STRING`
- `IGGY_STREAM`
- `SPEED_JOB_INPUT_TOPIC`
- `SPEED_JOB_OUTPUT_TOPIC`
- `SPEED_JOB_CONSUMER_GROUP`
- `SPEED_JOB_WINDOW_SECONDS`
- `SPEED_JOB_MAX_OUT_OF_ORDERNESS_SECONDS`
- `SPEED_JOB_SOURCE_POLL_BATCH_SIZE`
- `SPEED_JOB_SINK_BATCH_SIZE`
- `SPEED_JOB_SINK_FLUSH_INTERVAL_SECONDS`

## Output shape

```json
{
  "device_id": "64BCD7C63C94",
  "source_session": "esp32-gps-bench",
  "window_start": "2026-05-25T22:57:40Z",
  "window_end": "2026-05-25T22:57:50Z",
  "sample_count": 4,
  "average_speed_kph": 12.34,
  "unit": "kph",
  "source_topic": "telemetry.raw.gps",
  "topic": "telemetry.derived.speed",
  "derivation": "average_ground_speed_kph"
}
```
