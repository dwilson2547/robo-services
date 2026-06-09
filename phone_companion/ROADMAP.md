# Phone Companion — Roadmap

## Current state

- ESP32-S3 (Seeed Studio XIAO ESP32-S3) firmware bridges CAN (TWAI listen-only, 500 kbps) to BLE NUS notifications
- Frame packing: multiple frames per BLE notification (10 ms flush window, 400-byte early-flush threshold)
- BLE OTA firmware update confirmed working end-to-end (v1.1.0); dongle no longer requires USB for updates
- Android app: BLE scan/connect, live frame display (Known / Unknown tabs), signal decoder, DBC parser/writer, signal editor with live BitGrid preview, verification marking (VERIFIED / SUSPECT / UNVERIFIED), session recording, log browser with frame diff view, vehicle profiles, Git sync (JGit, PAT auth)
- Gap fixes in progress — see Phase 1–4 items below

---

## Hardware note — enabling TX (required before Phase 6)

> **TODO on all dongles:** the S pin on the TJA1051 module is held LOW by a pulldown resistor on the breakout board, which forces silent (listen-only) mode. To enable TX:
> 1. Desolder the S-pin pulldown resistor (confirmed present on the purple TJA1051 breakout).
> 2. Either tie S to 3.3V (VCC) for permanently normal mode, **or** wire S to a spare XIAO GPIO for firmware-controlled mode switching (preferred — stays listen-only by default, asserts TX only when needed).
> 3. Change firmware from `TWAI_MODE_LISTEN_ONLY` → `TWAI_MODE_NORMAL` at the same time.
>
> **No termination resistor changes needed** for vehicle tap use. The OBD-II port is mid-bus; do not add a 120Ω resistor there. On a standalone bench setup with no other nodes, add one 120Ω at each physical end of the bench bus segment.

---

## Phase 1 — Reliable connection ✓ (complete)

**Goal:** solid, user-friendly BLE connection before building anything on top of it.

| # | Feature | Status |
|---|---------|--------|
| 1.1 | **Device picker** | ✓ Done |
| 1.2 | **Scan filter by name** | ✓ Done |
| 1.3 | **Remember last device** | ✓ Done |
| 1.4 | **Auto-reconnect** | ✓ Done |
| 1.5 | **Connection status indicator** | ✓ Done — top bar shows device name, RSSI, fps |
| 1.6 | **Configurable baud rate** | Backlog |

---

## Phase 2 — Usable data display ✓ (complete)

**Goal:** make raw frame data readable and navigable.

| # | Feature | Status |
|---|---------|--------|
| 2.1 | **Structured frame list** | ✓ Done |
| 2.2 | **Pause / resume** | ✓ Done — freeze button in top bar |
| 2.3 | **Frame rate counter** | ✓ Done — fps in connection header |
| 2.4 | **Filter by CAN ID** | ✓ Done — Known/Unknown filter chips |
| 2.5 | **Unique ID list view** | ✓ Done — Unknowns tab |
| 2.6 | **Hex / decimal / binary toggle** | Backlog |

---

## Phase 3 — Decoding ✓ (complete)

**Goal:** turn raw bytes into named signals.

| # | Feature | Status |
|---|---------|--------|
| 3.1 | **DBC file import** | ✓ Done — Git-backed DBC repository |
| 3.2 | **Signal extraction** | ✓ Done — Intel + Motorola, signed/unsigned, factor/offset |
| 3.3 | **Decoded signal view** | ✓ Done — live values in Signals tab and log browser |
| 3.4 | **Hardcoded OBD-II PID profiles** | See Phase 6 |

---

## Phase 4 — DBC authoring & gap fixes (active)

**Goal:** complete the DBC editing workflow and close the identified quality gaps.

