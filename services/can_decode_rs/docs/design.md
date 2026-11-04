# Design notes: stateless Rust consumer on Iggy

## Why stateless + committed offsets

The decode is a pure function of (raw record, DBC), so the app keeps no
in-memory state. All durability comes from the Iggy consumer group offset:

1. poll with `PollingStrategy::next()`, `auto_commit=false`
2. process + publish each message (publish retries until accepted)
3. `store_consumer_offset` every N messages and on shutdown

A crash replays at most N messages (at-least-once); nothing is ever skipped.
This is exactly the Python `can_decode` contract, so the two can be swapped or
run side-by-side.

## De-duplication

Duplicates arrive from MQTT redelivery upstream (QoS 1 republish), not from
the consumer replay — replay is already collapsed by the committed offset.
Since decode is deterministic, a duplicate input yields a byte-identical
output, so:

- outputs carry `x-source-offset` (input partition offset); consumers that
  must not see a frame twice dedup on it;
- otherwise consumers dedup on the natural key
  `(source_session, device_id, base_timestamp_us, can_id, dt_us)`, the same
  idempotency key `session_tracker` uses for frame receipts and
  `vehicle_state` uses for its upsert primary key.

A stateful in-app dedup cache was deliberately not built: it would break the
"stateless" goal and buys nothing over keying downstream, which is already
idempotent.

## Watchdog

iggy 0.8 consumers silently stop polling after a server restart (they
reconnect but never rejoin the group). The process therefore watches topic
message count vs. last consumed offset and exits non-zero on a stall so the
supervisor restarts it. Ported from `can_pub_sub_probe.consumer_watchdog`.

## Future: DBC detection topic

When a vehicle has no DBC set, unmatched frames can be routed to a
detection topic; a Rust app there will try to match unknown traffic against
known DBCs (message-id overlap + signal plausibility) or extract decodable
signals. can-decode-rs is the template: stateless, committed offsets, watchdog.
