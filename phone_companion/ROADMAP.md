# Phone Companion — Roadmap

## Current state (as of 2026-06-09)

- ESP32-S3 (Seeed Studio XIAO ESP32-S3) firmware v1.2.0: CAN listen-only (TWAI, 500 kbps) → BLE NUS notifications
- Frame packing: multiple frames per BLE notification (10 ms flush window, 400-byte early-flush threshold)
- Per-frame capture timestamps embedded in BLE packets (v1.2.0); Android reconstructs per-frame timing from intra-packet firmware offsets
- BLE OTA firmware update confirmed working end-to-end; dongle no longer requires USB for updates
- BLE connection persistence: last device saved, auto-reconnect on unintentional drop (3 s → 15 s, 3 attempts)
- Android app: BLE scan/connect/auto-reconnect, live frame display (Known / Unknown tabs), signal decoder, DBC parser/writer (including mux indicators), signal editor with live BitGrid preview, verification marking (VERIFIED / SUSPECT / UNVERIFIED), session recording with notes, log browser with frame diff view and decoded signals, vehicle profiles, Git sync (JGit, PAT auth)
- DBC editing: signal/message deletion, editable DLC, comment field, VAL_ editor, search/filter, coverage badges, frame inspector for known messages (with signal bit overlays)
- Operational telemetry: `CanStats` StateFlow tracking frames processed, decode-out-of-range events, parse errors, BLE notification count

---

## Hardware note — enabling TX (required before Phase 7.2+)

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
| 1.3 | **Remember last device** | ✓ Done — saved to SharedPreferences; top-bar button glows green and reconnects directly |
| 1.4 | **Auto-reconnect** | ✓ Done — reconnects after 3 s then 15 s on unintentional drop (3 attempts); user disconnect suppresses |
| 1.5 | **Connection status indicator** | ✓ Done — top bar shows device name, RSSI, fps |
| 1.6 | **Configurable baud rate** | Backlog — requires firmware F.3 |

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
| 3.2 | **Signal extraction** | ✓ Done — Intel + Motorola, signed/unsigned, factor/offset, mux-aware |
| 3.3 | **Decoded signal view** | ✓ Done — live values in Signals tab and log browser |
| 3.4 | **Hardcoded OBD-II PID profiles** | See Phase 7 |

---

## Phase 4 — DBC authoring & gap fixes ✓ (mostly complete)

**Goal:** complete the DBC editing workflow and close the identified quality gaps.

| # | Feature | Status |
|---|---------|--------|
| 4.1 | **Signal deletion** | ✓ Done — delete icon on SignalRow with confirmation dialog |
| 4.2 | **Message deletion** | ✓ Done — three-dot overflow menu on MessageRow header |
| 4.3 | **Editable DLC on new message** | ✓ Done — DLC field pre-populated from observed frame size |
| 4.4 | **Search / filter — Unknowns screen** | ✓ Done — TextField filter on ID hex string with clear button |
| 4.5 | **Search / filter — Signals screen** | ✓ Done — filter on message name and CAN ID |
| 4.6 | **Coverage badges** | ✓ Done — "N / M messages seen" summary row; green/dim dot per message |
| 4.7 | **Frame inspector for known messages** | ✓ Done — FrameInspectorScreen accepts known IDs; signal bit overlays with color legend |
| 4.8 | **Signal comment field** | ✓ Done — multiline notes field in SignalEditorScreen; written to DBC `CM_` block |
| 4.9 | **Value description (VAL_) editor** | ✓ Done — collapsible section in SignalEditorScreen; add/delete entries |
| 4.10 | **Trigger window survives freeze** | ✓ Done — processing always runs; only UI StateFlow emissions are frozen |
| 4.11 | **Session notes at record time** | ✓ Done — notes field in vehicle picker dialog; shown in session list and log browser header |
| 4.12 | **Signal decoder bounds guard** | ✓ Done — `decodeOrNull()` returns null past DLC; warning in editor live preview |
| 4.13 | **DBC signal sanity check** | Backlog — flag signals whose decoded value falls outside DBC min/max range |
| 4.14 | **Coverage report export** | Backlog — JSON/CSV: message ID, name, seen Y/N, sample count, value range |
| 4.15 | **Session comparison** | Backlog — diff two sessions by ID presence and signal value ranges |

---

## Phase 5 — Signal health & automated verification ✓ (mostly complete)

