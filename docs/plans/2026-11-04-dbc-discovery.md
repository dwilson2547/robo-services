# DBC discovery: a topic and a service for vehicles without a DBC

Status: step 1 **built** (2026-11-04), steps 2–3 proposed.

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

## 2. can-discovery: a new service

It reads `telemetry.discovery.can` plus the raw GPS and IMU topics in its own
consumer group. Per session:

- **An inventory of ids:** for each one, its count, rate, DLC, and which bytes
  and bits change. Constant, counter and checksum bytes are flagged the way
  dbc-forge's byte view shows them.
- **Correlation:** a port of `SignalCorrelator` run against GPS speed, IMU
  longitudinal and lateral acceleration, and yaw rate. Each candidate is kept
  with its start bit, length, byte order, signedness, r, lag, factor and
  offset.
- **Results go to the registry** in a new table (per vehicle, per session,
  per candidate) for review. They become a DBC only by a human promoting them
  into `embedded/phone-dbc-repo` and assigning it to the vehicle in the
  registry. Nothing is auto-promoted.
- **Batch, not streaming:** it runs per session once the session has ended,
  using the same "ended and quiet for 2 minutes" rule drive-summary uses.
  Correlation needs the whole drive, and a vehicle's evidence accumulates
  across drives.
- **Re-running:** the raw CAN is also archived to parquet, so discovery can
  re-run over old drives when the correlator improves, without replaying
  iggy.

The brute-force search is CPU-bound and self-contained, so it's a good first
Rust service. A Python version can run in parallel in another consumer group
to compare the two.

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
