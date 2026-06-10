# Changelog

## 2026-06-09 (session 15 — starter DBC assets)

### Added
- **Starter DBC bundle** — Added `app/src/main/assets/starter_dbcs/` containing 8 real-world DBC files sourced from the comma.ai opendbc repository, plus a `LICENSE` file (MIT, Comma.ai). Files included: `acura_ilx_2016_nidec.dbc` (5.1 KB), `gm_global_a_lowspeed.dbc` (3.3 KB), `ford_fusion_2018_pt.dbc` (4.8 KB), `bmw_e9x_e8x.dbc` (24.6 KB), `mazda_2017.dbc` (28.7 KB), `tesla_model3_vehicle.dbc` (23.8 KB), `hyundai_i30_2014.dbc` (22.8 KB), `toyota_2017_ref_pt.dbc` (66.6 KB). These give users real DBC definitions to explore on first launch without needing to import their own file. Note: no Honda or Subaru DBC files exist in the opendbc repo at this time; those slots were filled with the closest available alternatives (Hyundai i30 2014 and Toyota 2017 ref PT).

## 2026-06-09 (session 14 — F.3 baud rate, F.4 bus-off recovery, OTA MTU fix)

### Fixed
- **OTA MTU negotiation** — `onConnectionStateChange` now calls `gatt.requestMtu(517)` before service discovery instead of proceeding at the BLE default (23-byte) ATT MTU. `onMtuChanged` stores the negotiated MTU and then calls `discoverServices()`. OTA chunk size is now computed as `(negotiatedMtu - 3).coerceIn(20, 512)` instead of a hardcoded 512, preventing GATT error 133 on phones that don't auto-negotiate a high MTU. This was the primary risk for bricking a device on a day-1 OTA update.

### Added
- **F.3 — Runtime baud rate (firmware + Android)** — Firmware `v1.4.0` parses `BAUD:<rate>` commands on the NUS RX characteristic. Supported rates: 125000, 250000, 500000, 1000000. `reinitCan()` selects the appropriate `TWAI_TIMING_CONFIG_*` macro from `gReinitBaudRate`; reinit preserves current TX-enabled state. Android: `setBaudRate(baud)` in `CanBusViewModel` sends the NUS command, updates `_activeBaudRate` StateFlow, and persists to SharedPreferences (`can_baud_rate`). Baud rate selector added to `SettingsScreen` as a `FilterChip` row (125k / 250k / 500k / 1M); shows live selection, applies immediately when connected.
- **F.4 — Bus-off recovery (firmware)** — `loop()` calls `twai_get_status_info()` every 500 ms. On `TWAI_STATE_BUS_OFF`, triggers `reinitCan(gTxEnabled)` with a 5-second cooldown between recovery attempts. Prevents the dongle from going permanently silent after a bus error burst.

### Changed
- Firmware bumped to **v1.4.0**; bundled binary and `assets/firmware/version.txt` updated to match.

## 2026-06-09 (session 13 — Phase 8 Generic Signal Graph)