**Goal:** reduce manual verification burden by automatically detecting misconfigured or implausible signals.

| # | Feature | Notes |
|---|---------|-------|
| 5.1 | **Stuck signal detection** | ✓ Done — 50-frame rolling window; amber flag in SignalRow and health panel. |
| 5.2 | **Pegged signal detection** | ✓ Done — all values at DBC min or max; amber chip in health panel. |
| 5.3 | **Implausible rate-of-change** | Backlog — requires user to set `maxDeltaPerFrame` or use a global heuristic. |
| 5.4 | **Signal health panel** | ✓ Done — `signal_health` route; STUCK/PEGGED chips; empty state explains observation window. |
| 5.5 | **Canonical signal name suggestions** | ✓ Done — `CanonicalSignals.ALL` (47 entries with unit/min/max/factor/offset/description); autocomplete dropdown in SignalEditorScreen fires after 2 characters; selecting fills in all scaling fields. |
| 5.6 | **Create new DBC** | ✓ Done — FAB in DbcListScreen opens "New DBC" dialog (name validation, no-duplicate check); creates empty DBC locally via `SettingsViewModel.createDbc()`. |
| 5.7 | **Copy signal from another DBC** | ✓ Done — "Copy from…" button in SignalEditorScreen opens two-level picker (DBC → signal); selecting copies all signal fields including bit layout into the form. |

---

## Phase 6 — Timeline & log analysis ✓ (complete)

**Goal:** make recorded sessions analytically useful — scrub, zoom, diff, and correlate signals over time.

> Per-frame timestamps are now accurate (firmware v1.2.0). Phase 6 is unblocked.

| # | Feature | Notes |
|---|---------|-------|
| 6.1 | **Timeline range scrubber** | ✓ Done — Material3 `RangeSlider` (two-thumb) at bottom of `LogBrowserScreen`. Frame list re-filters reactively to selected window. Label row shows session total and selected sub-range. |
| 6.2 | **Signal sparklines** | ✓ Done — `SparklineSeries` (200-slot normalized time series) computed per-signal after load. Scrollable sparkline card row above the scrubber; tapping a card toggles signal selection for correlation (up to 4, color-coded). |
| 6.3 | **Reference event anchor** | ✓ Done — Long-press any frame → "Set as T=0 reference". All timestamps switch to `T+N.Ns`. Amber star chip clears reference. |
| 6.4 | **Stimulus-response diff view** | ✓ Done — Long-press any frame → "Mark as A" or "Mark as B". "Diff (N changed)" button opens a dialog listing all decoded signals sorted by magnitude of change (↑/↓ delta, appeared/disappeared). 2 s snapshot windows around each bookmark. |
| 6.5 | **Multi-signal correlation plot** | ✓ Done — "Plot (N)" button opens signal picker (checkbox list with colored dots). Confirming shows a full-screen Canvas plot with independent Y-scale per signal, horizontal grid, legend, and time axis footer. |
| 6.6 | **Playback mode** | ✓ Done — Play/pause `IconButton` left of scrubber; coroutine advances `_rangeEnd` at real time × speed. Speed selector: 0.25×/0.5×/1×/2×/4×. Scrubber drag stops playback; auto-stops at end; restarts from beginning if already at end. |

---

## Phase 7 — Passive OBD-II support

**Goal:** passively decode OBD-II traffic observed on the bus (scan tool + sniffer workflow). No active TX required beyond 7.1.

> Active diagnostics (PID polling, DTC read/clear, ISO-TP) are out of scope for this app — they belong in a dedicated diagnostic tool.

| # | Feature | Notes |
|---|---------|-------|
| 7.1 | **Diagnostic frame detection** | ✓ Done — `CanBusViewModel.isObd2Diagnostic(id)` detects `0x7DF` / `0x7E0–0x7EF`. "Diag" filter chip in live view; frames rendered in light purple with `REQ`/`RSPn` prefix + SVC/PID fields. |
| 7.2 | **OBD-II PID decoder** | ✓ Done — `Obd2PidTable` (63 PIDs, J1979 Mode 01). `formatObd2Frame()` decodes SVC `0x41` responses into `Engine RPM: 1726.25 rpm`; unknown PIDs fall back to raw hex. |
| 7.3 | **Native ↔ OBD-II cross-reference** | ✓ Done — Pearson r correlation engine in `processBatch()`. Accumulates up to 30 `(obd, native)` sample pairs per `(pid, signal)`. |r| ≥ 0.85 → `OdbCrossRef` promoted to `obdCrossRefs` StateFlow. Green "OBD ✓ pidName r=0.97" badge in `SignalRow`. |

