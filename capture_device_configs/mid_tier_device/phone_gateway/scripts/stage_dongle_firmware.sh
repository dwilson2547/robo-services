#!/usr/bin/env bash
# Compile the dongle firmware (dbc-forge's esp32_can_ble_bridge) and stage it in this
# app's assets, so "Update dongle" in Settings installs it over BLE. Rebuild and install
# the APK afterwards.
#
#   scripts/stage_dongle_firmware.sh            # dbc-forge found through the workspace superrepo
#   DBC_FORGE=/path/to/dbc-forge scripts/stage_dongle_firmware.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$HERE/../android_app"
if [[ -z "${DBC_FORGE:-}" ]]; then
    SUPER="$(git -C "$HERE" rev-parse --show-superproject-working-tree)"
    [[ -n "$SUPER" ]] || { echo "not inside the workspace superrepo; set DBC_FORGE" >&2; exit 1; }
    DBC_FORGE="$SUPER/embedded/dbc-forge"
fi
SRC="$DBC_FORGE/firmware/esp32_can_ble_bridge"
ARDUINO_CLI="${ARDUINO_CLI:-$HOME/bin/arduino-cli}"
FQBN="esp32:esp32:XIAO_ESP32C6"
OUT="$APP/app/src/main/assets/dongle"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT

VERSION=$(grep -oP 'kFirmwareVersion\[\]\s*=\s*"\K[^"]+' "$SRC/esp32_can_ble_bridge.ino")
[[ -n "$VERSION" ]] || { echo "no kFirmwareVersion in $SRC" >&2; exit 1; }
COMMIT=$(git -C "$DBC_FORGE" rev-parse --short HEAD)
DIRTY=$(git -C "$DBC_FORGE" status --porcelain -- firmware/esp32_can_ble_bridge | head -1)
[[ -z "$DIRTY" ]] || echo "warning: $SRC has uncommitted changes" >&2

"$ARDUINO_CLI" compile --fqbn "$FQBN" --output-dir "$BUILD" "$SRC" >/dev/null
mkdir -p "$OUT"
cp "$BUILD/esp32_can_ble_bridge.ino.bin" "$OUT/firmware.bin"
echo "$VERSION" > "$OUT/version.txt"
echo "staged dongle firmware $VERSION (dbc-forge $COMMIT, $(stat -c %s "$OUT/firmware.bin") bytes) -> $OUT"