| # | Feature | Notes |
|---|---------|-------|
| 4.1 | **Signal deletion** | Delete a signal from a message via trailing icon in expanded MessageRow. Confirm before delete. |
| 4.2 | **Message deletion** | Three-dot menu on message header row; AlertDialog confirmation. |
| 4.3 | **Editable DLC on new message** | DLC field shown when creating a new message; default pre-populated from observed frame size (`lastFrameData(canId)?.size`). Used for BitGrid bounds and saved to DBC. |
| 4.4 | **Search / filter — Unknowns screen** | TextField filter on ID hex string. Clear button. Persists across freeze/unfreeze. |
| 4.5 | **Search / filter — Signals screen** | TextField filter on message name and CAN ID. |
| 4.6 | **Coverage badges** | "N / M messages seen" summary row at top of Signals tab. Message header color-coded: green tint if seen this session, dim if not. Coverage summary card at top of log browser. |
| 4.7 | **Frame inspector for known messages** | Generalize FrameInspectorScreen to accept known message IDs (not just unknowns). Add `recentFrames` ring buffer to `MessageState`. Overlay defined signal bit ranges on the byte grid using distinct tint colors per signal. |
| 4.8 | **Signal comment field** | Multiline notes/comment field in SignalEditorScreen. Written to DBC `CM_` block. Also a single-line comment field for new messages. |
| 4.9 | **Value description (VAL_) editor** | Collapsible section in SignalEditorScreen. Add/edit/delete raw-value → label entries. Essential for gear position, PRNDL, mode signals. |
| 4.10 | **Trigger window survives freeze** | Continue processing batches (including `triggeredInWindow` bookkeeping and recording) when frozen; only suppress StateFlow UI emissions. |
| 4.11 | **Session notes at record time** | Notes field in vehicle picker dialog. Displayed in vehicle detail session list and log browser header. |
| 4.12 | **Signal decoder bounds guard** | `SignalDecoder.decodeOrNull()` returns null when signal range exceeds frame DLC. Surfaces warning in SignalEditorScreen live preview. Null signals excluded from decoded map. |
| 4.13 | **DBC signal sanity check** | For a loaded DBC, flag signals whose decoded value falls outside the DBC min/max range. |
| 4.14 | **Coverage report export** | Save a JSON/CSV summary: message ID, name, seen Y/N, sample count, observed value range. |
| 4.15 | **Session comparison** | Diff two recorded sessions by ID presence and signal value ranges — useful for before/after or car-to-car comparison. |

---

## Phase 5 — Signal health & automated verification (new)

**Goal:** reduce manual verification burden by automatically detecting misconfigured or implausible signals.

| # | Feature | Notes |
|---|---------|-------|
| 5.1 | **Stuck signal detection** | Flag signals whose decoded value has not changed across the last N frames (default: 50 frames / ~5 seconds of data). Surfaces as an amber warning icon in SignalRow, separate from the user-assigned VERIFIED/SUSPECT status. |
| 5.2 | **Pegged signal detection** | Flag signals always at DBC min or always at DBC max. Indicates wrong scaling or a startBit/length error. |
| 5.3 | **Implausible rate-of-change** | Flag signals changing faster than a configurable per-signal threshold. Optional; requires user to set a `maxDeltaPerFrame` on the signal or use a global heuristic. |
| 5.4 | **Signal health panel** | Dedicated view listing all signals with active health flags, sortable by flag type. Accessible from Signals tab overflow menu. |
| 5.5 | **Canonical signal name suggestions** | When typing a signal name in SignalEditorScreen, suggest names from the opendbc canonical list (e.g. `STEERING_ANGLE`, `ENGINE_RPM`, `THROTTLE_POS`, `VEHICLE_SPEED`) with pre-filled typical units and min/max ranges. Reduces naming inconsistency across vehicles. Ship the canonical list as a bundled asset. |
| 5.6 | **Seed DBC from opendbc** | Settings option to import a community DBC from the opendbc repository as a starting point for a vehicle. Saves mapping common signals from scratch on platforms with existing coverage (GM W-body, Ford Mustang). |
| 5.7 | **Copy signal from another DBC** | In SignalEditorScreen, a "copy from…" picker that imports a signal definition (name, bit layout, scaling, unit) from any other DBC in the repository. Useful when the same signal appears on multiple vehicles. |

---

## Phase 6 — Timeline & log analysis (new)

**Goal:** make recorded sessions analytically useful — scrub, zoom, diff, and correlate signals over time.

