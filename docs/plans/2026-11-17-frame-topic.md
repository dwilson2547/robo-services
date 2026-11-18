# Frame topic: one message per TelemetryFrame

Status: **planned** (agreed with the user 2026-11-17; nothing built yet).

## Why

kreceiver splits each one-second `TelemetryFrame` into one Iggy message per sample: per CAN
frame, per IMU point, per GPS fix, plus a receipt on `telemetry.session`, so hundreds of messages
per frame on a busy bus (exact count per session ⚠ unverified; measure it in step 1). Most
consumers then rebuild the second:

| Consumer | What it does with per-sample messages today |
|---|---|
| `vehicle_state` | Rebuilds each frame (`FrameTable`) from tagged samples and checks it against the receipt before writing |
| `archive_writer` | Stores the samples by topic; a whole second has to be joined again across topics |
| `can_discovery` | Lines GPS, IMU and session topics up again by time |
| `speed` (Flink) | Regroups samples into tumbling event-time windows |
| `can_decode` | Per CAN message. Would read just as well from frames |
| `lap`, `track_position` (+ sink) (Flink) | Per GPS fix: a state machine and a per-fix projection. The only real per-sample uses |

The split costs IOPS (one write and one read per sample per consumer), and the regrouping needs
ordering, lateness and "is this frame complete" logic in every consumer. The user's call: keep
per-sample topics only where they help, and expect them to fade out.

The race-logger pipelines (speed, lap, track position) were built in a weekend and have only seen
spoofed data or static fixes. Keeping them alive is cheap, so they stay. Reshaping them is not a
goal.

## Design

1. **kreceiver publishes each accepted frame once to `telemetry.frames`**, alongside the topics
   it publishes today. No existing consumer changes in this step.
2. **The message is protobuf, not JSON:** a small wrapper in `telemetry.proto`, e.g.
   `IngestedFrame { TelemetryFrame frame; string user; string device_id; uint64 received_at_us;
   string source_session; }`. The frame stays byte-for-byte what the device sent, and the wrapper
   carries what kreceiver adds today (the user stamp, receive time, session key). Python, Java
   (Flink) and Rust consumers already generate from this file or can.
3. **One frame is its own receipt:** a consumer reading frames needs no completeness check. The
   `telemetry.session` frame receipts stay for `session_tracker` until it reads frames too.
4. **GPS source choice moves into consumers:** with both `gps` (phone) and `dongle_gps` in the
   frame, each consumer picks by presence and fix quality. A shared helper per language (Python
   first) gives the default rule: dongle fixes with `fix_type >= 2`, else phone. Until the
   per-fix topic goes, kreceiver keeps forwarding the better source to `telemetry.raw.gps` with
   `gps_source` (live since 2026-11-17).

## Order

1. kreceiver: the `IngestedFrame` wrapper, the publish to `telemetry.frames`, tests. Deploy, then
   check one real capture's frames on the topic.
2. `vehicle_state` onto frames. This deletes `FrameTable` and the receipt matching. Rows must
   come out the same as today for the same session: replay one and compare.
3. `archive_writer`: one Parquet row group (or file) per frame window. The layout is an open
   question: keep columnar per-sensor tables, or store the frame bytes.
4. `can_discovery`, then `can_decode`.
5. `speed`: either onto frames or retired in favour of `vehicle_state`'s speed. To decide then.
6. `lap`, `track_position`: stay on `telemetry.raw.gps` until there's a reason to move.
7. Once a `telemetry.raw.*` topic has no reader, kreceiver stops publishing it.

## Open questions

- Frame size on the topic with a full CAN load. Measure from a real session before sizing
  retention; don't estimate it.
- Iggy partitioning: everything is on partition 0 today. Whether to key frames by device is to be
  decided once more than one device sends at a time.
