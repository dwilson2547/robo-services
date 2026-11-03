# Phone app: from status page to app

Status: **proposed** (2026-11-03). The phone gateway app today is one white page
of status lines, plus a History tab. This turns it into an app with:

- a live view of the current drive;
- History with the route explorer;
- Tracks you can find and add while out;
- Settings for everything that is configuration today.

It follows the DBC companion app's shape (`embedded/dbc-forge/android_app`):
navigation-compose, a status top bar, a bottom navigation bar, Settings behind a
gear icon, and a dark theme.

## What exists (✅ verified 2026-11-03)

- App, a single activity:
  - **Capture:** a vehicle picker, Start/Stop, mark start/finish, the Last
    drive card, about 15 diagnostic status lines, and the dongle CAN filter
    editor.
  - **History:** the session list, and a session's route map and summary.
  - Light Material theme, no icons, no navigation library.
- Registry tracks (LAN API, shared by everyone, not per user):
  - `GET/POST /api/tracks`;
  - `POST /api/tracks/discover` (lat, lon, radius → OSM candidates via
    Overpass);
  - `POST /api/tracks/ingest` (selected candidates → tracks, with centreline
    and optional start line).
- Sessions carry `track_id`, always null so far. Track selection was step 5 of
  the recording-sessions plan, deferred behind the trip pipelines.

## Shape

```
┌ top bar: dongle ● GPS ● broker ●                     ⚙ ┐
│                                                        │
│                 Drive | History | Tracks               │
└ bottom navigation ─────────────────────────────────────┘
⚙ → Settings (pushed screen, back arrow)
```

- **Top bar:** three status dots that are always visible (dongle connected;
  GPS fix fresh; broker connected), and the gear for Settings. Tapping a dot
  says what it means and what is wrong.
- **Theme:** dark, the DBC app's palette, so the two apps feel like one family.
  Speed-band colours stay as on the map.

## Drive tab (the current drive)

- **Idle:** the vehicle picker; a mode toggle (Trip / Race; in Race, a track
  picker from the Tracks tab); one big Start button; the Last drive card,
  restyled as stat tiles, which opens the session in History.
- **Recording:** tiles for elapsed time, distance and current speed. These come
  from the phone's GPS: the phone does not decode CAN, so live CAN values are
  out of scope here. Below them:
  - frames sent, acked and queued, as one health line (green / amber / red);
  - in Race, Mark start/finish;
  - a big Stop button.
- The diagnostic counters (IMU gaps, late samples, MTU, PHY, dongle stats) move
  to Settings › Diagnostics.

## History tab

- **List:** cards with date, vehicle, distance, time and average speed, and a
  small speed-coloured thumbnail of the route.
- **Session screen:**
  - **Map with a colour-by selector:** speed, throttle, brake, gear or RPM,
    each with its legend.
  - **Tap the route:** a callout with that second's values: time into the
    drive, speed, RPM, throttle, gear, and brake on/off.
  - **Summary:** tiles (distance, time, speed, stops / brakes / shifts, hard
    events, engine, coolant), then the detail lines.
- **Registry change:** the track endpoint returns columns and rows rather than
  fixed tuples, so the map can colour by any role and tap to any value:
  `{"columns": ["s", "lat", "lon", "speed_kph", "rpm", "throttle_pct",
  "brake_on", "gear", "prndl"], "rows": [...]}`.

## Tracks tab

- **List:** known tracks, nearest first when there is a fix, with a mini map of
  each outline.
- **Find tracks:**
  - **Nearby:** the phone's position and a radius (25 / 50 / 100 mi);
  - **By ZIP:** a 5-digit US ZIP, geocoded by the registry through Nominatim
    (OSM's free geocoder; one request per second, identifying user agent; the
    registry caches ZIP results).
  - Either way, the results are OSM candidates already known or new; adding one
    ingests it.
- **Track screen:** the outline on the map and its start line, if OSM has one.
  If it doesn't, "Mark start/finish" in Race mode on the Drive tab sets it
  later; that part is the racing pipeline's work, not this plan's.
- **Registry change, phone API:**
  - `GET /api/app/tracks` (all tracks, optionally sorted by distance from a
    point);
  - `POST /api/app/tracks/discover` (`{lat, lon, radius_m}` or `{zip}`);
  - `POST /api/app/tracks/ingest`.
  These reuse the LAN endpoints' code. Tracks stay shared (a track is a place,
  not a user's), with who added it recorded.

## Settings

- **Account:** signed-in email; sign out.
- **Vehicles:**
  - list, add, and edit name, year, make, model and DBC (editing is new: the app
    can only add today);
  - the CAN filter editor moves here, under its vehicle.
- **Dongle:** the remembered dongle, Forget, reconnect.
- **Units:** imperial (default) or metric, for every screen.
- **Phone storage:** frame logs on the phone, their total size, and the cleanup
  rule below.
- **Diagnostics:** the counters that leave the Drive tab.
- **About:** app version, device id, registry and broker hosts.

## Frame log cleanup

The decision from 2026-11-03 still stands:

- one file per session;
- a file is kept until the registry confirms its session complete, then for 30
  more days;
- a 5 GB backstop.

- The app asks `GET /api/app/sessions` for the state of every session it has a
  file for. It deletes files whose session is `complete` and ended over 30 days
  ago.
- `incomplete`, `unterminated` and unknown sessions keep their files (they may
  be needed for a re-send) until the 5 GB backstop.
- It runs when the app opens and after each capture, and logs what it deleted.

## Build order

1. **Shell:** theme, navigation, top bar and Settings. Move what exists into
   Drive and Settings, with no new behaviour. Vehicle editing in Settings.
2. **Drive tab:** idle and recording layouts and live tiles; the Last drive
   tiles.
3. **History:** cards, the session screen with tiles, the track endpoint's
   columns, colour-by, and tap for values.
4. **Tracks:** registry phone endpoints with ZIP geocoding; the Tracks tab;
   Race mode with a picked track (`session_start.track_id`).
5. **Frame log cleanup** and the Phone storage settings.

Each step ships on its own and is checked on the phone before the next.

## Decisions (user, 2026-11-03)

- Dark theme, the DBC companion app's palette.
- Imperial units by default (metric selectable in Settings).
- Route thumbnails are rendered on the phone and cached; the registry stays
  free of a renderer.