| # | Feature | Notes |
|---|---------|-------|
| 6.1 | **Timeline range scrubber** | Two-thumb range bar at the bottom of LogBrowserScreen spanning the full session duration. Frame list filtered to the selected window. Known/Unknown filter chips apply inside the window. |
| 6.2 | **Signal sparklines** | Mini per-signal value strip above the scrubber showing signal magnitude over the full session. Tap a sparkline to snap the window to the region of interest. Primary targets: RPM, TPS, STFT, O2 voltage. |
| 6.3 | **Reference event anchor** | User marks a reference moment (e.g. "engine start", "WOT entry", "shift to 3rd"). Timeline re-labels relative to that point (`T+0`, `T+2.3s`). Enables meaningful cross-session comparison of the same maneuver. |
| 6.4 | **Stimulus-response diff view** | User bookmarks two moments (before and after a stimulus). App shows a diff: which signals changed value, direction, and magnitude between the two snapshots. The primary tool for identifying unknown signals by controlled input. |
| 6.5 | **Multi-signal correlation plot** | Select 2–4 signals and plot them on the same time axis within the scrubber window. Essential for confirming signal relationships (e.g. RPM + throttle + MAP together). Renders as overlaid line charts with independent Y axes. |
| 6.6 | **Playback mode** | Replay a saved log through the decoder/dashboard as if live, respecting original frame timing. Integrates with the timeline scrubber for selective replay of a windowed segment. |

---

## Phase 7 — Diagnostic session mode (new)

**Goal:** first-class support for the OBD-II splitter workflow (scan tool + sniffer on the same bus simultaneously) and active OBD-II diagnostics.

> Prerequisite for 7.2–7.7: S-pin hardware mod + `TWAI_MODE_NORMAL` firmware.

| # | Feature | Notes |
|---|---------|-------|
| 7.1 | **Diagnostic frame detection** | Automatically identify OBD-II request/response frames on `0x7DF` / `0x7E0–0x7EF`. Filter them from the Unknowns view (or separate them into a Diagnostics tab) to reduce noise during passive sniffing with a scan tool present. Filter chip: "Hide diagnostic IDs". |
| 7.2 | **OBD-II PID decoder** | Parse Mode 01 single-frame responses using the full SAE J1979 PID table (ship as a bundled asset). Display decoded value alongside raw bytes in the Diagnostics tab. Covers all ~60 common PIDs including O2 sensor family (`0x14–0x1B`, `0x24–0x2B`). |
| 7.3 | **Native ↔ OBD-II cross-reference** | When a scan tool is active and polling a known PID, automatically correlate the OBD-II decoded value against native bus signals that vary with the same timing. Strong value match = automated signal confirmation. Surfaces as a "Confirmed by OBD cross-reference" badge in SignalRow. |
| 7.4 | **Guided correlation mode** | User declares intent: "I am about to read [signal type] from the scan tool; current scan tool value is approximately [N]." App narrows candidate native signals to those currently near value N with matching update rate and direction-of-change. One-tap to promote a candidate to a defined signal. |
| 7.5 | **Firmware TX mode** | Switch TWAI to `TWAI_MODE_NORMAL`; expose NUS RX characteristic for phone→dongle commands. GPIO-controlled S pin preferred for runtime mode switching. |
| 7.6 | **OBD-II PID poller** | Phone sends Mode 01 PID requests (`0x7DF`); firmware forwards to bus and routes `0x7E8` responses back. Polling budget managed by the app — dedicate full budget to O2 PIDs in O2 monitor mode (~4–5 Hz per sensor achievable). |
| 7.7 | **ISO-TP framing** | ISO 15765-2 multi-frame reassembly on the phone side for OBD-II responses > 7 bytes. Required for Mode 09 (VIN), Mode 03 (DTC list > 3 codes), and extended PID ranges. |
| 7.8 | **DTC read** | Mode 03 (stored), 07 (pending), 0A (permanent) fault code requests; decode P/B/C/U codes with description lookup. |
| 7.9 | **DTC clear** | Mode 04 clear with explicit user confirmation prompt. |
| 7.10 | **Freeze frame data** | Mode 02 — read the sensor snapshot captured at the time a DTC was set. |
| 7.11 | **Mode 06 monitor results** | On-board monitoring test results — O2 monitor min/max, threshold comparisons, readiness flags. Useful for sensor health check without sustained polling. |

---

## Phase 8 — O2 sensor monitoring (new)

**Goal:** dedicated O2 sensor graphing and fuel system analysis.

> **Architecture note:** O2 sensor data is not natively broadcast on the HS-CAN bus on the W-body Impala or Mustang GT. It must be actively polled via OBD-II (Phase 7 prerequisite). Native bus signals STFT and LTFT (short/long-term fuel trim) *are* typically broadcast and can be mapped passively — these are the first targets. Raw O2 voltage and λ require polling PIDs `0x14–0x1B` (narrowband voltage + STFT) and `0x24–0x2B` (equivalence ratio + voltage, wideband-compatible).

