# DBC discovery: a topic and a service for vehicles without a DBC

Status: step 1 **deployed** (2026-11-04), step 2 **built** (2026-11-04), step 3 proposed.

A vehicle without a DBC needs manual review before its decodes can be trusted.
Its CAN should go to a topic of its own, where a discovery service gathers what
a human needs to write the DBC. It should not be decoded with a guessed DBC.
Each piece stays its own microservice, so any of them can be swapped for a Rust
version, or run alongside one in a separate consumer group.

## What happens today (✅ verified 2026-11-04, from the code)

- can-decode asks the registry for each session's DBC
  (`GET /api/sessions/{device}/{session}/dbc`, cached per session). It decodes
  each CAN message with that DBC. If there is no answer, it uses the default
  DBC from the `can-decode-dbc` ConfigMap, which is currently the Impala's
  (`services/can_decode/app/dbc_resolver.py`).
- A message the DBC can't decode goes to `telemetry.unknown.can`. That covers
  two cases: the DBC has no message for the id, or the data doesn't fit the
  message's layout. These records carry the raw `can_id`/`data_hex`, the frame
  base, the DBC they missed against and every envelope field. vehicle-state
  reads the topic only to count them toward a frame's receipt.
- The registry's 404 already says which case applies: `session not found`,
  `session has no vehicle` or `vehicle has no DBC`. can-decode doesn't tell
  them apart; all three fall back to the default DBC.
- The problem this causes: a car without a DBC is decoded as an Impala. Ids
  that happen to match yield plausible-looking but wrong signals. These reach
  `telemetry.decoded.can`, vehicle_state and the drive summary.
- `embedded/dbc-forge` (the phone companion) already has
  `data/decoder/SignalCorrelator.kt`. It slides candidate bit fields
  (start bit, length, byte order, signedness) over one CAN id's frames,
  resamples them and a reference channel onto a grid, and lag-searches for the
  best Pearson |r|. A regression then gives factor and offset. Its plan is
  `docs/sensor_correlation_plan.md` in that repo.

## 1. Routing in can-decode

The DBC answer for a session is one of three:

| Registry answer | Today | Proposed |
|---|---|---|
| DBC found | decode with it | unchanged |
| `vehicle has no DBC` / `session has no vehicle` | default DBC | every CAN message to `telemetry.discovery.can`, not decoded |
| DBC doesn't parse | default DBC | discovery, reason `DBC '<name>' does not parse` |
| `session not found` | default DBC | dropped; asked again every second |
| registry down | default DBC | retried with backoff; the message waits (the watchdog restarts the pod if it goes on) |

- A discovery record looks like an unknown record (raw id, data, DLC, frame
  base, envelope) with `source_type: can_discovery` and the registry's reason.
  The vehicle comes from the session in the registry.
- `telemetry.unknown.can` stays, for ids missing from a DBC we do have. Each
  record gains a `reason`: `no_dbc_message` or `layout_mismatch`.
- The default DBC is used only for records with no session (`proto-live`).
- vehicle-state also reads the discovery topic and counts it toward receipts,
  as it does the unknown topic, so frames still close as complete. A drive
  without a DBC then gets GPS/IMU rows and a summary from those alone
  (distance, speed, g-forces). The CAN-only lines are left out, as they
  already are when a metric is missing.

Messages from before session-tracker has registered the session are dropped
(decided 2026-11-04): recording starts a few seconds before driving, so they
carry nothing a drive needs. Their frames close incomplete, as any short frame
does.

## 2. can-discovery: a new service (Rust)

`services/can_discovery`, Rust (iggy crate 0.10, which works against the 0.8
server: `services/can_decode_rs` smoke-tested it).

**Input.** It reads `telemetry.discovery.can`, `telemetry.raw.gps`,
`telemetry.raw.imu` and `telemetry.session` by explicit offset as consumer
`can-discovery`. It buffers each open session in memory:
- CAN as (time, id, DLC, data), with time = frame base + `dt_us`;
- GPS speed;
- IMU gyro.

Only sessions with discovery traffic are analysed; the rest are discarded when
they finish.

**Restarts.** Each topic's stored offset stays below the first message of its
oldest open session, as vehicle-state does it, so a restart replays every
session not yet analysed. Each session has a frame cap (`MAX_CAN_FRAMES`);
past it, frames are counted, not kept.

**When a session is analysed.** After its `session_end` on `telemetry.session`
and 2 minutes with nothing new for it. Or after 30 minutes with nothing new and
no end, matching drive-summary's rule. Frames that arrive after the analysis
are logged and ignored in v1.

**Reference channels**, all on the phone's clock, chosen to need no knowledge
of how the phone or dongle is mounted:
- `speed_kph`: GPS `ground_speed_kph`;
- `long_accel_m_s2`: the time derivative of GPS speed;
- `yaw_rate_deg_s`: the gyro axis with the largest variance over the drive.
  In a car that's yaw. Its sign is arbitrary, which the signed r absorbs.
- `lat_accel_m_s2`: speed × yaw rate.

**Per id** (the inventory):
- count, rate, the most common DLC, and the median period;
- per byte: distinct values and a class (`constant`, `counter` for a steady
  step modulo 256 or 16, `varying`);
- per bit: flip rate and entropy.

**Correlation:** a port of dbc-forge's `SignalCorrelator`, unchanged in
behaviour:
- fields of 8, 12 and 16 bits, Intel and Motorola, signed and unsigned;
- a 200 ms grid, with the CAN value held and the reference interpolated;
- a ±2 s lag search for the best Pearson r, |r| ≥ 0.8;
- a regression for factor and offset;
- overlap suppression, keeping the top 12 per id per reference.

One addition: among equally good fields, the one whose low bits change wins. A
field shifted past the real one ends in bits that never move, and the Kotlin
picked it by its lower start bit.

Ids whose bytes are all constant are skipped. Ids run in parallel (rayon).

**Output:** one row per session in a new registry table, `session_discoveries`
(Alembic `0011`): `session_pk`, `version`, `can_frames`, `inventory` JSONB,
`candidates` JSONB, `computed_at`. It mirrors `session_summaries`, and is
written straight to the registry database as drive-summary does. Nothing is
auto-promoted to a DBC.

**Not in v1:**
- OTel metrics (logs only);
- a registry API for the results (step 3 adds it with the review view);
- combining evidence across a vehicle's drives;
- re-running from the parquet archive.

## 3. Review

A review view of the candidates, either a registry page or in the app:
- per id: the inventory, plus the top candidates per reference channel;
- the candidate plotted against its reference over the drive;
- promote or reject, with promotions written out as DBC lines for
  phone-dbc-repo.

## Out of scope

- Active diagnostics (UDS/OBD requests). Discovery stays passive, like
  dbc-forge.
- Automatic DBC assignment or publishing.
