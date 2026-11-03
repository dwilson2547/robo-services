# Changelog

## 2026-11-03 (recording sessions, step 1)

- Each capture is now a session (docs/plans/2026-11-03-recording-sessions.md).
  Its id is the capture's start time in epoch ms, set as
  `TelemetryFrame.session_id` on every frame, so kreceiver keys the frames
  `<session id>:<device>` instead of `proto-live:<device>` and drives no longer
  share one stream. Lap anchors use the same session id.
- Start capture sends `session_start` (mode, CAN filter, dongle, app version,
  phone; vehicle and track are null until those phases). Stop capture builds the
  seconds still open (it used to drop the last ~5 s), then sends `session_end`
  with the frame count, first/last frame time, the app's loss counters and the
  dongle's summed drop counts.
- Session events go through an outbox (`files/outbox/`): kept until the broker
  acks them, so an end sent while offline goes out at the next capture's start.
  Stop waits up to 5 s for the ack; the service stays up until it finishes.
- A capture the app never closed (killed) leaves `files/session-open`; the next
  capture rebuilds its `session_end` from the frame log with reason
  `app_killed_recovered`.

## 2026-11-03 (BLE callbacks in order, 2M PHY, 5 s grace)

- The second in-car drive with gap diagnostics showed 733 "dongle" gaps, 0
  "clock" gaps and 4436 late samples, while the dongle reported every line sent:
  - Notification callbacks arrived on four binder threads at once, so lines were
    processed out of order and DongleClock (not thread-safe) raced. GATT
    callbacks now run on one dedicated HandlerThread, in order.
  - The link ran on the 1M PHY at a 15 ms interval; the Impala's ~32 KB/s of
    lines (23-id filter) outgrows it where the bench simulator's ~20 KB/s did
    not. The app now asks for the 2M PHY; the status screen shows which PHY the
    link settled on.
  - Seconds now stay open 5 s (was 1.5 s), so a burst delivered seconds late
    still lands in its second instead of being dropped as late.
- Third in-car drive with this build (2M PHY confirmed, phone WiFi off): 121
  seconds, 1 IMU second dropped, 22 of ~6000 grid points filled, 0 late samples,
  23 short dongle gaps (56-142 ms), all callbacks on one thread. The second
  drive also had the phone on a weak WiFi signal, which likely starved BLE
  (shared 2.4 GHz radio); this run does not separate that from the fixes.

## 2026-11-03 (partial IMU seconds, gap diagnostics)

- IMU seconds with a few lost lines are kept. Before, one gap over 60 ms dropped
  the whole second's IMU section; on the first in-car drive (Impala, ~730 CAN
  frames/s through the 23-id filter) that discarded IMU for 122 of 207 seconds,
  although the dongle reported every IMU line sent and the phone received the
  notifications. Now each 50 Hz grid point takes the nearest sample, points with
  no sample within 20 ms are listed in the new `ImuSection.missing` (proto field
  30, additive), and only a second missing over half its points is left out.
- Gap diagnostics to find where the gaps come from: each IMU line's step in the
  dongle's counter is compared with its step in placed phone time. A large dongle
  step (dongle emitted late, or lines lost) counts as a "dongle" gap; a normal
  dongle step whose placed step differs by over 10 ms counts as a "clock" gap
  (DongleClock moved its offset). Counts are on screen, and the first 30 a minute
  are logged under tag `PhoneGatewayImu`.

## 2026-11-03 (dongle indicator, lap start marker)

- The app connects to the dongle whenever it is open, so a green / amber / red
  indicator shows whether the dongle is there (and sending) before capture
  starts. The service is now "linked" (screen up) and/or "capturing"; closing
  the app without capturing disconnects, freeing the dongle for dbc-forge.
- "Mark start/finish here" (while capturing, with a GPS fix under 3 s old)
  sends a `lap_anchor` JSON packet on the frames' MQTT topic. kreceiver routes it
  to telemetry.raw.gps in session `proto-live:<device>`, the same lap-job key as
  this app's GPS fixes, and the lap-segmentation job starts timing laps from it
  (checked against kreceiver's normalizer). Anchors are also appended to
  `files/frames/events.jsonl`.
- "Apply filter" re-sends the CAN filter to a connected dongle at once instead of
  waiting for the next connect.

## 2026-11-03 (bench run with the CAN simulator)

- First run on the phone (S21 Ultra) with the dongle and Impala CAN replay:
  phone -> pub-sub mosquitto -> kreceiver -> Iggy works end to end.
- A notification's text after its last newline is a line the BLE stack cut off;
  it is now dropped instead of parsed (a cut line could parse with a shortened
  timestamp counter and land seconds away from its time). The dongle was sending
  512-byte notifications on a 256-byte MTU; fixed in dbc-forge firmware 1.6.0.
- Requests high connection priority after connecting: interval 50 ms -> 15 ms.
- CAN filter: "CAN ids to forward" (hex, at most 64; empty = all) is sent to the
  dongle as `FILTER:` on every connect (needs firmware 1.6.0+).
- Status shows BLE MTU, cut-off notifications, and the last second's IMU sample
  count and longest gap.
- Samples are timed by the dongle's capture counter (unwrapped across
  notifications, offset from the least-delayed arrival), not by notification
  arrival: Android delivers BLE notifications in bursts, which showed as 90 ms
  IMU gaps.
- Logs and shows the dongle's 5-second STAT line next to what the app received
  in the same window. That comparison found the phone losing up to 15% of
  notifications at ~210/s; fixed in dongle firmware 1.6.4 (near-full
  notifications, ~50/s). Bench result: 90/90 seconds with CAN, GPS and full IMU.

## 2026-11-03

- Rewrote the app. The first scaffold never compiled (four compile errors and a
  manifest-merge failure) and its reconnect, sign-in and buffering logic had
  bugs; it was replaced rather than patched.
- Sign-in: AppAuth (authorization code + PKCE) against the authentik
  `phone-gateway` provider, redirect `com.robo.phonegateway:/oauth2redirect`.
  AuthState is persisted after every change and refreshes are serialized,
  because authentik rotates the refresh token on each use.
- Broker: one Paho client for the service's lifetime, QoS 1 over TLS, a fresh
  access token on every CONNECT. A refused CONNECT gets one forced token
  refresh, then exponential backoff. Paho's disconnected buffer (on disk, 600
  frames) carries short outages; frames published before the first connect
  are delivered once it connects (tested against mosquitto-go-auth + mqtt-auth).
- The broker is tried at `mqtt.danwils.com:48213`, then at `192.168.0.71:8883`
  for the home network (the router does not hairpin the WAN address). The
  certificate is checked against `mqtt.danwils.com` for both.
- Frames: one TelemetryFrame per wall-clock second, closed 1.5 s after it ends so
  late BLE batches still land. IMU on the proto's implicit 50 Hz grid (nearest
  sample per grid point) and 10 Hz quaternion grid; a second without full IMU
  coverage gets no IMU section rather than invented samples. GPS fixes carry
  `dt_us`; CAN is raw with `dt_us`. Built from the repo-root `telemetry.proto`,
  not a copy.
- Every frame is written to the frame log (one file per UTC day) before it is
  handed to the publisher.
- BLE: auto-connects to the remembered dongle (or the first NUS device seen) and
  keeps reconnecting while capture runs. Line parsing and the 16-bit timestamp
  reconstruction follow dbc-forge's, now as a tested pure function.
