# robo-services — AI-Assisted Engineering Session Showcase
**May 2026 · GitHub Copilot CLI (Claude Sonnet 4.6)**

---

## What Is This?

This document is a record of a multi-day engineering session building out the `robo-services`
project — a real-time vehicular telemetry pipeline — using GitHub Copilot CLI as the primary
engineering assistant. Every task listed below was requested conversationally, in plain English,
and executed autonomously.

The system used: a local Kubernetes cluster (MicroK8s), ArgoCD for GitOps deployment, Apache Iggy
as the message broker, Apache Flink for stream processing, and an ESP32 microcontroller as the
field data logger. No proprietary tooling. No elevated cloud spend. Just the right tools connected
correctly.

---

## What Was Already in Place at Session Start

- A bare Helm chart (`robo-services`) managed by ArgoCD
- An ESP32 publishing live GPS + IMU data over UDP to a Python receiver (`kreceiver`)
- `kreceiver` normalizing and forwarding to Iggy message broker topics (`telemetry.raw.gps`, `telemetry.raw.imu`)
- Iggy running in the cluster, receiving a live feed

---

## Task 1 — Design a Flink Speed Derivation Pipeline

**Request:** *"I'd like to build a Flink pipeline that subscribes to the test GPS feed and
publishes on a derived/speed topic with the average speed of the unit."*

**What happened:**
- Investigated the repo layout, Helm chart, topic naming conventions, and existing GPS payload
  shape before making any recommendations
- Determined Apache Kafka was not needed — Iggy had a native Flink connector
- Recommended a standalone Flink DataStream job: consume `telemetry.raw.gps`, key by `device_id`,
  use event time from `captured_at` with `received_at` fallback, compute 10-second tumbling
  average of `ground_speed_kph`, publish to `telemetry.derived.speed`

**Outcome:** Architecture validated and agreed on before a single line of code was written.

---

## Task 2 — Create the Speed Derivation Job

**Request:** *"Could you create the speed Flink job in `/robo-services/derivations`?"*

**What happened:**
- Scaffolded an initial Python/PyFlink proof of concept with parsing helpers, windowing, README,
  and unit tests — validated it against the live repo test suite
- Recognised the Python version was not the right production/deployment fit and pivoted to a
  Java/Maven implementation using the Apache Iggy Flink connector

**Files created:**
- `derivations/speed_flink_job/pom.xml` — Maven build, shaded JAR packaging
- `derivations/speed_flink_job/Dockerfile` — Flink 2.0 Java 17 runtime image
- `derivations/speed_flink_job/src/main/java/.../SpeedDerivationJob.java` — full Flink job
- `derivations/speed_flink_job/src/test/java/.../SpeedDerivationJobTest.java` — unit tests
- `derivations/speed_flink_job/README.md` — build and runtime docs

**Outcome:** Working, tested, containerised Flink job ready for deployment.

---

## Task 3 — Deploy the Job to the Live Cluster

**Request:** *"How do you recommend we deploy this? I think it fits in well with the robo-services."*

**What happened:**
- Checked `cluster_config/CLAUDE.md` to understand the exact deployment pattern before touching
  anything (ArgoCD self-managed Helm, git push to `main` = live deploy)
- Confirmed the cluster already had the Flink Kubernetes Operator installed
- Extended the existing `robo-services` Helm chart rather than creating a separate ArgoCD app:
  - `helm/robo-services/templates/speed-job-flinkdeployment.yaml` — `FlinkDeployment` CR
  - `helm/robo-services/templates/speed-job-configmap.yaml` — env/config wiring
  - `helm/robo-services/templates/speed-job-rbac.yaml` — namespaced service account + RBAC
  - `helm/robo-services/templates/_helpers.tpl` — Helm helpers for job naming/labels
- Built Docker image, pushed to registry, enabled the job in `values.yaml`, committed and pushed

**Commits:** `173f0ef`, `3d50ede`

**Outcome:** `FlinkDeployment/speed-derivation` live in ArgoCD. Job running in the cluster.

---

## Task 4 — Debug: Job Running but Not Publishing

**Request:** *"The job seemed to pick up data but wasn't publishing."*

**What happened:**
- Pulled live cluster state, Flink REST metrics, JobManager and TaskManager logs, and Iggy HTTP
  API responses to build a picture of what was failing