### Added
- **`CanBusViewModel` Phase 8 backend** — `_pinnedSignalKeys: MutableStateFlow<List<String>>`, `_signalSeries: MutableStateFlow<Map<String, List<Pair<Long,Double>>>>`, `_thresholds: MutableStateFlow<Map<String,Double>>`, `_thresholdAlerts: MutableSharedFlow<Pair<String,Double>>` (capacity 8). Public methods: `pinSignal(key)`, `unpinSignal(key)`, `setThreshold(key, value)`, `clearThreshold(key)`. Constants: `MAX_PINNED_SIGNALS = 4`, `GRAPH_MAX_WINDOW_MS = 60_000L`, `GRAPH_BUFFER_SLOTS = 6_000`.
- **Rolling series accumulation in `processBatch()`** — For each decoded signal value, if the key is in `pinnedNow`, appends `(timestampMs, value)` to an `ArrayDeque` in `signalSeriesData`. Keeps the last 6000 points and drops points older than 60 s. Emits a snapshot to `_signalSeries` every batch (only when not frozen).
- **Threshold crossing detection in `processBatch()`** — Compares current side (above/below) to previous; on a crossing, enqueues `(key, value)` in a per-batch `crossings` list, then `tryEmit`s each to `_thresholdAlerts` from `Dispatchers.Main`.
- **`SignalGraphScreen.kt`** (new) — Full-screen rolling chart view. Window selector chips (5/15/30/60 s). Individual `SignalChartCard` per pinned signal: Canvas line chart with 25/50/75 % grid lines, dashed threshold line, threshold dialog (set/clear), min/max/current readout strip, Y-axis labels. Multi-signal `OverlayChartCard` with normalized traces and color legend when "Overlay" is toggled. Empty-state message when nothing is pinned. `SnackbarHost` at bottom; `LaunchedEffect` collects `thresholdAlerts` and shows `"⚠ <sigName> crossed threshold: <value>"` snackbar.
- **Pin/unpin button in `SignalRow`** (`SignalsScreen.kt`) — `Icons.AutoMirrored.Filled.ShowChart` icon button; tinted primary when pinned, dim when not. Tapping calls `vm.pinSignal` / `vm.unpinSignal` via `onTogglePin` callback threaded through `MessageRow`.
- **"Graph (N signals pinned)" navigation banner** (`SignalsScreen.kt`) — Appears between the health warning banner and the search bar whenever `pinnedSignalKeys` is non-empty. Tapping calls `onGraphScreen()`.
- **`signal_graph` route in `AppNavigation.kt`** — `composable("signal_graph")` renders `SignalGraphScreen`; `SignalsScreen` receives `onGraphScreen = { navController.navigate("signal_graph") }`.

### Why
- Rolling chart enables watching a signal evolve in real time — useful for confirming a newly decoded signal behaves as expected (e.g., correlates with pedal movement) and for threshold-based spotting of anomalies.
- Overlay mode lets you visually correlate up to 4 signals without needing to export to a desktop tool.

## 2026-06-09 (session 12 — Phase 7.3 Native ↔ OBD-II cross-reference)

### Added
- **`OdbCrossRef` data class** — Public state type: `signalKey` (`"msgName/sigName"`), `pid`, `pidName`, `correlation` (Pearson r), `sampleCount`.
- **`obdCrossRefs: StateFlow<Map<String, OdbCrossRef>>`** in `CanBusViewModel` — emits confirmed matches (|r| ≥ 0.85) keyed by `"msgName/sigName"`. Updated unconditionally (not subject to freeze).
- **Pearson correlation engine in `processBatch()`** — For each Mode 01 OBD-II response frame (SVC `0x41`) in a batch, decodes the PID value and samples all currently-decoded native signals. Accumulates up to 30 `(obd_value, native_value)` pairs per `(pid, signalKey)` in `crossRefObdSamples`/`crossRefNativeSamples`. After ≥ 10 samples, computes Pearson r; if |r| ≥ 0.85, promotes to `_obdCrossRefs`. Entry removed if r drops below threshold. Buffers cleared on DBC change and on BLE disconnect.
- **"OBD ✓" badge in `SignalRow`** — Green monospace label `"OBD ✓  Engine RPM  r=0.97"` shown below the signal name when `obdCrossRefs` contains a confirmed entry for that signal. Correlation coefficient shown for transparency.

### Why
- Scale-invariant correlation handles the common case where a native signal is a raw count at a different scaling factor than the OBD-II physical value (e.g., raw ticks → rpm). The Pearson r approach finds matches without requiring unit knowledge.
- Stuck-signal guard: if either signal is constant, the denominator is ≈0 and r is returned as 0, preventing false positives on parked-vehicle data.

## 2026-06-09 (session 11 — Phase 7.2 OBD-II PID decoder + app scope decision)

