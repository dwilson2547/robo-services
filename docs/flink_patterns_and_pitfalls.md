# Flink on Kubernetes: Patterns and Pitfalls

**Project:** robo-services  
**Last Updated:** 2026-05-30

A living reference for common problems and proven patterns when writing Apache Flink jobs
deployed via the Flink Kubernetes Operator (FlinkDeployment CRDs) in this cluster.

---

## 1. SLF4J Format Strings — `{:.1f}` Is a Silent No-op

**Problem:** SLF4J's `{}` placeholder substitution uses `indexOf("{}")` — it matches the
exact two-character sequence `{}`. Any format specifier inside braces like `{:.1f}` or `{:d}`
is **not** treated as a placeholder. It is emitted as literal text, and the corresponding
argument shifts to fill the next bare `{}`.

**Example of the bug:**
```java
// BAD — {:.1f} is literal, args shift left
LOG.info("dist={:.1f}m elapsed={}ms session={}", distToAnchor, elapsed, key);
// Actual output: "dist={:.1f}m elapsed=18.3ms session=2"
//                                       ^ distToAnchor    ^ elapsed!
```

**Fix — pre-format floats with `String.format`:**
```java
// GOOD
LOG.info("dist={}m elapsed={}ms session={}",
         String.format("%.1f", distToAnchor), elapsed, key);
```

This caused multiple debugging sessions where elapsed times appeared correct but were
actually distances, masking the real bug.

---

## 2. iggy_backend.py b64 Envelope

Every message published to Iggy via kreceiver is wrapped. See:
`docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md`

**TL;DR:** Always call `decodeEnvelope(rawMap)` before reading any field from a Flink message.

---

## 3. FlinkDeployment Patch for Rapid Iteration

ArgoCD sync can be slow (minutes) when iterating on a job. To force a new image immediately:

```bash
kubectl patch flinkdeployment <name> -n robo-services --type=merge \
  -p '{"spec":{"job":{"upgradeMode":"stateless"},"image":"<new-image-tag>"}}'
```

`upgradeMode: stateless` is required — without it the operator may refuse the patch or
attempt a savepoint that fails. For jobs without important keyed state during development,
stateless is fine; use `last-state` or `savepoint` for production upgrades.

After patching, watch for the TaskManager pod to become Ready:
```bash
kubectl get pods -n robo-services -l app=<job-name> -w
```

---

## 4. Raw `Map` Types and Unchecked Casts

The Flink IggySource deserializes JSON into `Map` (raw type). Java will produce unchecked
cast warnings everywhere. Suppress them with `@SuppressWarnings("unchecked")` at the method
or class level. Use a typed helper rather than inline casts throughout:

```java
static String asString(Object o) {
    return o instanceof String s ? s : (o != null ? o.toString() : null);
}
```

Avoid casting deeply nested structures — always use null-checked helpers.

---

## 5. `Map<?, ?>` vs `Map` in processElement Signatures

Flink's `CoProcessFunction` generics require `Map` (raw) in the method signature if the
source type is `TypeInformation.of(Map.class)`. Don't try to use `Map<String, Object>` in
the signature — you'll get serialization errors. Cast internally after extracting.

---

## 6. Keyed Streams and Session Windows

For per-session stateful processing (e.g., lap segmentation):
- Key by session ID before the `connect()` call — both streams must be keyed by the same key.
- `KeyedCoProcessFunction` gives you per-key `ValueState` / `ListState` that automatically
  partitions by session.
- Session ID is `"{source_session}:{device_id}"` in this project (set by kreceiver headers).

---

## 7. Timestamp Parsing — Java vs Python datetime strings

Python's `datetime.isoformat()` and `datetime.utcnow().isoformat() + "Z"` produce strings
like `2026-05-30T08:01:07.023697Z`. Java's `Instant.parse()` handles this correctly — the
microsecond precision and trailing `Z` are valid ISO-8601 and parse without issues.

No custom formatter is needed for timestamps originating from kreceiver.

---

## 8. Multi-Stream Timing — Simulator Clock vs Wall Clock

When writing simulators for Flink jobs that use event-time (message timestamps), ensure the
simulator emits a **monotonically increasing simulated clock** in the `captured_at` field,
not `datetime.utcnow()`. Using wall clock causes elapsed times of 2–5ms regardless of how
many simulated seconds have passed.

**Pattern:**
```python
sim_clock = datetime.utcnow()
GPS_STEP_S = 1.0
IMU_STEP_S = 0.5

for fix in gps_fixes:
    msg["captured_at"] = sim_clock.isoformat() + "Z"
    sim_clock += timedelta(seconds=GPS_STEP_S)
    send(msg)
```

---

## 9. Geofence + Bearing Filter for Lap Detection

The geofence-only approach produces many false positives when the anchor is on the track
and the vehicle crosses it mid-lap (e.g., heading out vs heading back). Add a bearing
direction filter:

- Record `anchorBearingDeg` as the bearing from anchor → first fix after launch (the
  "departure" bearing).
- On each geofence hit, compute `currentBearing` = bearing from prev fix → current fix.
- Only count the crossing if `bearingDelta(currentBearing, anchorBearingDeg) <= toleranceDeg`.

The `bearingDelta` function must handle the 0°/360° wrap correctly:
```java
static double bearingDelta(double a, double b) {
    double d = Math.abs(a - b) % 360;
    return d > 180 ? 360 - d : d;
}
```

---

## 10. DeviceProfile — Default Profile with `imu = null`

When `profilesJson` is empty in `values.yaml`, the default `DeviceProfile()` is used.
Default profile has `imu = null`, which causes `processElement2` (IMU handler) to return
early — launch detection falls back to GPS speed-only (`speedKph >= 3.0`).

This is correct for the scraps-v1 device. For IMU-based launch detection, ensure a matching
profile is configured with appropriate `accelFields` and thresholds.

---

## References

- `derivations/lap_flink_job/` — LapSegmentationJob (reference implementation)
- `helm/robo-services/templates/lap-flink-job.yaml` — FlinkDeployment template
- `docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md` — b64 envelope issue
- `docs/issues/2026_05_26_iggy_split_reader_polling_log_flood.md` — IggySource polling flood
