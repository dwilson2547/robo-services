# Issue: iggy_backend.py wraps Flink-consumed messages in a b64 envelope

**Date:** 2026-05-30  
**Component:** kreceiver → iggy_backend.py → Flink IggySource connector  
**Symptom:** `captured_at`, `device_id`, `source_session` all null in Flink; timestamps fall back to wall clock; session key is always `"unknown"`.

---

## Root Cause

`kreceiver/iggy_backend.py :: _encode_message(payload, headers)` does **not** publish the
`NormalizedIngressMessage` JSON directly to Iggy. It wraps it:

```json
{
  "payload_b64": "<base64 of NormalizedIngressMessage.to_dict() JSON>",
  "headers": {
    "x-device-id": "SCRAPS-001",
    "x-session-id": "sim-clean-001:SCRAPS-001",
    "x-captured-at": "2026-05-30T08:01:07.023697Z",
    "x-received-at": "2026-05-30T08:01:07.123456Z",
    "x-source-type": "gps",
    "x-message-type": "telemetry"
  }
}
```

The Flink IggySource connector receives this **outer wrapper** as the message. Any Flink job
that tries to read `captured_at`, `device_id`, or `source_session` from the top-level map gets
`null` — those fields only exist inside the base64-decoded inner JSON.

The GPS `payload` (lat/lon/speed) *does* work because the job had an early b64 code path in
`extractPayload()` that decoded it. This masked the root cause for a long time: positions and
geofence events appeared correct, but timestamps were always wall clock and sessions were always
`"unknown"`.

---

## Why It Was Hard to Diagnose

1. **Partial functionality** — GPS positions worked fine, giving false confidence the envelope
   was being read correctly.
2. **SLF4J format string bug** (see separate issue) caused debug log arguments to be reported
   out of order. What appeared to be `elapsed=18ms` was actually `distToAnchor=18.3m`. This hid
   the timestamp fallback for several iterations.
3. **No startup error** — `null` fields are silently accepted; fallback to `System.currentTimeMillis()`
   produces valid (but wrong) timestamps.

---

## Fix Applied in v5

Added a `decodeEnvelope(Map rawEnvelope)` helper to `LapSegmentationJob.java`:

```java
@SuppressWarnings("unchecked")
static Map<String, Object> decodeEnvelope(Map rawEnvelope) {
    // Already flat (direct NormalizedIngressMessage format)
    if (rawEnvelope.containsKey("device_id")) return rawEnvelope;
    Object b64 = rawEnvelope.get("payload_b64");
    if (!(b64 instanceof String b64str)) return null;
    try {
        return OBJECT_MAPPER.readValue(
                java.util.Base64.getDecoder().decode(b64str), Map.class);
    } catch (IOException e) {
        LOG.warn("Failed to decode b64 envelope: {}", e.getMessage());
        return null;
    }
}
```

Call this **first** in `processElement1` and `processElement2` before accessing any envelope
fields. Pass the decoded map to `parseTimestampMs`, `extractPayload`, and `emitLapRecord`.

Also updated `extractSessionId` to fall back to `headers["x-session-id"]` as a secondary path:

```java
Object headersObj = envelope.get("headers");
if (headersObj instanceof Map<?, ?> headers) {
    String sessionId = asString(headers.get("x-session-id"));
    if (sessionId != null && !sessionId.isBlank()) return sessionId;
}
```

---

## Prevention / Future Flink Jobs

Any Flink job consuming from an Iggy topic populated by kreceiver **must**:

1. Call `decodeEnvelope()` as the first step in `processElement`.
2. Extract session/device/timestamp fields from the **decoded** map, not the raw message.
3. The decoded map is a flat `NormalizedIngressMessage.to_dict()` with these top-level fields:
   - `device_id`, `source_session`, `session_id`, `captured_at`, `received_at`
   - `source_type`, `message_type`, `topic`, `sender_ip`, `sender_port`
   - `payload` → nested dict with sensor-specific fields (lat/lon/speed, accel, etc.)
4. Copy `decodeEnvelope()` into each new job or extract it into a shared utility class.

---

## References

- `src/can_pub_sub_probe/iggy_backend.py` — `_encode_message` / `_decode_message`
- `gps test feed/kreceiver_proto/models.py` — `NormalizedIngressMessage` schema
- `derivations/lap_flink_job/src/main/java/.../LapSegmentationJob.java` — `decodeEnvelope`, `extractPayload`, `extractSessionId`, `parseTimestampMs`
- Git commit: `28f22c1` (v5 fix)