### Added
- **`Obd2PidTable` (Phase 7.2)** — `data/obd2/Obd2PidTable.kt`. 63-entry hardcoded J1979 Mode 01 PID table covering: engine RPM, vehicle speed, coolant/intake/ambient/oil temps, MAF, throttle/accel pedal positions, fuel trim (STFT/LTFT), fuel level/pressure/rate, MAP, timing advance, O2 sensors (banks 1–2, sensors 1–8), catalyst temps, barometric pressure, EGR, run time, MIL distance/time, module voltage, torque, ethanol content. Each entry: `pid`, `name`, `unit`, `minBytes`, `decode: (ByteArray) -> Double`.
- **Mode 01 response decoding in live view** — `formatObd2Frame()` in `LiveScreen.kt` now detects SVC `0x41` responses and calls `Obd2PidTable.decode(pid, valueBytes)`. Decoded frames show as `RSP1  SVC:41 PID:0C  Engine RPM: 1726.25 rpm` instead of raw hex. Falls back to raw hex for unknown PIDs. Non-Mode-01 frames unchanged.

### Changed
- **App scope narrowed** — Active OBD-II diagnostics (PID polling, DTC read/clear, ISO-TP, freeze frame, Mode 06) moved to a future separate diagnostic app. `phone_companion` stays a DBC tool. Phase 8 redesigned as a generic signal graph; old O2-specific monitor and active polling phases removed from roadmap.

## 2026-06-09 (session 10 — Android NUS RX plumbing + Phase 7.1 OBD-II frame detection)

### Added
- **NUS RX write channel in `CanBusViewModel`** — Discovers the `6E400002` write characteristic in `onServicesDiscovered` and stores it as `nusCmdChar`. `enableTx()` sends `TX_ENABLE`; `disableTx()` sends `TX_DISABLE` via a new `writeNusCommand()` helper (uses `WRITE_TYPE_NO_RESPONSE`). `_txEnabled: MutableStateFlow<Boolean>` tracks the current TX state; resets to `false` on both user-initiated and unexpected disconnect.
- **TX active indicator in top bar** — A green `"TX"` badge appears in the `TopAppBar` actions area when `txEnabled == true`. Implemented by collecting `canBusVm.txEnabled` in `AppNavigation`.
- **Phase 7.1 — Diagnostic frame detection** — `CanBusViewModel.isObd2Diagnostic(id)` returns `true` for `0x7DF` and `0x7E0–0x7EF`. `_showDiagInLive: MutableStateFlow<Boolean>` (default `true`) and `setShowDiag()` added; OBD-II frames are now a separate bucket from Known/Unknown in the live filter.
- **"Diag" filter chip in `LiveScreen`** — Third `FilterChip` alongside Known/Unknown. OBD-II frames (identified by ID) are shown in light purple and formatted with `formatObd2Frame()`: `REQ SVC:01 PID:0C` for requests (`0x7DF`/`0x7E0–0x7E7`), `RSP1 SVC:41 PID:0C  XX XX XX XX` for responses (`0x7E8–0x7EF`). Request/response prefixes include ECU index for responses.

### Why
- The NUS RX channel is the control path for all future TX-mode features (Phase 7.2+). Making it solid now — with auto-revert on disconnect — means every future OBD-II polling screen can use `enableTx()`/`disableTx()` via `DisposableEffect` without touching the BLE layer again.
- Diagnostic frame detection (7.1) requires no hardware and lets us validate which OBD-II IDs are active on the bus before writing any polling code.

## 2026-06-09 (session 9 — Firmware v1.3.0: F.1/F.2 NUS RX + S-pin GPIO control)

### Added
- **NUS RX write characteristic (F.1)** — UUID `6E400002` added to the NUS service. Accepts UTF-8 commands from the phone. Currently recognised: `TX_ENABLE` and `TX_DISABLE`.
- **S-pin GPIO control (F.2)** — `kCanSPin = GPIO_NUM_3` (D2). Driven `LOW` in `setup()` before any CAN or BLE init — transceiver is in listen-only mode from the very first millisecond. `TX_ENABLE` drives the pin `HIGH` and reinits TWAI in `TWAI_MODE_NORMAL`; `TX_DISABLE` drives it `LOW` and reinits as `TWAI_MODE_LISTEN_ONLY`.
- **Auto-revert to listen-only on disconnect** — `ServerCallbacks::onDisconnect` schedules `TX_DISABLE` via the deferred reinit flags, so the bus is always safe when no phone is connected.
- **Deferred reinit pattern** — BLE callbacks set `volatile bool gReinitRequested` + `gReinitTxTarget`; `loop()` applies the reinit at a safe point (between TWAI receive calls) to avoid driver races.
- **Firmware staged for OTA** — `firmware.bin` and `version.txt` (`1.3.0`) updated in Android assets.

