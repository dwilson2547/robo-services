# Phone Companion — Roadmap

## Current state

- ESP32-S3 firmware bridges CAN (TWAI listen-only, 500 kbps) to BLE NUS notifications
- Android app scans for BLE, connects to first matching device, renders raw frame lines
- Frame format: `STD|EXT,0xID,DLC,B0..Bn`

---

## Hardware note — enabling TX (required before Phase 6)

> **TODO on all dongles:** desolder the S-pin jumper to GND on the TJA1051.
> The S pin (pin 8) in silent mode (GND) prevents the transceiver from driving the bus.
> For listen-only sniffing this is fine; for OBD-II requests and DTC reads it must be open (or pulled high).
> Firmware also needs to change from `TWAI_MODE_LISTEN_ONLY` → `TWAI_MODE_NORMAL` at the same time.

---

## Phase 1 — Reliable connection (immediate)

**Goal:** solid, user-friendly BLE connection before building anything on top of it.

| # | Feature | Notes |
|---|---------|-------|
| 1.1 | **Device picker** | Replace "connect to first match" with a scrollable list of scan results showing name + RSSI + address. User taps to connect. |
| 1.2 | **Scan filter by name** | Pass a `ScanFilter` for `CAN-DONGLE` so only relevant devices appear — eliminates the noise from nearby devices. |
| 1.3 | **Remember last device** | Persist MAC address in SharedPreferences; show a "reconnect to last" shortcut on launch. |
| 1.4 | **Auto-reconnect** | On disconnect, retry connection to the remembered device with backoff before falling back to scan. |
| 1.5 | **Connection status indicator** | Persistent status bar: Disconnected / Scanning / Connecting / Connected + RSSI. |
| 1.6 | **Configurable baud rate** | Firmware: runtime CAN baud via BLE write characteristic (RX UUID). App: baud selector (125k / 250k / 500k / 1M). |

---

## Phase 2 — Usable data display

**Goal:** make raw frame data readable and navigable.

| # | Feature | Notes |
|---|---------|-------|
| 2.1 | **Structured frame list** | RecyclerView rows: timestamp, ID, DLC, bytes. Replace the plain text dump. |
| 2.2 | **Pause / resume** | Freeze the live display without disconnecting; review frames then resume. |
| 2.3 | **Frame rate counter** | Frames/sec displayed in the status bar — immediate health indicator for the CAN bus. |
| 2.4 | **Filter by CAN ID** | Text field to show only frames matching an ID or ID range (e.g. `0x300–0x3FF`). |
| 2.5 | **Unique ID list view** | Tab showing each distinct CAN ID seen, last value, and update rate. |
| 2.6 | **Hex / decimal / binary toggle** | Per-row byte display format. |

---

## Phase 3 — Decoding

**Goal:** turn raw bytes into named signals.

| # | Feature | Notes |
|---|---------|-------|
| 3.1 | **DBC file import** | Parse a `.dbc` file from device storage; map frame IDs to message/signal definitions. |
| 3.2 | **Signal extraction** | Decode start bit, length, factor, offset, byte order per DBC signal spec. |
| 3.3 | **Decoded signal view** | Display signal name + physical value + unit alongside raw bytes. |
| 3.4 | **Hardcoded OBD-II PID profiles** | Built-in decode for standard OBD-II PIDs (Mode 01) as fallback without a DBC. |

---

## Phase 4 — DBC coverage & verification

**Goal:** use the tool to build and validate DBC coverage on a real car.

| # | Feature | Notes |
|---|---------|-------|
| 4.1 | **Coverage report** | For a loaded DBC, show % of defined messages seen, % of signals decoded in the current session. |
| 4.2 | **Unknown ID tracking** | Highlight IDs seen on bus that have no matching DBC entry — the gap list for expanding coverage. |
| 4.3 | **Signal sanity check** | Flag signals whose decoded value falls outside the DBC min/max range. |
| 4.4 | **Session comparison** | Diff two recorded sessions by ID presence and signal value ranges — useful for before/after or car-to-car comparison. |
| 4.5 | **Export coverage report** | Save a JSON/CSV summary: message ID, name, seen Y/N, sample count, value range. |

---

## Phase 5 — Visualization & engine monitoring

**Goal:** dashboard-style display for monitoring key signals; real-time alerting.

| # | Feature | Notes |
|---|---------|-------|
| 5.1 | **Pin a signal to dashboard** | Long-press a decoded signal to pin it as a gauge or value tile. |
| 5.2 | **Gauge widgets** | Circular gauge, numeric readout, bar graph — selectable per pin. |
| 5.3 | **Rolling time-series chart** | Scrolling line chart for a pinned signal over the last N seconds. O2 sensor voltage is the primary target here. |
| 5.4 | **O2 sensor graph** | Dedicated view for wideband/narrowband O2 signals — dual-bank, time-aligned, with lambda overlay if available. |
| 5.5 | **Threshold alerts** | Set min/max on any signal; toast + optional audio alert when breached (coolant temp, oil pressure, etc.). |
| 5.6 | **Dashboard layout persistence** | Save and restore pinned signals and layout across sessions. |

---

## Phase 6 — OBD-II diagnostics (requires TX hardware mod)

**Goal:** active diagnostics — DTC reads, PID polling, live engine data on demand.

> Prerequisite: S-pin jumper removed from all dongles + firmware switched to `TWAI_MODE_NORMAL`.

| # | Feature | Notes |
|---|---------|-------|
| 6.1 | **Firmware TX mode** | Switch TWAI to `TWAI_MODE_NORMAL`; expose NUS RX characteristic for phone→dongle commands. |
| 6.2 | **OBD-II PID poller** | Phone sends Mode 01 PID requests (0x7DF); firmware forwards to bus and routes 0x7E8 responses back. |
| 6.3 | **DTC read** | Mode 03 (stored), 07 (pending), 0A (permanent) fault code requests; decode P/B/C/U codes with description lookup. |
| 6.4 | **DTC clear** | Mode 04 clear with explicit user confirmation prompt. |
| 6.5 | **Freeze frame data** | Mode 02 — read the sensor snapshot captured at the time a DTC was set. |
| 6.6 | **ISO-TP framing** | OBD-II responses > 7 bytes need ISO 15765-2 multi-frame reassembly on the phone side. |

---

## Phase 7 — Logging & export

**Goal:** capture sessions for offline analysis.

| # | Feature | Notes |
|---|---------|-------|
| 7.1 | **Session recording** | Start/stop button; write frames with timestamps to internal storage. |
| 7.2 | **CSV export** | Export recorded session as `timestamp,id,dlc,b0..bn` CSV. |
| 7.3 | **ASC / BLF export** | Standard Vector log formats for compatibility with CANalyzer / SavvyCAN. |
| 7.4 | **Playback mode** | Replay a saved log through the decoder/dashboard as if live. |

---

## Firmware backlog

| # | Feature | Notes |
|---|---------|-------|
| F.1 | **RX write characteristic** | Accept baud rate, filter config, and OBD-II requests from the phone over BLE (NUS RX UUID `6E400002`). |
| F.2 | **Multi-frame BLE batching** | Bundle multiple CAN frames per notification to reduce BLE overhead at high bus loads. |
| F.3 | **Bus-off recovery** | Detect TWAI bus-off state and attempt driver restart automatically. |
| F.4 | **OTA firmware update** | BLE OTA via NimBLE's OTA service so the dongle can be updated without a laptop. |

---

## Out of scope (for now)

- iOS support
- Cloud sync / remote telemetry
