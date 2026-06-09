#!/usr/bin/env bash
# Compile the dongle firmware and stage it for bundling in the Android app.
# Run this from anywhere inside the repo. After it completes, rebuild the APK
# and install it to pick up the new firmware binary.
#
# Usage:
#   ./phone_companion/scripts/build_and_stage_firmware.sh [--upload /dev/ttyACM0]
#
# Options:
#   --upload <port>   Also flash via USB after staging (skipped if omitted)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

ARDUINO_CLI="$REPO_ROOT/bin/arduino-cli"
FIRMWARE_SRC="$REPO_ROOT/phone_companion/firmware/esp32_can_ble_bridge"
ASSETS_DIR="$REPO_ROOT/phone_companion/android_app/app/src/main/assets/firmware"
BUILD_DIR="/tmp/can_ble_ota_build"
FQBN="esp32:esp32:XIAO_ESP32S3"

UPLOAD_PORT=""
if [[ "${1:-}" == "--upload" && -n "${2:-}" ]]; then
    UPLOAD_PORT="$2"
fi

# ── Extract version from source ───────────────────────────────────────────────
VERSION=$(grep -oP 'kFirmwareVersion\[\]\s*=\s*"\K[^"]+' "$FIRMWARE_SRC/esp32_can_ble_bridge.ino")
if [[ -z "$VERSION" ]]; then
    echo "ERROR: Could not extract kFirmwareVersion from firmware source." >&2
    exit 1
fi
echo "[1/3] Firmware version: $VERSION"

# ── Compile ───────────────────────────────────────────────────────────────────
mkdir -p "$BUILD_DIR" "$ASSETS_DIR"
echo "[2/3] Compiling for $FQBN..."
"$ARDUINO_CLI" compile \
    --fqbn "$FQBN" \
    --output-dir "$BUILD_DIR" \
    "$FIRMWARE_SRC"

# ── Stage for Android app ─────────────────────────────────────────────────────
cp "$BUILD_DIR/esp32_can_ble_bridge.ino.bin" "$ASSETS_DIR/firmware.bin"
echo "$VERSION" > "$ASSETS_DIR/version.txt"
echo "[3/3] Staged firmware.bin and version.txt → $ASSETS_DIR"
echo "      Rebuild and install the Android APK to bundle this version."

# ── Optional USB flash ────────────────────────────────────────────────────────
if [[ -n "$UPLOAD_PORT" ]]; then
    echo "[+] Flashing to $UPLOAD_PORT..."
    "$ARDUINO_CLI" upload \
        --fqbn "$FQBN" \
        --port "$UPLOAD_PORT" \
        --input-dir "$BUILD_DIR" \
        "$FIRMWARE_SRC"
    echo "[+] Flash complete."
fi