### Changed
- **Firmware version → 1.3.0**
- **`kFirmwareVersion`** bumped; `Serial` startup log now prints S-pin state.
- **`initBle()`** — NUS RX characteristic added before NUS TX in service init order.
- **Hardware comment in file header** updated to document D2 (GPIO3) S-pin wiring.

### Hardware
- S-pin pulldown resistor removed from TJA1051 breakout.
- S-pin wired to XIAO ESP32-S3 D2 (GPIO3).

## 2026-06-09 (session 8 — Phase 6 completion: sparklines, correlation plot, playback mode)

### Added
- **Signal sparklines (Phase 6.2)** — `LogPlayerViewModel` computes a `List<SparklineSeries>` immediately after loading frames. Each series samples 200 evenly-spaced time slots across the session and normalises values to `[0, 1]`. Rendered as a horizontally scrollable `LazyRow` of `SparklineCard` composables (110×~60dp each) above the scrubber. Each card shows signal name, message name, and a Canvas line chart. Tapping a card toggles that signal's selection for correlation (up to 4). Selected cards are highlighted with a colored border + tinted background. Color palette shared with the correlation plot.
- **Multi-signal correlation plot (Phase 6.5)** — "Plot (N)" button appears in the filter chip row when any sparkline is selected (or `sparklines` non-empty for first-time picker). Tapping opens `SignalPickerDialog` — a checkbox list of all decoded signals with colored dots. After confirming, a full-screen `Dialog` shows `CorrelationPlotDialog`: a Canvas-based multi-line plot with one Y-scale per signal (independent scaling), horizontal grid lines, and a time axis footer. Signal legend at top shows name + color for each selected signal. Y-axis labels show max/min for each signal. Plot rendered from `allFrames` filtered to the current scrubber range.
- **Playback mode (Phase 6.6)** — Play/pause `IconButton` added to the left of the scrubber row. When playing, a coroutine in `LogPlayerViewModel` advances `_rangeEnd` by `wallElapsed × playbackSpeed` every 16 ms. Playback stops automatically when `_rangeEnd` reaches `1f` or when the user drags the scrubber. Restarts from current `_rangeEnd` on play (or from `_rangeStart` if already at the end). Speed selector `TextButton` (right of scrubber) offers 0.25×, 0.5×, 1×, 2×, 4× via a `DropdownMenu`.

### Changed
- **`LogPlayerViewModel`** — Added `SparklineSeries` data class, `sparklines` / `selectedSignalKeys` / `isPlaying` / `playbackSpeed` StateFlows, `startPlayback()` / `stopPlayback()` / `setPlaybackSpeed()` / `toggleSignalSelection()` / `clearSignalSelection()` methods, `computeSparklines()` private function. `loadSession()` resets all new state and computes sparklines after frame load. `MAX_CORRELATION_SIGNALS = 4` exposed as companion constant.
- **Phase 6 marked complete** in roadmap.

## 2026-06-09 (session 7 — Phase 6.1/6.3/6.4: timeline scrubber, reference anchor, stimulus-response diff)

### Added
- **Timeline range scrubber (Phase 6.1)** — `RangeSlider` (Material3, two-thumb) pinned to the bottom of `LogBrowserScreen`. Dragging the thumbs sets a normalised `[0, 1]` window in `LogPlayerViewModel`; `visibleFrames` is re-filtered reactively. Label row above the slider shows session start (`00:00`), total duration, and selected sub-range when non-trivial. Scrubber only renders once the session has loaded.
- **Reference event anchor (Phase 6.3)** — Long-press any frame row in `LogBrowserScreen` opens a context menu with "Set as T=0 reference". When set, all frame timestamps switch from `+MM:SS.mmm` (session-relative) to `T+N.Ns` / `T-N.Ns` (reference-relative). An amber star chip (`★ T=0`) in the status row clears the reference on tap.
- **Stimulus-response diff (Phase 6.4)** — Long-press any frame row → "Mark as A" or "Mark as B" bookmarks that moment. When both are set, a "Diff (N changed)" button appears in the filter chip row. Tapping opens `DiffDialog` listing all decoded signals sorted by magnitude of change: signal name (monospace), message name, values at A and B (`a → b`), and a `↑`/`↓` delta indicator. Changed signals are highlighted in `ColorActive`; "appeared" / "disappeared" signals handled. The 2 s snapshot window is shared with the ViewModel's `SNAPSHOT_WINDOW_MS` constant. Bookmark chips (blue for A, green for B) appear below the filter chips and can be cleared individually.
- **`SignalDiff` data class** in `LogPlayerViewModel` — `signalName`, `messageName`, `valueAtA?`, `valueAtB?`, computed `delta?` property.
- **`allFrames` public StateFlow** on `LogPlayerViewModel` — gives `LogBrowserScreen` the full unfiltered list for scrubber endpoint computation.
- **`clearBookmarkA()` / `clearBookmarkB()`** on `LogPlayerViewModel` — clear individual bookmarks and reset diffResult without touching the other bookmark.