---

## Phase 8 — Live signal graph

**Goal:** rolling time-series chart for any decoded signal, directly in the live view. Replaces the earlier O2-specific monitor concept.

| # | Feature | Notes |
|---|---------|-------|
| 8.1 | **Pin signal to graph** | ✓ Done — `ShowChart` icon button in `SignalRow`; taps `vm.pinSignal`/`vm.unpinSignal`. Up to 4 signals. "Graph (N)" banner navigates to graph screen. |
| 8.2 | **Rolling line chart** | ✓ Done — Canvas-based `SignalChartCard` per signal; window selector 5/15/30/60 s; multi-signal `OverlayChartCard` with overlay toggle (up to 4, same `PlotColors` palette as Phase 6). |
| 8.3 | **Min/max/current readout** | ✓ Done — Numeric strip below chart: current value from live StateFlow, window min/max, unit from DBC. |
| 8.4 | **Threshold lines** | ✓ Done — Dashed `ThresholdColor` line on chart; `ThresholdDialog` via Tune icon; `setThreshold`/`clearThreshold` in ViewModel. |
| 8.5 | **Threshold alert** | ✓ Done — `MutableSharedFlow` emits `(key, value)` on side change; `LaunchedEffect` in `SignalGraphScreen` shows Snackbar `"⚠ <sig> crossed threshold: <val>"`. |

---

## Phase 9 — Export & integration

**Goal:** get data out in useful formats for offline analysis and pipeline integration.

| # | Feature | Notes |
|---|---------|-------|
| 9.1 | **CSV export** | Export recorded session as `timestamp,id,dlc,b0..bn` CSV. |
| 9.2 | **ASC / BLF export** | Standard Vector log formats for compatibility with CANalyzer / SavvyCAN. |
| 9.3 | **Coverage report export** | JSON/CSV summary: message ID, name, seen Y/N, sample count, value range. |

---

## Phase 10 — Documentation & help

**Goal:** make the app self-explanatory for new users and link to reference material.

| # | Feature | Notes |
|---|---------|-------|
| 11.1 | **Help icon in Settings** | ✓ Done — tile at bottom of SettingsScreen opens GitHub in system browser. |
| 11.2 | **User guide (GitHub wiki)** | Walkthrough covering: connect, capture, define signals, use the frame inspector, trigger workflow, session recording. Include screenshots. |
| 11.3 | **In-app tooltips** | Contextual help on the BitGrid, byte order selector, and mux indicator field — short explanatory text shown once per session. |

---

## Firmware backlog

| # | Feature | Notes |
|---|---------|-------|
| F.1 | ~~**RX write characteristic**~~ | ✓ Done — UUID `6E400002` WRITE char in NUS service. `TX_ENABLE` / `TX_DISABLE` implemented; extensible for future baud/filter commands. |
| F.2 | ~~**GPIO S-pin control**~~ | ✓ Done — S pin wired to D2 (GPIO3). Held LOW in `setup()`. `TX_ENABLE` drives HIGH + reinits TWAI as `TWAI_MODE_NORMAL`; auto-reverts to LOW on BLE disconnect. |
| F.3 | **Configurable baud rate at runtime** | Accept baud rate selection from app via F.1 channel. Restart TWAI driver without reflash. |
| F.4 | **Bus-off recovery** | Detect TWAI bus-off state and attempt driver restart automatically. |
| F.5 | ~~**OTA firmware update**~~ | ✓ Done — BLE OTA confirmed working in v1.1.0. |
| F.6 | ~~**Per-frame capture timestamps**~~ | ✓ Done — v1.2.0 appends `millis() % 65536` to each frame line; Android reconstructs per-frame timestamps from intra-packet offsets. |
| F.7 | **CAN TX safety model** | Android NUS RX plumbing done: `enableTx()`/`disableTx()` write `TX_ENABLE`/`TX_DISABLE` to `6E400002`; `txEnabled: StateFlow<Boolean>` drives green "TX" badge in top bar; auto-clears on disconnect. Remaining: TX allowlist (only permit IDs app has explicitly approved), per-ID rate cap. |

---

## Out of scope (for now)

- iOS support
- Cloud sync / remote telemetry
- Standalone WiFi mode (hotspot replaced by BLE dongle architecture)
