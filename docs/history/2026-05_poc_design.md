# CAN Bus Telemetry Pipeline — POC Design

## Goal

Build a multi-hop pub/sub pipeline using vehicle CAN bus data as the message source. The primary engineering goal is to validate a probe/tracer message architecture that supports 100% observability for synthetic diagnostic messages alongside a sampled observability strategy for high-volume real traffic. Secondary goal is a reusable vehicle telemetry foundation that can feed SLAM rig enrichment and vehicle build data logging.

The pipeline is intentionally abstract at the transport layer — the pub/sub backend should be swappable (MQTT, Kafka, Pulsar) without touching pipeline logic.

---

## Hardware — CAN Recorder

**Target platform:** ESP32 with a TJA1051 CAN transceiver

**Capture approach:**
- TWAI peripheral in listen-only mode, 500kbps (W-body HS-CAN standard)
- Timestamp each frame at capture time using ESP32 RTC or NTP-synced system clock
- Buffer frames in-memory and flush over TCP in a lightweight framing format

**Wire format (capture → ingest):**

```
[4 bytes: unix timestamp ms]
[4 bytes: CAN ID, extended flag in MSB]
[1 byte:  DLC]
[8 bytes: data, zero-padded]
= 17 bytes per frame, fixed width for easy parsing
```

Fixed-width framing avoids delimiter ambiguity and keeps the ingest parser trivial. At 500kbps with a busy bus this is well within ESP32 TCP throughput.

**Dataset recording:**

Run capture sessions per scenario (cold start, highway cruise, hard acceleration, idle). Store raw binary frame logs with session metadata in a sidecar JSON file:

```json
{
  "session_id": "w-body-cold-start-001",
  "vehicle": "1999-pontiac-grand-prix-gtp",
  "capture_start_utc": "2025-01-01T08:00:00Z",
  "bus": "HS-CAN",
  "baud": 500000,
  "frame_count": 142800,
  "duration_seconds": 312
}
```

**Replay:**

A replay tool reads the binary log and re-publishes frames at their original inter-frame timing using the captured timestamps. This produces a realistic real-time message rate rather than a bulk dump, which is necessary to validate sampler behavior under load. The replay tool should support a speed multiplier (1x, 5x, 10x) for stress testing.

---

## Pipeline Architecture

Five hops, each implemented as an independent process subscribing to an input topic and publishing to an output topic.

```
[ESP32 / Replay Tool]
        │  raw binary frames over TCP
        ▼
   Hop 1: Ingest + Normalize
        │  normalized CAN signal events
        ▼
   Hop 2: Validate + Filter
        │  validated signal events
        ▼
   Hop 3: Signal Router
        │  per-domain topics (powertrain / chassis / body)
        ▼
   Hop 4: Aggregation / Windowing
        │  windowed aggregates + derived signals
        ▼
   Hop 5: Sink
           InfluxDB (time series), flat file (SLAM enrichment)
```

---

## Hop Detail

### Hop 1 — Ingest + Normalize

**Input:** raw binary frames (TCP from ESP32 or replay tool)  
**Output:** normalized signal events on `signals.raw` topic

Responsibilities:
- Parse the 17-byte frame format
- Detect probe frames (see Probe Architecture below) before any other processing
- Look up CAN ID in DBC definition table
- Decode signal values from frame data per DBC bit/scaling/offset rules
- Emit one event per decoded signal (not per frame — a single frame can carry multiple signals)

**Normalized schema:**

```python
@dataclass
class SignalEvent:
    signal_name: str         # e.g. "EngineRPM", "SteeringAngle"
    value: float             # engineering units
    unit: str                # e.g. "rpm", "deg", "km/h"
    raw_frame_id: int        # original CAN ID
    captured_at: datetime    # from frame timestamp
    processed_at: datetime   # wall clock at ingest
    source_session: str      # session_id from metadata
    probe: ProbeContext | None = None  # None = real message
```

Drop conditions at this hop:
- `UNKNOWN_CAN_ID` — frame CAN ID not present in DBC
- `DBC_DECODE_FAILED` — frame data malformed relative to signal definition
- `FRAME_INVALID` — DLC out of range, timestamp out of bounds

### Hop 2 — Validate + Filter

**Input:** `signals.raw`  
**Output:** `signals.validated`

Responsibilities:
- Range validation against engineering limits defined in config (min/max per signal name)
- Rate-of-change plausibility check (e.g. RPM cannot jump 5000rpm in one frame)
- Intentional filter rules — signals explicitly excluded from downstream processing

Drop conditions at this hop:
- `RANGE_EXCEEDED` — value outside configured engineering limits
- `RATE_IMPLAUSIBLE` — delta vs previous value exceeds configured threshold
- `FILTER_REJECTED` — signal matches an exclusion rule (intentional, not a bug)