### Changed
- **`visibleFrames` in `LogPlayerViewModel`** — updated from a 3-flow `combine` to a nested 3+3 combine that also filters by the selected time range before the known/unknown filter runs. Range is based on the first/last frame timestamps in `_allFrames`.
- **`loadSession()`** now resets `_rangeStart`, `_rangeEnd`, `_referenceTs`, `_bookmarkA`, `_bookmarkB`, and `_diffResult` on every new session load.
- **`LogBrowserScreen` layout** — `LazyColumn` now wrapped in a `Box(weight = 1f)` so the scrubber is anchored at the bottom. The `SessionHeader` extracted to its own composable. Empty-state message distinguishes "no frames at all" from "no frames in selected window".

## 2026-06-09 (session 6 — Phase 5 completion: canonical names, new DBC, copy signal)

### Added
- **`CanonicalSignals` catalog** — `CanonicalSignal` data class + `CanonicalSignals.ALL` list with 47 common automotive signals (engine RPM/torque/load, wheel speeds, O2 sensors, fuel trims, temperatures, dynamics, body signals) including unit, min, max, factor, offset, and description for each.
- **Canonical name autocomplete (Phase 5.5)** — `SignalEditorScreen` now shows a `DropdownMenu` below the signal name field when 2+ characters are typed. Each suggestion shows the canonical name (monospace) + unit and description. Selecting auto-fills `unit`, `min`, `max`, `factor`, and `offset`.
- **Create new DBC (Phase 5.6)** — `DbcListScreen` has a FAB (+ icon) that opens a "New DBC" dialog. The name field filters to letters, digits, `_`, and `-`. Duplicate names are blocked with an inline error. `SettingsViewModel.createDbc(name)` writes an empty DBC and refreshes the list. Empty-state text updated to mention the FAB.
- **Copy signal from another DBC (Phase 5.7)** — "Copy from…" button in `SignalEditorScreen` opens a two-level `AlertDialog` picker: level 1 lists available DBC files; level 2 lists signals from the selected DBC (sorted by message, showing message name + unit + min/max). Selecting a signal copies all fields including bit layout (startBit, length, byteOrder, signed, factor, offset, unit, min, max, comment, valueDescriptions) into the form. DBC loading runs on `Dispatchers.IO` via `LaunchedEffect`.

### Changed
- **Phase 5 marked mostly complete** in roadmap (5.3 rate-of-change backlog; everything else done).
- **Phase 6 marked active** in roadmap.

## 2026-06-09 (session 5 — Phase 5 signal health + Phase 11.1 help tile)