- Identified two root causes:
  1. The Iggy source connector did not auto-create its consumer group — job silently got nothing
  2. The output topic `telemetry.derived.speed` did not exist — sink had nowhere to write
- Created both prerequisites live against the Iggy HTTP API for immediate validation
- Then discovered a deeper issue: Jackson classpath conflicts between the Iggy connector and the
  Flink runtime were causing `NoSuchMethodError` and `NoSuchFieldError` on first window emission
- Iterated through multiple fixes: switched from typed DTO payloads to plain `Map`, added a
  custom deserializer using the job's own `ObjectMapper`, added `child-first` classloading,
  replaced the built-in `IggySinkWriter` (which called Iggy's internal HTTP path) with a custom
  `TcpIggySink` using the raw TCP protocol client
- Discovered the raw GPS messages in Iggy were outer wrapper objects with `payload_b64`
  (base64-encoded inner JSON) — added decode logic

**Commits:** `4111318`, `261c4e4`, `f64e9f9`, `ed1ea48`, `8d4a609`, `9000d3c`, `4381d07`
**Images:** `speed-flink:v1` through `speed-flink:v7`

**Outcome:** `telemetry.derived.speed` receiving records. Pipeline end-to-end functional.

---

## Task 5 — Fix: TaskManager Appeared Unstable

**Request:** *"The TaskManager is whigging out and refusing to go green."*

**What happened:**
- Checked live cluster state first — all pods `Running`, 0 restarts, job `STABLE/RUNNING`,
  26 successful checkpoints. Job was actually healthy.
- Pulled TaskManager logs: 100% `IggyPartitionSplitReader` INFO polling lines, nothing else
- Root cause: the Iggy Flink connector has no idle backoff — on empty poll results it
  immediately re-polls, creating a sub-millisecond busy-wait loop that floods logs completely,
  making the TM look broken when it wasn't
- Fix: added `logConfiguration` to the `FlinkDeployment` template suppressing
  `IggyPartitionSplitReader` to WARN — **no image rebuild required**
- ArgoCD reconciled, pods came back clean in ~4 minutes

**Commit:** `0660a10`

**Outcome:** TM healthy and green. Actual job events now visible in logs again.

---

## Task 6 — Document the Issue

**Request:** *"Could you document the issue?"*

**What happened:**
- Created `docs/issues/` directory (did not previously exist)
- Wrote a structured issue document covering observed symptom, root cause mechanics, numbered
  troubleshooting steps, and the fix applied

**File created:**
- `docs/issues/2026_05_26_iggy_split_reader_polling_log_flood.md`

**Outcome:** Issue recorded for future reference and onboarding.

---

## Task 7 — ESP32 Hardware Debugging

**Request:** *"The display is causing brownouts."*

**What happened:**
- Traced `Serial.println(payload)` as the source of observed LED blinking (GPIO2 TX activity)
  — but the user found the real cause first: wrong ground pin on a new ESP32 board
- Investigated the SSD1306 display drawing too much from the ESP32's onboard 3.3V LDO
  (AMS1117, ~800mA limit) — diagnosed as thermal failure of the voltage regulator
- Advised isolating power vs I2C as the failure mode
- Removed display code entirely from the sketch and replaced with two-LED status indicators:
  - GPIO 32 — WiFi status (fast blink = searching, heartbeat = connected)
  - GPIO 26 — GPS status (same pattern for fix state)
- Caught and fixed a subtle GPIO conflict: GPIO 25 is held by the WiFi radio when active on
  many ESP32 boards — moved WiFi LED to GPIO 32

**File modified:** `gps test feed/esp32_gps_udp_feed/esp32_gps_udp_feed.ino`

**Outcome:** ESP32 runs indefinitely, status readable from LEDs without display overhead.

---

## Task 8 — Feature Planning: Project Vision

**Request:** The user described the broader vision — an open-source MyChron alternative for
track racing: CAN bus integration, WiFiManager for on-device network config, MsgPack for
compact payloads, lap timer button, high-accuracy RTK GPS, motorcycle CAN bus research, SD
card local buffering.

**What happened:**
- Captured every requested item as tracked todos
- Discussed each in technical depth: WiFiManager captive portal with custom parameters (IP,
  port), MessagePack payload encoding with ArduinoJson, PubSubClient for MQTT, lap marker
  button publishing a dedicated `telemetry.event.lap` message, GPS-RTK-SMA integration, CAN
  standard compatibility for Ducati 2013+ and KTM, MyChron feature parity analysis

**Todos created:** #48 (WiFiManager), #49 (reset button), #50 (MsgPack), #51 (RTK GPS),
#52 (lap marker), #53 (GPS/IMU chip), #54 (Ducati CAN), #55 (KTM CAN), #56 (SD buffer),
#57 (MQTT firmware)

**Outcome:** Comprehensive backlog with enough context to execute any item independently.

---

## Task 9 — Deploy Mosquitto MQTT Broker

**Request:** *"I'm a bit concerned about UDP over the public internet. Could we tackle the
MQTT receiver in the cluster now?"*

**What happened:**
- Confirmed the deployment pattern (self-managed Helm, `cluster_config/CLAUDE.md` consulted)
- Identified next available MetalLB IP: `192.168.0.71`
- Created three new Helm templates:
  - `mosquitto-configmap.yaml` — `eclipse-mosquitto:2` config (anonymous, no persistence, stdout logs)
  - `mosquitto-deployment.yaml` — deployment mounting the configmap
  - `mosquitto-service.yaml` — LoadBalancer service at `192.168.0.71:1883`
- Extended `kreceiver` to run both UDP and MQTT concurrently:
  - Added `paho-mqtt>=1.6,<2.0` to `pyproject.toml`
  - Added `transport`, `mqtt_host`, `mqtt_port`, `mqtt_topic` to `ReceiverSettings`
  - Added `start_mqtt_receiver_background()` using paho v1 `loop_start()` background thread
  - Same `normalize_packet()` → `publish_normalized()` pipeline for both transports
  - Default `KRECEIVER_TRANSPORT=both`
- Added `mqtt IN A 192.168.0.71` to `cluster_config/dns/dns.yaml`
- Built and pushed `kreceiver-proto:20260526-mqtt-v1`
- Committed and pushed both repos; ArgoCD deployed automatically

**Outcome:** Mosquitto running at `192.168.0.71:1883`. kreceiver consuming from both UDP and
MQTT simultaneously.

---

## Task 10 — Verify MQTT End-to-End

**Request:** *"Could you verify the MQTT end-to-end?"*

**What happened:**
- Checked pod status (`kubectl get pods/svc -n robo-services`) — both pods Running, 0 restarts
- Read kreceiver startup logs — confirmed:
  `MQTT connected to mosquitto.robo-services.svc.cluster.local:1883, subscribing to telemetry/ingest`
- Published a test message via `mosquitto_pub` to `192.168.0.71`, watched kreceiver logs in
  real time, confirmed the message appeared as:
  `published topic=telemetry.raw.gps device=TEST_MQTT_001 source_type=gps`

**Outcome:** Full MQTT path verified live. ✅

---

## Summary of Infrastructure Built

| Component | Technology | Notes |
|---|---|---|
| Field device | ESP32 + u-blox NEO-M8N + MPU-6050 | GPS + IMU, UDP→MQTT |
| Transport | Mosquitto MQTT `192.168.0.71:1883` | LoadBalancer, MetalLB |
| Ingestion | `kreceiver` Python service | UDP + MQTT concurrent |
| Message broker | Apache Iggy | Streams/topics/consumer groups |
| Stream processing | Apache Flink 2.0 (Kubernetes Operator) | FlinkDeployment |
| Derivation | `speed_flink_job` Java/Maven | 10s tumbling window, event time |
| Deployment | ArgoCD + Helm | git push to `main` = live |
| DNS | CoreDNS zone `robo-services.local` | `kreceiver`, `mqtt` entries |

## Commits in This Session

| Hash | Description |
|---|---|
| `173f0ef` | Live speed derivation job rollout |
| `3d50ede` | Add Flink job service account and RBAC |
| `4111318` | Ensure speed job Iggy metadata at startup |
| `261c4e4` | Fix speed job Iggy preflight parsing |
| `f64e9f9` | Fix speed job JSON compatibility |
| `ed1ea48` | Bypass Iggy JSON schema, use custom deserializer |
| `8d4a609` | Decode wrapped base64 GPS payloads |
| `9000d3c` | Use child-first classloading for speed job |
| `4381d07` | Use custom TCP sink for speed derivation |
| `0660a10` | Suppress IggyPartitionSplitReader polling log spam |
| *(mqtt)* | MQTT transport: Mosquitto + kreceiver dual-mode |

---

*Document generated 2026-05-28. Repository: `dwilson2547/robo-services`.*