Note: `FILTER_REJECTED` vs `RANGE_EXCEEDED` distinction matters. One is intentional configuration, the other is a data quality or hardware issue.

### Hop 3 — Signal Router

**Input:** `signals.validated`  
**Output:** domain topics — `signals.powertrain`, `signals.chassis`, `signals.body`

Responsibilities:
- Route signals to domain topics based on routing table config
- A signal may route to multiple domains

Drop conditions at this hop:
- `NO_ROUTE` — signal name not present in routing table (config gap, not intentional)
- `ROUTING_TABLE_VERSION_MISMATCH` — signal present but routing table version predates its definition

### Hop 4 — Aggregation / Windowing

**Input:** domain topics  
**Output:** `signals.aggregated`

Responsibilities:
- Tumbling windows (configurable, e.g. 1s / 5s / 30s) per signal
- Compute min/max/mean/stddev per window
- Derive composite signals (e.g. lateral acceleration from yaw rate + vehicle speed)
- Pass probe events through without aggregation — probes get emitted immediately, not held for a window boundary

Drop conditions at this hop:
- `WINDOW_OVERFLOW` — more samples in window than configured maximum (backpressure signal)
- `INSUFFICIENT_SAMPLES` — window closed with fewer than minimum required samples for a valid aggregate

### Hop 5 — Sink

**Input:** `signals.aggregated` (aggregated) + `signals.validated` (raw feed for SLAM)  
**Output:** InfluxDB, flat file

Responsibilities:
- Write aggregates to InfluxDB with signal name as measurement, domain as tag
- Write raw validated signals to flat file for SLAM rig enrichment (steering angle, yaw rate, vehicle speed are the priority signals)
- Suppress writes for probe events (`probe.suppress_egress = True`) — log the probe arrival instead

Current POC implementation keeps InfluxDB behind a replaceable sink interface and ships a flat-file JSONL backend first. Raw sink records carry the validated signal payload and source topic, while aggregate sink records normalize the event into `measurement`, `domain`, `source_topic`, `window_start`, `window_end`, `sample_count`, `min_value`, `max_value`, `mean_value`, `stddev_value`, `kind`, and `derived_from`. Probe messages do not egress when suppression is enabled, but they still emit a terminal `forwarded_to_sink` outcome record so probe tracking can close successfully without polluting downstream storage.

---

## Pub/Sub Abstraction Layer

All hops interact with pub/sub through a common interface. The concrete backend is injected at startup via config.

```python
class PubSubBackend(Protocol):
    def publish(self, topic: str, payload: bytes, headers: dict[str, str]) -> None: ...
    def subscribe(self, topic: str) -> Iterator[Message]: ...

class Message(Protocol):
    @property
    def payload(self) -> bytes: ...
    @property
    def headers(self) -> dict[str, str]: ...
    def ack(self) -> None: ...
    def nack(self) -> None: ...
```

Concrete implementations:
- `IggyBackend` — short-term default target for local development over native TCP (`iggy+tcp://...`), not the HTTP adapter
- `MQTTBackend` — for ESP32 connectivity and local testing
- `KafkaBackend` — for load testing and production-equivalent scenarios
- `PulsarBackend` — for comparison against Kafka under same workload

The abstraction intentionally does not expose topic partitioning, consumer groups, or subscription types — those are backend-specific concerns configured externally. If a hop needs partition-aware behavior it should be explicit about requiring a specific backend.

---

## Rule Engine

Rules are loaded from a YAML config file and hot-reloaded on change. A DBC file serves as the signal definition source and is treated as a special-case rule set for Hop 1.

**Rule format:**

```yaml
rules:
  - id: rpm_range
    hop: validate
    signal: EngineRPM
    condition: value < 0 or value > 8000
    action: drop
    reason_code: RANGE_EXCEEDED

  - id: exclude_debug_signals
    hop: validate
    signal_pattern: "Debug_*"
    condition: always
    action: drop
    reason_code: FILTER_REJECTED

  - id: route_powertrain
    hop: router
    signal_pattern: "Engine*|Throttle*|Transmission*"
    action: publish
    topic: signals.powertrain
```

Rules are evaluated in definition order. First match wins per drop/route decision. The rule engine should log the matched rule ID alongside every drop event to make config debugging tractable.

---

## Probe / Tracer Architecture

### Detection

Probe detection happens at the very top of Hop 1, before format parsing and before the sampling gate. A probe frame is identified by a reserved CAN ID (e.g. `0x7FF` — not used by any production ECU on the W-body bus) plus a magic byte in the data field.

