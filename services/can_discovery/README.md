---
tier: service
domain: robotics
---

# can-discovery

DBC discovery for vehicles without a DBC
([plan](../../docs/plans/2026-11-04-dbc-discovery.md) §2). Rust.

```
telemetry.discovery.can ─┐
telemetry.raw.gps ───────┼─► can-discovery ─► registry session_discoveries
telemetry.raw.imu ───────┤   (per finished session)
telemetry.session ───────┘
```

can-decode sends a session's CAN here, undecoded, when its vehicle has no DBC.
can-discovery buffers each session until it finishes (2 minutes after its
`session_end` with nothing new, or 30 minutes idle), then writes one row:

- **inventory**: per CAN id, its count, rate, median period and DLC; per byte, a
  class (`constant`, `counter`, `varying`) and its range; per bit, the flip rate
  and entropy.
- **candidates**: per id and reference channel, bit fields that track it, from a
  port of dbc-forge's `SignalCorrelator`:
  - fields of 8, 12 and 16 bits, Intel and Motorola, signed and unsigned;
  - a 200 ms grid and a ±2 s lag search, keeping |r| ≥ 0.8;
  - factor and offset from a regression.

  The reference channels are GPS speed, its derivative, the busiest gyro axis
  (yaw) and speed × yaw.

Sessions with no discovery traffic are discarded. Nothing becomes a DBC
automatically: the results are for review.

**Restarts.** It reads the four topics by explicit offset as consumer
`can-discovery`. Each stored offset stays below the first message of that
topic's oldest open session, so a restart replays every session not yet
analysed. A consumer with no stored offset starts at the end of each topic
(`CAN_DISCOVERY_START=earliest` to replay all of it). If a topic grows while
the reader stays put for 2 minutes, the process exits, and Kubernetes
restarts it from the stored offset.

## Backfill: a past session

```bash
kubectl -n robo-services exec deploy/can-discovery -- can-discovery backfill <source_session> <device_id>
```

This analyses one session recorded before discovery existed, or under the
wrong vehicle, by reading the raw topics instead of the discovery topic:
- `telemetry.raw.can` (`CAN_DISCOVERY_RAW_CAN_TOPIC`), plus GPS, IMU and
  session events;
- each topic is binary-searched for the session's start (the session id is its
  start in epoch ms, and records are close to time order), then read forward
  and filtered to the session.

It writes the result like any other session and exits. It never stores
offsets.

## Config (env)

| Var | Default | |
|---|---|---|
| `IGGY_CONNECTION_STRING` | required | |
| `IGGY_STREAM` | `can-pub-sub-probe` | |
| `CAN_DISCOVERY_CONSUMER` | `can-discovery` | consumer whose offsets are stored |
| `CAN_DISCOVERY_TOPIC`, `_GPS_TOPIC`, `_IMU_TOPIC`, `_SESSION_TOPIC` | `telemetry.discovery.can`, `telemetry.raw.gps`, `telemetry.raw.imu`, `telemetry.session` | |
| `CAN_DISCOVERY_START` | `latest` | where a consumer with no stored offset starts |
| `MAX_CAN_FRAMES` | `2000000` | CAN frames kept per session; past it, counted only |
| `END_QUIET_S` / `IDLE_S` | `120` / `1800` | when a session counts as finished |
| `CHECK_EVERY_S` | `30` | how often finished sessions are analysed and offsets stored |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | | the registry database |
| `CAN_DISCOVERY_LOG_LEVEL` | `info` | |

## Build, test

```bash
cargo test --release                       # unit tests
cargo test --release -- --ignored --nocapture   # timing of a synthetic 10 min drive
docker build -f services/can_discovery/Dockerfile -t dwilson2547/robo-services-can-discovery:<tag> .   # from the repo root
```

`Cargo.lock` pins the iggy 0.10 dependency tree to versions that build on
rustc 1.92. A fresh resolve picks a `compio-buf` that needs a newer compiler.

Smoke-tested end to end on 2026-11-04 against `apache/iggy:0.8.0` and the
registry schema at migration 0011:
- **The drive:** a synthetic 3-minute no-DBC drive, with speed planted in
  0x202 as Motorola `31|16` at 0.01 km/h, sent 300 ms behind the GPS.
- **Found:** 0x202 `31|16` Motorola, factor 0.00999, r 0.99997, lag -400 ms.
  The lag is only good to one 200 ms grid step.
- **Inventory:** the counter id's counter and constant bytes were classed
  correctly.
- **Restart:** a second run resumed from the stored offsets and wrote nothing
  new.

The local iggy 0.8 container needs `--security-opt seccomp=unconfined` (it
uses io_uring) and `IGGY_TCP_ADDRESS=0.0.0.0:8090`.
