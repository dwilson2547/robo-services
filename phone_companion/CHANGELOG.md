# Changelog

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
