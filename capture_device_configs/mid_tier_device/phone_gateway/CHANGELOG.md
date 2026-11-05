# Changelog

## 2026-11-04 (delete a drive)

- A drive's screen has "Delete drive", with a confirmation. It removes the
  drive's route, summary, per-second records and discovery results from the
  registry, then the phone's own copy: the frame log, upload progress, queued
  events and cached route. Without the phone part, the background upload would
  find the drive missing and send it all again.
- A drive that is still recording can't be deleted. The raw telemetry stays in
  the archive.

## 2026-11-04 (notifications)

- The gateway's notification says what the dongle is actually doing: "Dongle
  connected", "Looking for the dongle", "Connecting to the dongle", "Bluetooth
  is off" or "Dongle not connected". While recording, it's prefixed
  "Recording ·". It used to say "Connected to the dongle" whenever the app was
  open.
- The background catch-up shows its own notification while it sends: a
  progress bar per drive ("Uploading drive Wed 12:11: 40 of 417 s"), then
  "Uploaded N s from K drives", which clears itself after 5 minutes. Runs with
  nothing to send show nothing.

## 2026-11-04 (upload key)

- Uploads no longer depend on the authentik sign-in.
  - At sign-in, or at the first start of this version while signed in, the app
    creates a key in the phone's hardware keystore and registers its public
    half with the registry.
  - Recording uploads and the background catch-up then sign their own
    10-minute tokens with it. A lost or refused token refresh no longer stops
    uploads.
  - The private key can't leave the phone.
  - Signing out revokes the key at the registry and deletes it.
- A failed or cancelled sign-in now resets the sign-in state, so the next try
  starts clean. Recorded drives and the upload key are kept.

## 2026-11-04 (background upload catch-up)

- Drives now finish uploading on their own:
  - once there is a network after Stop (2 minutes after, or at the next app
    start), a background job asks the registry which seconds of each recorded
    drive it has, and sends the missing ones from the phone's frame log;
  - session start and end events still waiting go first;
  - it keeps trying, with backoff, across app restarts and reboots, until each
    drive is complete. A drive is given up on after 5 sends that change
    nothing.
- Before this, frames that didn't upload during a drive went out only at the
  next recording, and only the last ~10 minutes of them. The log was never
  re-sent.
- The catch-up uses its own broker connection, so it never disturbs a
  recording in progress.

## 2026-11-04 (sign-in crash, vehicle preselect)

- Fixed: signing in crashed, or failed with "user cancelled flow". AppAuth's
  redirect screen inherited the app's platform theme since the 2026-11-03
  rewrite and crashed when the browser handed the result back. It now has
  AppAuth's own AppCompat theme.
- Fixed: a car picked on the Drive screen could be switched back to the
  dongle's last car (the Impala) whenever the Drive screen was shown again with
  the dongle connected. The dongle's car is now preselected once per dongle
  while the app runs, so a car picked by hand sticks. Two Blazer drives were
  recorded as the Impala because of this.
- See `docs/issues/2026_11_04_phone_gateway_signed_out_and_sign_in_crash.md`,
  which also covers the sign-out itself: an authentik refresh lost on a weak
  signal.

## 2026-11-03 (processing lag)

- Session › All details shows how far behind real time the drive ran, once
  its summary has the figures (drives recorded from now on):
  - Upload lag: from the end of each second on the phone to the cluster
    receiving it (the phone and the network; high after recording offline);
  - Processing lag: from the cluster receiving a second to its snapshot row
    being written;
  - Summary ready: how long after the drive ended its first summary was.
  Older drives show none of these lines.

## 2026-11-03 (brake pressure)

- History › colour by Brake now grades brake line pressure (off, light, medium,
  firm, hard) instead of on/off, for vehicles whose DBC maps `brake_kpa`; others
  keep on/off. The route track API sends `brake_kpa` for this.
- Off is below 20 kPa: the Impala rests at about 8 kPa with the pedal up and
  brakes at 70–140 kPa (median 101) in the drive of 2026-11-03.
- Tapping the route shows the pressure (psi, or kPa in metric).
- Cached routes are versioned (`tracks-v2`): routes cached before `brake_kpa`
  existed are dropped and fetched again, otherwise old drives stayed on/off.

## 2026-11-03 (phone storage)

- Frame log cleanup:
  - a session's log is deleted once the registry has the session complete and
    it ended 30 or more days ago;
  - incomplete, unterminated and unknown sessions keep their logs until the
    5 GB cap;
  - it runs once each time the app opens (signed in), and from Settings ›
    Phone storage › Clean up now;
  - every deletion is logged.
- Settings › Phone storage shows how many session logs there are and their size.
- Fixed: messages (saved, added, deleted, errors) never showed since the app
  shell: the snackbar was cancelled the moment it appeared.

## 2026-11-03 (Tracks)

- Tracks tab: every track, nearest first with the phone's location, each with
  its outline, distance, whether you added it, and whether it has a start line.
  A track opens to its outline on the map.
