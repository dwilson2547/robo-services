---
tier: experiment
domain: robotics
---

# can-decode-rs

Rust shadow of [`services/can_decode`](../can_decode): consumes Iggy
`telemetry.raw.can`, decodes each frame with a DBC, and publishes to a
parallel output topic. Built as the first Rust streaming experiment — it runs
alongside the Python service (nothing existing is removed) so outputs can be
diffed byte-for-byte and throughput compared.

```
telemetry.raw.can ──► can-decode-rs (consumer group can-decode-rs) ──► telemetry.decoded.can.rs
                                                                    └─► telemetry.unknown.can.rs
```

## Guarantees (same contract as the Python can_decode)

- **Consumer-group offsets, committed after side effects.** Polls with
  `auto_commit=false`; `store_consumer_offset` every `COMMIT_EVERY` messages
  *after* they are processed and published, and once more on SIGTERM/SIGINT.
  A crash re-decodes at most `COMMIT_EVERY` frames, never skips any.
- **Publish retries forever** until Iggy accepts the message, so the offset is
  never committed past a frame whose publish failed (at-least-once).
- **Watchdog**: iggy consumers do not rejoin their group after a server
  restart, so a watchdog compares the topic's message count with the last
  consumed offset every `WATCHDOG_INTERVAL_S` and `exit(1)`s on a stall —
  Kubernetes restarts the pod, which resumes from the committed offset.
- **Dedup**: the app is stateless; replayed duplicates produce identical
  output. Each output message carries an `x-source-offset` header (the input
  partition offset), so downstream consumers can dedup on it, or on the
  natural key `(source_session, device_id, base_timestamp_us, can_id, dt_us)`
  like `session_tracker` does for receipts.

## Config (env)

| Var | Default | Meaning |
|---|---|---|
| `IGGY_CONNECTION_STRING` | (required) | e.g. `iggy+tcp://iggy:iggy@127.0.0.1:8090` |
| `IGGY_STREAM` | `can-pub-sub-probe` | Iggy stream |
| `CAN_DECODE_GROUP` | `can-decode-rs` | consumer group; its stored offset is where a restart resumes |
| `CAN_DECODE_INPUT_TOPIC` | `telemetry.raw.can` | |
| `CAN_DECODE_OUTPUT_TOPIC` | `telemetry.decoded.can.rs` | decoded records |
| `CAN_DECODE_UNKNOWN_TOPIC` | (empty = drop) | unknown/unparseable frames |
| `DBC_PATH` | (required) | DBC file to decode with |
| `CAN_DECODE_DEFAULT_DBC_NAME` | `default` | name stamped into `payload.dbc` |
| `COMMIT_EVERY` | `100` | offset commit interval (messages) |
| `BATCH_LENGTH` | `200` | poll batch size |
| `POLL_INTERVAL_MS` | `50` | sleep when a poll returns empty |
| `WATCHDOG_INTERVAL_S` / `STALL_AFTER_S` | `30` / `120` | watchdog cadence / stall threshold |
| `REPORT_INTERVAL_S` | `60` | counters log cadence |
| `CAN_DECODE_LOG_LEVEL` | `info` | tracing filter |

Not yet implemented (follow-ups): per-session DBC resolution from the
registry (`REGISTRY_URL`, as in the Python service), and routing unmatched
frames to a DBC-detection topic when a vehicle has no DBC set — that topic is
where a Rust detection algorithm will later try to match unknown traffic
against known DBCs.

## Build / test / run

```bash
cargo build --release
cargo test          # unit tests incl. cantools ground-truth vectors
./start.sh          # or: cargo run --release (env above)
./kill.sh
```

Smoke-tested against a local `apache/iggy:0.8.0`: published one
`telemetry.raw.can` frame (`0x0C9 840c6c07004000`), consumed it as group
`can-decode-rs-smoke`, and read back `telemetry.decoded.can.rs` with
`EngineRPM 795.0` matching cantools exactly.

## Layout

- `src/main.rs` — iggy wiring: connect, ensure topics, join group, poll loop, commit, watchdog, signals
- `src/config.rs` — env settings
- `src/envelope.rs` — the `{"headers", "payload_b64"}` message envelope, byte-compatible with `can_pub_sub_probe.iggy_backend`
- `src/dbc.rs` — minimal DBC parser (`BO_`/`SG_`, extended-id flag fix like the Python loader)
- `src/decode.rs` — bit extraction (Intel + Motorola), scale/offset, signed; `decode_record` mirroring `app/decode.py`
- `src/watchdog.rs` — port of `consumer_watchdog.ProgressWatchdog`
- `docs/design.md` — why stateless + committed offsets, and the dedup story
