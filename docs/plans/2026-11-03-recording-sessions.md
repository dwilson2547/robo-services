# Recording sessions (phone gateway)

Status: **planned** (2026-11-03). Nothing here is built unless marked ✅ verified.

A *session* is one recording: from **Start capture** to **Stop capture** on the
phone gateway app, in one vehicle, in one mode. Today there is no such object:
every phone frame lands in source session `proto-live`, so all drives of a device
share one stream and nothing says where a drive starts, ends, or whether it
arrived whole. Sessions are the base for the rest of the companion-app work
(vehicle detection, per-vehicle DBC, trip mode, track metadata, drive history).

## What exists today (✅ verified 2026-11-03)

- `TelemetryFrame.session_id` (uint64, field 4) is already in `telemetry.proto`.
  kreceiver uses `str(session_id)` as `source_session` when it is non-zero, and
  `proto-live` when it is zero (`_source_session_from_frame`). Every derived
  message then carries `session_id = "<source_session>:<device_id>"`.
- The phone app leaves `session_id` at 0, so its frames are `proto-live`.
- The lap-segmentation job keys state by `source_session:device_id`; the app's
  `lap_anchor` JSON sets `source_session: "proto-live"` to match.
- The parquet archive writer partitions objects by `source_session`.
- kreceiver's JSON path routes by the `source` prefix (`infer_source_type`):
  `imu`, `rtk`, `can`, everything else to `telemetry.raw.gps`. There is no
  session/event topic.
- The registry has `users`, `devices` (with `last_mode` race/trip from a `-r`/`-t`
  device-id suffix, heartbeated by kreceiver), per-device profiles, per-mode
  pipeline assignments, `tracks` (OSM or user-built, with `start_line`), and
  `/analytics/sessions` built from `telemetry.track_position_samples` grouped by
  `source_session, device_id, track_id`.
- The track-position job matches every GPS sample against every track; nothing
  tells it which track a session is at.
- mqtt-auth knows the authentik `sub` of every phone connection (username must
  equal `sub`), but no payload or topic carries it downstream.

## Design

### 1. Session id

- The app sets `session_id = capture start time in epoch milliseconds` on every
  frame of the capture. Unique per device (two captures cannot start in the same
  millisecond), sortable, readable, fits uint64, and needs no server round trip,
  so a capture can start with no signal.
- Downstream identity stays kreceiver's `"<session_id>:<device_id>"`; no
  pipeline change is needed for frames to separate by drive.
- The `lap_anchor` JSON uses the same `source_session` (the decimal session id),
  so anchors keep landing in the lap job's key for that drive.

### 2. Session events

Two JSON events, sent on the same MQTT topic as the frames, so they share the
app's offline buffer and frame log and reach the pipeline in order with the data.

`session_start` (first message of a capture):

| Field | Meaning |
|---|---|
| `source` | `phone-gateway:session` (new source type, see §4) |
| `message_type` | `session_start` |
| `device_id`, `source_session` | as frames |
| `captured_at` | start time (ISO) |
| `mode` | `race` \| `trip` |
| `vehicle_id` | registry vehicle, or null until vehicles exist (§6) |
| `track_id` | registry track the user picked, or null (§7) |
| `can_filter` | CAN ids sent to the dongle (empty = all) |
| `dongle` | firmware version and BLE address, or null if no dongle |
| `app_version`, `phone` | build and phone model |

`session_end` (last message; sent on Stop capture):

| Field | Meaning |
|---|---|
| `message_type` | `session_end` |
| `captured_at` | stop time |
| `frames` | frames the app built (one per second with any data) |
| `first_frame_us`, `last_frame_us` | `base_timestamp_us` of first and last frame |
| `imu_seconds_dropped`, `imu_points_filled`, `late_samples` | the app's own loss counts |
| `dongle_drops` | sum of the dongle's `can_drop` / `imu_drop` over the session |
| `reason` | `user_stop` \| `dongle_lost` \| `app_killed_recovered` |

If the app is killed mid-capture, it finds the unterminated session in its frame
log on next start and sends `session_end` with `reason: app_killed_recovered` and
the counts it can reconstruct from the log.

### 3. Completeness

A session's state, kept by the session consumer (§5):

