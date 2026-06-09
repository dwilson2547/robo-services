# Changelog

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