### Added
- **ROADMAP.md refreshed** — Current state section rewritten to reflect firmware v1.2.0, completed Phase 4 (1.3/1.4 now actually done), hardware-blocked path clearly noted (Phase 7.2+ require S-pin mod), Phase 11 Documentation & help added as a proper phase, Firmware backlog updated with F.6 ✓ and new F.7 (TX safety model). User help note converted to structured items 11.1–11.3.
- **Stuck signal detection (Phase 5.1)** — `CanBusViewModel` tracks a rolling window (50 frames / ~5 s) of decoded values per signal. A signal is flagged `isStuck` when all window values are equal. History is tracked in `signalValueHistory: MutableMap<String, ArrayDeque<Double>>` populated in `processBatch`.
- **Pegged signal detection (Phase 5.2)** — A signal is flagged `isPegged` when all window values are at or beyond the DBC `min` or `max` (only checked when `min < max` to avoid false positives on unconfigured ranges). Indicates wrong scaling, startBit, or length.
- **`SignalHealth` data class** — `data class SignalHealth(val isStuck: Boolean, val isPegged: Boolean)` exposed as `canBusVm.signalHealth: StateFlow<Map<String, SignalHealth>>`. Updated every processing batch; gated by the freeze flag alongside other UI StateFlows.
- **Health flags in SignalsScreen** — Amber banner row at top of Signals tab shows "N signals with health warnings" and navigates to the health panel on tap. Individual `SignalRow` shows amber `Warning` icon next to the value and a sub-label (`Stuck`, `Pegged`, or `Stuck+Pegged`) under the signal name.
- **Signal health panel screen (Phase 5.4)** — New `SignalHealthScreen` at `signal_health` route. Lists all currently flagged signals with message name, CAN ID, and `STUCK`/`PEGGED` chips. Empty state explains the 5-second observation window needed before flags appear. Accessible from the amber banner on the Signals tab.
- **Help & Documentation tile (Phase 11.1)** — New tile at bottom of SettingsScreen opens the project GitHub URL in the system browser. Uses `LocalContext` + `Intent.ACTION_VIEW`. Unobtrusive placement — settings screen only.

### 2026-06-09 (session 4 — infrastructure fixes from external review)

### Added
- **Connection persistence** — Last device address/name persisted in SharedPreferences. On app start, the BLE button in the top bar glows green and triggers immediate reconnect to the saved device (no scan required). On unintentional disconnect, auto-reconnect is attempted after 3 s, then 15 s (up to 3 attempts). User-initiated `disconnect()` suppresses this. `lastKnownDevice` StateFlow exposed for UI.
- **Operational telemetry** — `CanStats` data class exposed as `canBusVm.canStats` StateFlow, tracking: total frames processed, decode-out-of-range events (signals extending past DLC), parse errors, and BLE notification count. Updates every processing batch.
- **DBC mux signal awareness** — `DbcSignal.muxIndicator` field added (`"M"` = selector, `"m<N>"` = muxed at slot N, `null` = plain). Parser captures and preserves the indicator. Writer emits it in DBC output. Decoder in `processBatch` now: decodes the mux selector first, then skips muxed signals whose slot doesn't match the current selector value. Prevents garbage values when loading opendbc files with multiplexed signals.
- **Per-frame firmware timestamps (v1.2.0)** — Firmware `appendFrame` now appends `,<millis_mod_65536>` to each frame line. Android parser extracts this into `CanFrame.firmwareTimestampMs`. `handleNotification` uses intra-packet firmware offsets to reconstruct per-frame timestamps anchored to BLE receipt time, eliminating batch-level timestamp compression where all frames in a notification shared a single receive time.

### Changed
- **Session notes surfacing** — `SessionRow` in VehicleDetailScreen now shows `notes` below the frame/duration line. Log browser header shows notes if non-empty.
- **LogPlayerViewModel** — now uses `SignalDecoder.decodeOrNull()` for replay decoding; out-of-range signals are excluded from the displayed map rather than showing 0.
- **Roadmap Phase 4** — Updated status: items 4.1–4.12 marked complete.
- **Firmware v1.1.0 → v1.2.0** — Adds per-frame millis timestamp to BLE packet format. Backward compatible: old app ignores the extra field; new app uses it when present. New firmware.bin staged in Android assets for OTA delivery.

## 2026-06-09 (session 3 — gap fixes)

