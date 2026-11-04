---
title: Rust stateless iggy consumer pattern (can-decode-rs experiment)
date: 2026-11-03
tags: rust,iggy,streaming,can-decode,offsets
---

services/can_decode_rs is the first Rust streaming app: a stateless shadow of the Python can_decode. Durability = iggy consumer-group offset committed only after publish succeeds (every 100 msgs + on SIGTERM), publish retries forever, watchdog exits non-zero when iggy 0.8 silently stops polling after a server restart. Dedup is NOT in-app: decode is deterministic so replays produce identical output; outputs carry x-source-offset and downstream dedups on it or on the natural key (source_session, device_id, base_timestamp_us, can_id, dt_us) — same idempotency key session_tracker/vehicle_state already use. When a vehicle has no DBC, route unmatched frames to a dbc-detection topic and run the matcher in Rust; can-decode-rs is the template. Verified: 18 unit tests incl. cantools ground-truth vectors + live iggy smoke (0x0C9 frame -> EngineRPM 795.0 on telemetry.decoded.can.rs).