- Find tracks: near me (the phone's location) or by US ZIP, within 25 / 50 /
  100 mi. It searches OpenStreetMap through the registry, and Add makes a found
  track a registry track (shared, recorded as added by you).
- Race mode on Drive picks a track (nearest first, remembered); its
  `session_start` carries `track_id`.

## 2026-11-03 (History facelift, route explorer, units)

- History cards each carry a thumbnail of the route's shape, coloured by speed
  and drawn from its points (no map tiles), with routes cached on the phone.
- The session screen:
  - The map colours by Speed, Throttle, Brake, Gear or RPM, whichever the
    vehicle reports, each with its legend. Gear shows P/R/N from the selector,
    not the transmission's raw codes.
  - Tapping the route shows that second: time into the drive, speed, rpm,
    throttle, gear and brake, and marks the point.
  - The summary is tiles, with "All details" for every line.
- Units (Settings): imperial (mi, mph, °F) or metric (km, km/h, °C) for every
  number; speed bands follow (20/40/55 mph or 30/60/90 km/h).
- Needs the registry's track endpoint with columns (`20261103-trackcols-v1`).

## 2026-11-03 (Drive tab)

- Drive, idle:
  - the vehicle, and a Trip / Race mode, remembered, which `session_start`
    now carries instead of always "race";
  - a big Start;
  - Last drive as six tiles (distance, moving time, average and top speed,
    stops, brake applications) with Open ›.
- Drive, recording:
  - large tiles for elapsed time, distance and speed from the phone's GPS. The
    phone doesn't decode CAN. Distance only counts accurate fixes while moving,
    so parked drift adds nothing;
  - one upload health line, green / amber / red, with seconds recorded,
    uploaded and not sent;
  - Mark start/finish in Race;
  - a big red Stop.

## 2026-11-03 (app shell)

- Dark theme (the DBC companion app's palette); bottom navigation with Drive,
  History and Tracks; Settings behind the gear (docs/plans/2026-11-03-phone-app-experience.md,
  step 1).
- The top bar shows three status dots: dongle, GPS and broker. Tapping one says
  what it means.
- Drive keeps vehicle, Start/Stop, mark start/finish and Last drive (with "Open in
  History").
- Settings:
  - Account: name and email from the registry, sign out;
  - Vehicles: add, and now edit (name, year, make, model, DBC, CAN filter) or
    delete;
  - Dongle: state, the remembered dongle, Forget;
  - Diagnostics: the frame, IMU and BLE counters that were on the main page;
  - About: version, device id, hosts.
- The CAN filter is edited on its vehicle; saving the selected vehicle applies it
  to the dongle.
- Tracks is a placeholder for now.

## 2026-11-03 (route map)

- A session's History detail shows its route on a map (MapLibre with
  OpenStreetMap tiles: free and keyless). The route is coloured by speed band
  (<20, 20–40, 40–55, 55+ mph) with start and end dots, and is broken where GPS
  dropped out for more than 5 s. The track comes from the registry
  (`GET /api/app/sessions/{id}/track`).
- The APK carries arm64 native code only (MapLibre's four ABIs made it 84 MB;
  now 41 MB).

## 2026-11-03 (history)

- Capture and History tabs. History lists the user's sessions, newest first,
  from the registry (`GET /api/app/sessions`):
  - date, time and vehicle;
  - the summary headline (miles, time, average mph), or why there is none:
    still recording, or recorded before drive summaries existed.
  Tapping a session opens its full summary; Load more pages back through older
  sessions.
- Summary peaks (braking, acceleration, cornering g) get their own line.

## 2026-11-03 (drive summary)

- A "Last drive" card under the capture buttons shows the last finished
  capture's drive summary from the registry (`GET /api/app/sessions/{id}/summary`):
  - distance (GPS, with CAN and odometer beside it);
  - time moving, average and top speed;
  - stops, brake applications and shifts;
  - time in each selector position and gear;
  - hard braking, acceleration and cornering events with their peaks;
  - top RPM and coolant temperature;
  - how much of the drive arrived.
  It shows mi, mph and °F.
- The summary is computed in the cluster a couple of minutes after the session
  settles; the card asks every 20 s for up to 10 min, and Refresh asks again.
  The last finished session is remembered across app restarts.

## 2026-11-03 (vehicle DBC)

- Add vehicle offers the registry's DBCs (default = the pipeline's default DBC);
  the pipeline decodes that vehicle's sessions with it.
- Vehicles keep their `dbc_id` through every update. The previous build sent
  vehicles without it, which would have cleared a vehicle's DBC on Apply filter.

## 2026-11-03 (vehicles)

- A Vehicle picker above Start capture lists the user's vehicles from the
  registry's phone API (`robo.danwils.com/api/app`, same authentik token as MQTT)
  and has "Add vehicle" (name, year, make, model; starts with the current CAN
  filter). The list is cached so a capture can start with no signal.
- Picking a vehicle makes its CAN filter the dongle's (at once if connected), and
  Apply filter saves the filter back to the selected vehicle: the filter belongs
  to the car, not the phone.
- `session_start` carries the vehicle's id. The app remembers which vehicle each
  dongle was last used in and preselects it when that dongle connects.
- Sign-in also asks for the `email` scope, so the registry can link the user to
  an existing registry user by email. Sign out and in once to get it.

## 2026-11-03 (per-user topic)

- Publishes to `telemetry/ingest/<sub>` (the signed-in user's authentik subject)
  instead of the shared `telemetry/ingest`. The broker only lets a user publish
  to their own topic and kreceiver stamps the session with the user from it, so
  sessions belong to the sign-in, not to anything the app writes in a message.
  Falls back to the shared topic if no subject is stored.

## 2026-11-03 (filter before notifications)

- On connect the app writes the CAN filter before enabling notifications (was
  after), so the dongle's stream is filtered from its first line instead of
  ~1 s unfiltered. Verified on the bench: discovery 13.445, notifications
  enabled 13.482 after the filter write. The dongle still counts lines from
  before the MTU exchange (~1 s of 20-byte notifications, before the phone
  subscribes) as drops in its first STAT window; those were never receivable,
  and counting from subscription instead is a firmware change, not done.

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
- The frame log is one file per session, `files/frames/session-<id>.bin`
  (was one per UTC day; old `frames-YYYYMMDD.bin` files are left as they are).
  At each capture start the oldest logs are deleted while the total is over
  5 GB, never the current session or one being recovered. Deleting sessions
  the server has confirmed complete comes with the registry sessions table.

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