### Added
- **Signal deletion** (Item 1): Delete icon on each signal row in SignalsScreen. Confirms with AlertDialog before removing from DBC.
- **Message deletion** (Item 1): Three-dot overflow menu on MessageRow header with "Delete message" option and AlertDialog confirmation.
- **Editable DLC on new message** (Item 2): DLC field shown in SignalEditorScreen when creating a new message; defaults to observed frame size from live data. BitGrid and bounds checks now respect the user-entered or observed DLC instead of hardcoding 8.
- **Search / filter — Unknowns screen** (Item 3): TextField filter bar on UnknownsScreen filters by hex ID string (uppercase prefix match). Clear button resets filter; trigger window subsection filters too.
- **Search / filter — Signals screen** (Item 3): TextField filter bar on SignalsScreen filters by message name and CAN ID hex string.
- **Coverage badges** (Item 4): "N / M messages seen this session" summary row at top of SignalsScreen. Message header rows show a green (seen) or dim (not seen) dot indicator.
- **Frame inspector for known messages** (Item 5): FrameInspectorScreen now accepts both unknown and known CAN IDs. Known messages show recent-frame history (ring buffer added to MessageState) and overlay defined signal byte ranges with distinct tint colors and a legend. Inspect action added to SignalsScreen message overflow menu.
- **Signal comment field** (Item 6): Multiline "Notes / comment" field in SignalEditorScreen. Written to DBC `CM_` block.
- **VAL_ editor** (Item 7): Collapsible "Value descriptions" section in SignalEditorScreen. Add/delete raw-value → label entries via dialog; persisted to DBC `VAL_` block.
- **Session notes at record time** (Item 9): Notes field added to vehicle picker dialog in LiveScreen. Passed through to SessionMeta on recording start.

### Changed
- **Trigger window survives freeze** (Item 8): `processBatch()` now always runs trigger bookkeeping and recording; only StateFlow UI emissions are gated by the frozen flag. Frames processed while frozen retain correct `triggeredInWindow` state.
- **DLC guard in signal decoder** (Item 10): `SignalDecoder.decodeOrNull()` returns null when a signal's bit range extends past the frame DLC. `CanBusViewModel.processBatch()` now uses `decodeOrNull()` — out-of-range signals are excluded from the decoded map rather than silently returning 0. `SignalEditorScreen` shows a warning when the signal extends past the observed frame DLC.

## 2026-06-09

### Changed
- `readme.md`: corrected "Frame format" section — each BLE notification carries one or more packed frames (not one), clarified hex byte encoding, and added the EXT ID example. Added OTA GATT service UUIDs. Expanded firmware flash section to document `build_and_stage_firmware.sh` and wireless OTA update path.

---

## 2026-06-09

### Changed
- Updated hardware target for the BLE companion dongle from ESP32-C6 to ESP32-S3 (Seeed Studio XIAO ESP32-S3). README updated to reflect the new board target.

### Verified
- OTA firmware update confirmed working end-to-end over BLE. Device reports v1.1.0 via version characteristic; app shows "Device is up to date." Dongle no longer requires USB cable for firmware updates.

---

### Added
- BLE OTA firmware update support — dongle can now be updated wirelessly after the initial USB flash.
  - `partitions.csv`: custom dual-OTA partition table for 8 MB flash (app0/app1 at 3.2 MB each).
  - OTA GATT service (UUIDs `6E410001–6E410005`): control (write), data (write-no-response), status (notify), version (read) characteristics.
  - CAN frame streaming pauses automatically during OTA to avoid BLE congestion.
  - `esp_ota_mark_app_valid_cancel_rollback()` called on boot so the bootloader does not roll back a healthy image.
- `scripts/build_and_stage_firmware.sh`: compiles firmware, stages `firmware.bin` + `version.txt` into Android assets, and optionally flashes via USB in one command.
- **Settings → Firmware Update** screen in the Android app: shows device version vs. bundled version, streams chunks with a progress bar, and reports errors clearly. Firmware version is read from the OTA version characteristic on connect.
- Vehicle detail screen (Settings → Vehicles → tap vehicle): shows vehicle info card, Edit Profile button, and a list of all recordings for that vehicle sorted by date.
- Log browser screen: tap any recording to open a full frame-by-frame view with relative timestamps (`+MM:SS.mmm`), Known/Unknown filter chips, decoded signal values for known IDs, and raw hex for unknowns.
- `LogPlayerViewModel`: loads a session's `frames.log`, re-decodes against the DBC that was active at record time, and exposes filtered frames as a StateFlow.