- `open`: `session_start` seen, no `session_end` yet.
- `complete`: `session_end` seen and frames received == `frames`.
- `incomplete`: `session_end` seen, frames received < `frames` (the difference is
  what a backfill upload would recover from the phone's frame log).
- `unterminated`: no frame and no `session_end` for 30 min.

Frames received are counted from per-frame receipts: kreceiver publishes one
small `frame` record (base timestamp, GPS/IMU/CAN sample counts) to
`telemetry.session` for every frame that carries a session id, and the tracker
stores each `(session, base_timestamp_us)` once, so MQTT redelivery does not
inflate the count. (kreceiver emits nothing frame-level otherwise; counting from
the sample topics would mean reading ~700 messages/s to count seconds.)

State is derived on read, not stored, so `unterminated` needs no timer.
Completeness is reported as a percentage as well as a state: some loss on a phone
link is expected, and `incomplete` with 99.5 % is a different thing from 40 %.

A session whose `session_start` was lost is still identifiable: its frames carry
the id. It shows as `open` with unknown mode/vehicle until the end event or a
backfill fills it in.

### 4. kreceiver

- New source type `session` (`source` prefix `phone-gateway:session`) routed to a
  new Iggy topic `telemetry.session`. Today unknown prefixes go to
  `telemetry.raw.gps`, which the lap and track jobs read: session events must not
  land there.
- No change to frame handling; `session_id` already flows.

### 5. Registry: sessions

The registry stores sessions, since it already owns devices, tracks and the
analytics endpoints.

- New table `sessions`: `source_session`, `device_id`, `user_sub`, `mode`,
  `vehicle_id`, `track_id`, `started_at`, `ended_at`, `frames_expected`,
  `frames_received`, `state`, the `session_end` loss counts, `start_event` and
  `end_event` (JSONB, as received).
- `services/session_tracker` reads `telemetry.session` (Iggy consumer group,
  committed offsets) and writes the registry's Postgres directly. The registry
  owns the schema (Alembic `0004`: `sessions`, `session_frames`) and serves
  `GET /api/sessions` and `GET /api/sessions/{device_id}/{source_session}`.
- `/analytics/sessions` (Timescale, a different database) is not merged with this
  yet; the history tab work will decide how the two lists combine.
- The device-id `-r`/`-t` mode suffix stays for the existing dongle devices; the
  phone sends its mode in `session_start` instead.

### 6. Vehicles

- **4a (✅ done 2026-11-03, registry `20261103-vehicles-v2`; verified with an
  87 s capture in the Impala: session `complete` 87/87 with `vehicle_id` 1 and
  the user stamped): registry `vehicles` (owner, name, year/make/model,
  CAN filter; migration 0005, which also adds `users.sub`). The phone reaches it
  at `robo.danwils.com/api/app/*`, the only part of the registry with a public
  route; every call there needs the user's authentik access token (checked like
  mqtt-auth: JWKS signature, iss, aud `phone-gateway`). A user's first call
  creates their registry user, or links the existing one with the same email
  (authentik userinfo). A user created before authentik shared an email (a
  token from before the app asked for the `email` scope) keeps re-reading
  userinfo until it gets one, then is merged into the existing user with that
  email. Endpoints: `GET /me`, `GET|POST /vehicles`,
  `PUT|DELETE /vehicles/{id}`, all scoped to the caller.
- App: picks the vehicle at Start capture (remembers the last one per dongle),
  sends its `vehicle_id` in `session_start`, and sends its filter to the dongle.
- **4b (built 2026-11-03):** registry `dbcs` (name, content, sha256; migration
  0006) and `vehicles.dbc_id`; LAN API `GET|POST /api/dbcs`, `GET|PUT
  /api/dbcs/{id}`; `GET /api/sessions/{device}/{session}/dbc` (session -> vehicle
  -> DBC, 404 with a reason when there is none); phone API `GET /api/app/dbcs`
  and `dbc_id` on vehicles. can-decode resolves each session's DBC from the
  registry (cached per session for 5 min, each DBC compiled once per content
  hash; 15 s before asking again when there is no answer yet) and falls back to
  the ConfigMap DBC; decoded and unknown records carry `payload.dbc`, the DBC
  they are relative to.
- Later: recognise the vehicle from the CAN ids seen in the first seconds (the
  dongle is listen-only, so no VIN request), confirm once, remember; and use
  labelled driveway captures (indicators, steering, throttle, brakes, gears) to
  find signals on unknown buses and suggest a DBC.

