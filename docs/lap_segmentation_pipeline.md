# Lap Segmentation Pipeline: Architecture and Development Notes

**Project:** robo-services  
**Last Updated:** 2026-05-30

---

## Overview

The lap segmentation pipeline is a Flink job (`LapSegmentationJob`) that consumes GPS and IMU
telemetry from Iggy and emits structured lap records to `telemetry.derived.laps`. It supports
multiple device profiles with different sensor capabilities and uses a geofence + bearing filter
to detect lap completions.

---

## Pipeline Flow

```
Iggy: telemetry.ingest.gps  ──┐
                               ├─► LapCoProcessFunction (keyed by session_id)
Iggy: telemetry.ingest.imu  ──┘         │
                                         ▼
                               Iggy: telemetry.derived.laps
```

**Key by:** `"{source_session}:{device_id}"` — set by kreceiver as the `x-session-id` header,
decoded from the b64 envelope (see b64 issue doc).

---

## State Machine

```
UNANCHORED → (lap_anchor message) → STAGED → (launch detected) → LAPPING
                                                                      │
                                                       (geofence + bearing) → emit lap record
                                                                      │
                                                              lapNumber++, reset state
```

**Phase transitions:**
- `UNANCHORED`: waiting for a `lap_anchor` GPS message. The anchor can be set manually
  (user taps button on device) or via a known start-line geofence (future work).
- `STAGED`: anchor is set, waiting for launch. IMU baseline is collected during this phase
  for non-gravity-compensated sensors.
- `LAPPING`: actively tracking a lap. Accumulates distance, max speed, point count.

---

## Message Format

All messages from kreceiver arrive as a b64-wrapped envelope:
```json
{ "payload_b64": "<base64>", "headers": { "x-device-id": "...", ... } }
```
The b64 decodes to a flat `NormalizedIngressMessage` dict with `device_id`, `captured_at`,
`source_session`, `message_type`, and a nested `payload` dict for sensor data.

**Always call `decodeEnvelope()` first in processElement.**

---

## Device Profiles

Configured via `values.yaml :: lapJob.profilesJson`. Profiles allow each device spec to
declare its sensor field names, geofence radius, bearing tolerance, and IMU configuration.

When `profilesJson` is empty, the default profile is used:
- GPS: `lat`, `lon`, `speed_kph`
- Geofence: 40m radius, 35° bearing tolerance
- Launch: GPS speed ≥ 3.0 kph (IMU-based launch disabled)

Device ID prefix matching: `"SCRAPS"` → scraps-v1 profile (if configured).

---

## Lap Record Output Schema

```json
{
  "session_id": "sim-clean-001:SCRAPS-001",
  "device_id": "SCRAPS-001",
  "source_session": "sim-clean-001",
  "lap_number": 1,
  "lap_start_ms": 1748592067023,
  "lap_end_ms": 1748592131023,
  "lap_duration_ms": 64000,
  "distance_m": 795.2,
  "max_speed_kph": 92.5,
  "gps_point_count": 32,
  "profile_id": "default"
}
```

---

## Simulator

`sim/track_sim.py` — synthetic GPS + IMU feed against a 9-waypoint closed track.

```bash
python3 sim/track_sim.py \
  --scenario sim/scenarios/clean.json \
  --receiver-host 192.168.0.70 \
  --receiver-port 5514 \
  --speed 10
```

**Scenario (`clean.json`):**
- `device_id: "SCRAPS-001"`, `source_session: "sim-clean-001"`
- 3 laps, 1 Hz GPS, 2 Hz IMU
- 30s pre-anchor, 15s staged, then 3 laps
- Simulated clock advances 1s/GPS fix + 0.5s/IMU message

The sim uses a **simulated clock** (not wall time) so Flink event-time timestamps are correct.

---

## Key Debugging Checklist

When laps don't complete, check in this order:

1. **Are session IDs correct?** — If `session=unknown`, the b64 envelope is not being decoded.
2. **Are elapsed times > 0?** — If elapsed ≈ 2–5ms, timestamps are falling back to wall clock.
   This means `captured_at` is null in the decoded map (b64 decode failing, or sim using wall clock).
3. **Are geofence hits appearing?** — If no hits, check anchor lat/lon vs track waypoints.
4. **Is bearing diff within tolerance?** — Hits with `diff > 35°` won't trigger lap completion.
   The `diff=119°` pattern means the vehicle is approaching from the wrong direction (outbound leg).
5. **Is elapsed >= min_lap_time_ms?** — Default 30,000ms. Hits with `elapsed < 30000` are on
   the start/finish line during the first few seconds after launch.

---

## Common SLF4J Trap

SLF4J only substitutes exact `{}` pairs. Never use `{:.1f}` or `{:d}` — they are emitted
as literal text and shift subsequent args. See `docs/flink_patterns_and_pitfalls.md` §1.

---

## FlinkDeployment Fast Iteration

```bash
kubectl patch flinkdeployment lap-segmentation -n robo-services --type=merge \
  -p '{"spec":{"job":{"upgradeMode":"stateless"},"image":"dwilson2547/robo-services-lap-flink:<tag>"}}'
```

---

## References

- `derivations/lap_flink_job/` — Flink job source
- `sim/track_sim.py` + `sim/scenarios/` — simulator
- `helm/robo-services/templates/lap-flink-job.yaml` — deployment
- `docs/flink_patterns_and_pitfalls.md` — general Flink pitfalls
- `docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md` — b64 envelope root cause
- `src/can_pub_sub_probe/iggy_backend.py` — message encoding
- `gps test feed/kreceiver_proto/models.py` — NormalizedIngressMessage schema