### Changed
- Vehicle list row tap now navigates to vehicle detail instead of directly to the edit screen. Edit is accessible via the detail screen.
- `SettingsViewModel` now exposes `sessionRepository` and `loadSessionsForVehicle()` / `vehicleSessions` StateFlow for the vehicle detail screen.
- OTA GATT characteristic discovery and CCCD setup chained in `onDescriptorWrite` so UART and OTA notifications are enabled sequentially without GATT operation conflicts.
- CLAUDE.md updated: changelog entries are now required for all `phone_companion/` changes.

## 2026-06-08 (session 2)

### Added
- Frame packing in ESP32 firmware: multiple CAN frames batched per BLE notification (10 ms flush window, 400-byte early-flush threshold) to avoid frame drops on busy buses.
- `DisplayFrame.seq` monotonic counter to fix LazyColumn duplicate-key crashes when the same CAN ID fires multiple times per millisecond.
- Frame Inspector screen (`inspector/{canId}`) showing byte-level diff grid for unknown CAN IDs with `+Δms` row deltas and a "Define signal" shortcut.
- Long-press verification marking on signal rows (VERIFIED / SUSPECT / UNVERIFIED with optional notes), backed by sidecar JSON.
- Record / Stop button on Live screen with vehicle picker dialog.
- Freeze button in top app bar (Pause/Play) — suppresses StateFlow updates without interrupting internal processing or recording.
- Unknown ID persistence: IDs stay visible for 10 s of silence instead of being evicted each 100 ms batch tick.
- Connect / Bluetooth icon button in top app bar when disconnected — lets the user re-open the device scanner at any time.
- Pull from Git tile in Settings (fetches remote changes, refreshes DBC and vehicle lists).
- INTERNET and ACCESS_NETWORK_STATE permissions required for JGit clone/push.
- `CLAUDE.md` at repo root documenting arduino-cli and JAVA_HOME tool paths.

### Changed
- BLE connect dialog can now be dismissed (Skip button / tap-outside) without connecting — app is fully navigable without a dongle.
- "Sync to Git" renamed to "Push to Git" throughout Settings UI for clarity.
- Pause button tint set to white (was inheriting an invisible color on dark backgrounds).
- `stopScan()` no longer clears the scanned device list; new `dismissScanDialog()` does both, preventing the picker from reappearing after dismissal.

### Fixed
- `AcceleratorPedalPos` (0x1A1) startBit corrected from `48|8@1+` (byte 6, past end of 3-byte frame) to `16|8@1+` (byte 2).
- `SteeringWheelAngle` (0x1E5) startBit corrected from `7|16@0-` (bytes 0–1, constant) to `47|16@0-` (bytes 5–6, Motorola MSB — confirmed against steering sweep capture).
- `YawRate` (0x1E9) startBit corrected from `51|12@0-` (decoded 64 grad/s at rest) to `35|12@0-` (bytes 4–5, 0 grad/s at rest — matches opendbc reference).
- Session `appendFrame()` wrapped in try-catch to handle close race condition.

## 2026-06-08

### Added
- Bootstrapped Android app at `android_app/` to scan/connect over BLE and display incoming CAN frame text.
- Added ESP32 firmware at `firmware/esp32_can_ble_bridge/esp32_can_ble_bridge.ino` for CAN listen-only capture and BLE NUS notifications.
- Added local dependency bootstrap script `scripts/setup_android_env.sh` for JDK/Gradle/Android SDK command-line setup.
- Added project README instructions for setup, build, flash, and runtime usage.

### Changed
- Installed and used repository Arduino CLI (`/home/daniel/documents/workspace/robo-services/bin/arduino-cli`) for firmware compile/upload.
- Corrected hardware target from C6 to S3 during flashing after chip ID mismatch.
- Updated BLE advertising setup to explicitly advertise as `CAN-DONGLE` and auto-restart advertising on disconnect/idle checks.
- Adjusted firmware behavior to keep BLE advertising even if CAN init fails.

### Verified
- Android debug app builds and installs on device.
- Firmware flashes successfully to connected ESP32-S3.
- BLE advertisement is detectable as `CAN-DONGLE` in external scan.

### Known issue
- End-to-end phone connection/data flow remains unresolved in current session despite successful flashing and observable BLE advertising.