| # | Feature | Notes |
|---|---------|-------|
| 8.1 | **O2 monitor screen** | Dedicated screen with dual-trace layout: upstream sensor(s) on top plot, downstream on bottom. Time-aligned. Horizontal threshold line at 0.450V (stoichiometric crossover for narrowband). |
| 8.2 | **Dual-bank support** | Bank 1 and Bank 2 upstream sensors plotted simultaneously with distinct colors. W-body V6 has two upstream sensors; Mustang GT has two banks. |
| 8.3 | **Lambda overlay** | If equivalence ratio data is available (PID `0x24`), overlay λ on the voltage trace. Toggle on/off. |
| 8.4 | **Fuel trim display** | STFT and LTFT for both banks displayed as numeric readouts and rolling charts alongside the O2 traces. STFT sourced from native bus if mapped; LTFT typically requires OBD-II poll. |
| 8.5 | **Switching frequency analysis** | Compute upstream sensor switching frequency (crossings of 0.450V per second) and display as a rolling metric. Healthy narrowband upstream: ~0.5–2 Hz at cruise. |
| 8.6 | **Catalyst efficiency indicator** | Compare upstream vs downstream switching rate and amplitude. A healthy catalyst produces a nearly flat downstream trace. Flag degraded catalyst if downstream switching rate approaches upstream rate. |
| 8.7 | **O2 monitor mode** | When entering the O2 screen, dedicate the full OBD-II polling budget to O2 PIDs. Suspend all other active PID polling. Achieves ~4–5 Hz per sensor on a 4-sensor layout. |
| 8.8 | **Session O2 export** | Export O2 session data as CSV: timestamp, sensor ID, voltage, lambda (if available), STFT, LTFT. |

---

## Phase 9 — Visualization & dashboard (expanded from original Phase 5)

**Goal:** dashboard-style real-time display for monitoring key signals; configurable layout; alerting.

| # | Feature | Notes |
|---|---------|-------|
| 9.1 | **Pin a signal to dashboard** | Long-press a decoded signal to pin it as a gauge or value tile. |
| 9.2 | **Gauge widgets** | Circular gauge, numeric readout, bar graph — selectable per pin. |
| 9.3 | **Rolling time-series chart** | Scrolling line chart for a pinned signal over the last N seconds. |
| 9.4 | **Threshold alerts** | Set min/max on any signal; toast + optional audio alert when breached (coolant temp, oil pressure, etc.). |
| 9.5 | **Dashboard layout persistence** | Save and restore pinned signals and layout across sessions. |

---

## Phase 10 — Export & integration (expanded from original Phase 7)

**Goal:** get data out in useful formats for offline analysis and pipeline integration.

| # | Feature | Notes |
|---|---------|-------|
| 10.1 | **CSV export** | Export recorded session as `timestamp,id,dlc,b0..bn` CSV. |
| 10.2 | **ASC / BLF export** | Standard Vector log formats for compatibility with CANalyzer / SavvyCAN. |
| 10.3 | **Coverage report export** | JSON/CSV summary: message ID, name, seen Y/N, sample count, observed value range. |
| 10.4 | **O2 session export** | See Phase 8.8. |

---

## Firmware backlog

| # | Feature | Notes |
|---|---------|-------|
| F.1 | **RX write characteristic** | Accept baud rate, filter config, and OBD-II requests from the phone over BLE (NUS RX UUID `6E400002`). |
| F.2 | **GPIO S-pin control** | Wire TJA1051 S pin to a XIAO GPIO. Firmware defaults to listen-only; switches to normal mode only on explicit app command. Cleaner than a permanent hardware tie to VCC. |
| F.3 | **Configurable baud rate at runtime** | Accept baud rate selection from app via RX characteristic. Restart TWAI driver with new rate without reflashing. |
| F.4 | **Bus-off recovery** | Detect TWAI bus-off state and attempt driver restart automatically. |
| F.5 | ~~**OTA firmware update**~~ | ✓ Done — BLE OTA confirmed working in v1.1.0. |

---

## Out of scope (for now)

- iOS support
- Cloud sync / remote telemetry
- Standalone WiFi mode (hotspot replaced by BLE dongle architecture)
