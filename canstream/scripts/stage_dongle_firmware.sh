#!/usr/bin/env bash
# Compile the dongle firmware (can-dongle-firmware's esp32_can_ble_bridge) and stage it in this
# app's assets, so "Update dongle" in Settings installs it over BLE. Rebuild and install
# the APK afterwards.
#
#   scripts/stage_dongle_firmware.sh            # can-dongle-firmware found through the workspace superrepo
#   CAN_DONGLE_FIRMWARE=/path/to/can-dongle-firmware scripts/stage_dongle_firmware.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$HERE/../android_app"
if [[ -z "${CAN_DONGLE_FIRMWARE:-}" ]]; then
    SUPER="$(git -C "$HERE" rev-parse --show-superproject-working-tree)"
    [[ -n "$SUPER" ]] || { echo "not inside the workspace superrepo; set CAN_DONGLE_FIRMWARE" >&2; exit 1; }
    CAN_DONGLE_FIRMWARE="$SUPER/embedded/can-dongle-firmware"
fi
SRC="$CAN_DONGLE_FIRMWARE/esp32_can_ble_bridge"
[[ -d "$SRC" ]] || { echo "$SRC not found; init the can-dongle-firmware submodule" >&2; exit 1; }
ARDUINO_CLI="${ARDUINO_CLI:-arduino-cli}"
command -v "$ARDUINO_CLI" >/dev/null || { echo "arduino-cli not on PATH (or set ARDUINO_CLI)" >&2; exit 1; }
FQBN="esp32:esp32:XIAO_ESP32C6"
OUT="$APP/app/src/main/assets/dongle"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

VERSION=$(grep -oP 'kFirmwareVersion\[\]\s*=\s*"\K[^"]+' "$SRC/esp32_can_ble_bridge.ino")
[[ -n "$VERSION" ]] || { echo "no kFirmwareVersion in $SRC" >&2; exit 1; }
COMMIT=$(git -C "$CAN_DONGLE_FIRMWARE" rev-parse --short HEAD)
DIRTY=$(git -C "$CAN_DONGLE_FIRMWARE" status --porcelain -- esp32_can_ble_bridge | head -1)
[[ -z "$DIRTY" ]] || echo "warning: $SRC has uncommitted changes" >&2

"$ARDUINO_CLI" compile --fqbn "$FQBN" --output-dir "$BUILD" "$SRC" >/dev/null
mkdir -p "$OUT"
cp "$BUILD/esp32_can_ble_bridge.ino.bin" "$OUT/firmware.bin"
echo "$VERSION" > "$OUT/version.txt"
echo "staged dongle firmware $VERSION (can-dongle-firmware $COMMIT, $(stat -c %s "$OUT/firmware.bin") bytes) -> $OUT"