### 7. Track (outline only)

- At Start capture in race mode the app offers the registry's tracks, closest to
  the current fix first, and sends the pick as `track_id`.
- The track-position job uses a session's `track_id` when present and only falls
  back to matching against every track when it is null.

### 8. User

- The user is stamped by kreceiver, never claimed by the app: MQTT does not tell
  subscribers who published (the broker knows, but forwards only topic and
  payload), so the identity travels in the topic. Each user publishes to
  `telemetry/ingest/<sub>`; mqtt-auth allows a user only their own topic (one
  rule in code, nothing to configure per user: adding someone to the
  `phone-gateway-users` group is enough); the anonymous LAN listener may read
  those topics but not write them; kreceiver subscribes to
  `telemetry/ingest/+` and sets `user_sub` (and header `x-user-sub`) from the
  topic. The session tracker stores it on the session.
- The shared `telemetry/ingest` is closed to phones (`allowBareIngestTopic:
  false`); LAN loggers on the anonymous listener still use it, with
  `user_sub: null`.
- Registry users are linked by `sub` once the registry validates the same
  authentik tokens (needed before the phone reads vehicles, tracks or history):
  a user's first authenticated call creates or links their registry user from
  the token's `sub`, name and email.

## Phone frame log (decided 2026-11-03)

- One file per session, `files/frames/session-<session id>.bin` (was one per UTC
  day). Recovery, a later backfill upload and deletion all work per session.
- Measured on the Impala with the 23-id filter: about 9.4 KB per second (34 MB per
  driving hour), 0.37 KB/s without CAN; gzip shrinks it to 28 %.
- Kept until the server confirms a session `complete` (needs step 2 and a read
  API for the phone), then deleted 30 days later (planned).
- Backstop now: at each capture start the oldest logs are deleted while the total
  is over 5 GB; the current session and one being recovered are never deleted.

## Build order

1. App: session id on frames and anchors; `session_start` / `session_end`;
   recovery of unterminated sessions. kreceiver: `session` source type and topic.
   ✅ Done 2026-11-03 (robo-services 652700c, kreceiver `20261103-proto-v3`).
   Verified with a 28 s capture: `session_start`, 28 frames, `session_end` (acked)
   in that order; both events on `telemetry.session`, GPS on `telemetry.raw.gps`
   under `1793726754694:phone-…`, `frames: 28` matching what kreceiver received.
2. Registry: `sessions` table and consumer; `/analytics/sessions` from it.
   ✅ Done 2026-11-03 (kreceiver frame receipts in `20261103-proto-v4`, registry
   `20261103-sessions-v1` at migration 0004 with `/api/sessions`,
   `services/session_tracker` `20261103-v1`). Verified with a 9 min capture:
   `complete`, 537 of 537 frames, matching `session_end`; receipts summed to
   26,800 IMU samples (42 filled points) and 248,483 CAN frames.
3. Per-user topics (mqtt-auth ACL, kreceiver subscription, `user_sub`).
   ✅ Done 2026-11-03 (mqtt-auth `20261103-v3`, kreceiver `20261103-proto-v5`,
   session-tracker `20261103-v2`, anonymous-listener ACL in cluster-config).
   Verified: a capture from the updated app landed `complete`, 28/28 frames,
   with `user_sub` set; an anonymous publish to a user topic on 1883 never
   reached kreceiver. The shared topic is closed to phones
   (`allowBareIngestTopic: false`).
4. Vehicles, then per-vehicle DBC and filter.
5. Track selection, and the track-position job using it.
6. Trip mode, then the history tab.

## Open questions

- Should the frame count in `session_end` be checked against frames in the
  archive (parquet) instead of a live counter, so the check survives a consumer
  restart without its own offsets?
- Whether `session_start` should wait for the dongle (so `dongle` and the filter
  are known) or start immediately and send the dongle details in a later
  `session_update` event.

## Known issue found while surveying

kreceiver places GPS fixes at `base_timestamp_us + index * sample_period_us` and
ignores `GpsSection.dt_us`, which the phone app sets to each fix's real offset.
Phone GPS times are therefore off by up to the spacing jitter of the phone's fixes
(⚠ unverified how much in practice). Small fix, independent of sessions.