```python
PROBE_CAN_ID = 0x7FF
PROBE_MAGIC  = 0xCA  # byte 0 of data field

def detect_probe(frame: RawFrame) -> ProbeContext | None:
    if frame.can_id == PROBE_CAN_ID and frame.data[0] == PROBE_MAGIC:
        return ProbeContext(
            tracer_id=frame.data[1:9].hex(),  # bytes 1-8 as tracer UUID fragment
            flow=frame.data[9],               # flow ID byte
            injected_at=frame.timestamp,
            suppress_egress=True,
        )
    return None
```

### ProbeContext propagation

`ProbeContext` is a first-class field on `SignalEvent`. Each hop passes it downstream unchanged. When serializing to the pub/sub backend, probe fields are written as message headers:

```
X-Probe: true
X-Probe-ID: <tracer_id>
X-Probe-Flow: <flow>
X-Probe-Injected-At: <iso8601>
```

### Drop event emission

Every hop that can drop a message emits a structured drop event via a single wrapper function:

```python
def emit_drop(msg: SignalEvent, reason: DropReason, detail: str = "") -> None:
    if msg.probe is not None:
        # 100% emission, always
        diagnostic_sink.publish({
            "tracer_id": msg.probe.tracer_id,
            "hop": HOP_NAME,
            "outcome": "dropped",
            "reason_code": reason.value,
            "reason_detail": detail,
            "signal": msg.signal_name,
            "timestamp": utcnow(),
        })
    elif should_sample(msg):
        # sampled emission for real traffic
        metrics.increment("signal.dropped", tags={"reason": reason.value, "hop": HOP_NAME})
```

The 20 (or however many) drop condition sites call `emit_drop(msg, DropReason.X)` unchanged. Probe awareness is entirely in the wrapper.

### Probe injection

A small CLI tool publishes probe frames to the bus (or directly to the ingest topic, bypassing hardware):

```
probe inject --flow cold-start --tracer-id abc123
probe status --tracer-id abc123
probe list-flows
```

Pre-configured flow templates define which signals a probe should exercise and what values to inject. The probe service maintains in-flight state per tracer ID with a configurable TTL. If a terminal event (`dropped` or `forwarded_to_sink`) is not received before TTL expiry, the watchdog emits a `SILENT_DROP` event with the last known hop.

Current POC operator commands are local-first:

- `can-pub-sub-probe replay-run capture.bin --output-dir out/` runs a binary frame log through validate, route, aggregate, and sink stages and prints a JSON summary
- `can-pub-sub-probe inspect-probes capture.bin` scans a binary frame log for embedded probe frames and prints tracer metadata as JSON
- `can-pub-sub-probe run-fixture fixtures/session-a --output-dir out/` executes a fixture directory containing `frames.bin`, `session.json` or `metadata.json`, and optionally `expected.json` for regression checks

---

## Observability Strategy

| Traffic type | Metric emission | Trace emission | Log verbosity |
|---|---|---|---|
| Real messages | Sampled (configurable rate) | Sampled | WARN and above |
| Probe messages | 100% always | 100% always | DEBUG always |

All probe events are tagged with `tracer_id` enabling full correlation across hops in whatever backend is used (InfluxDB tags, log structured fields, etc.).

A per-hop diagnostic topic (`diagnostics.drop_events`) aggregates all drop events from all hops. The probe service subscribes to this topic to drive probe status tracking.

---

## Data Sources and Test Fixtures

**Primary:** Live capture from W-body HS-CAN via ESP32 bridge (existing hardware)

**Secondary — DBC reference:** 
- W-body (GP/Impala/Monte Carlo) community DBC files are available; signals of interest include `EngineRPM`, `VehicleSpeed`, `ThrottlePosition`, `SteeringWheelAngle`, `YawRate`, `LateralAcceleration`, `CoolantTemp`, `TransmissionGear`

**Test fixtures:**
- Known-good frame log per scenario (cold start, cruise, WOT pull) for regression testing
- Synthetically generated edge-case frames (out of range values, unknown CAN IDs, malformed DLC) for drop condition coverage
- Probe frame generator for each configured flow

---

## Open Questions / Decisions Deferred

- **Replay tool timing precision:** `time.sleep()` is not accurate enough at high frame rates. May need a busy-wait loop or a platform timer for sub-millisecond inter-frame gaps at 5x+ replay speed.
- **DBC source:** Need to confirm which community DBC covers the specific W-body variant in use. Signal names and IDs vary by model year and engine.
- **Probe CAN ID reservation:** `0x7FF` is the standard OBD-II tester-present ID. Verify it is not actively used on the target bus during capture sessions, or choose a different reserved ID.
- **Windowing in Hop 4:** Flink on MicroK8s is the natural fit given existing infrastructure. Could also start with a simpler in-process tumbling window and migrate later — the abstraction layer makes this relatively low risk.
- **Diagnostic sink backend:** Drop events need to go somewhere independent of the main pipeline (so a pipeline failure doesn't silence diagnostics). A separate lightweight topic or a direct write to a log file are both viable for POC.