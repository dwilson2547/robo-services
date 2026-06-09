# Phone Companion (CAN over BLE)

This sub-project now includes:

- `firmware/esp32_can_ble_bridge/esp32_can_ble_bridge.ino`  
  ESP32-S3 firmware that listens to CAN in TWAI listen-only mode and publishes frames over BLE (Nordic UART profile).
- `android_app/`  
  Android Kotlin app that scans for the dongle, connects over BLE, subscribes to notifications, and displays incoming CAN frame lines.
- `scripts/setup_android_env.sh`  
  Local no-sudo dependency bootstrap for JDK, Gradle, and Android SDK command-line tools.

## 1) Install local Android dependencies (no sudo)

From this directory:

```bash
chmod +x scripts/setup_android_env.sh
./scripts/setup_android_env.sh
```

The script installs tools under `phone_companion/.tools` and prints exports to use in your shell.

## 2) Build the Android app

```bash
cd android_app
./gradlew assembleDebug
```

Debug APK output:

`android_app/app/build/outputs/apk/debug/app-debug.apk`

## 3) Run on device

1. Enable Developer Options + USB debugging on Android.
2. Connect device and install:

```bash
cd android_app
./gradlew installDebug
```

## 4) Flash ESP32 firmware

Use the build script to compile, stage firmware into Android assets, and optionally flash over USB in one step:

```bash
chmod +x scripts/build_and_stage_firmware.sh
./scripts/build_and_stage_firmware.sh          # compile + stage only
./scripts/build_and_stage_firmware.sh --flash  # compile, stage, and USB flash
```

Or flash directly with arduino-cli (run from `firmware/esp32_can_ble_bridge/`):

```bash
../../../bin/arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32S3 . && \
../../../bin/arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32S3 --port /dev/ttyACM0 .
```

Board target: **Seeed Studio XIAO ESP32-S3**

After the initial USB flash, subsequent updates can be pushed wirelessly via **Settings → Firmware Update** in the app.

## BLE transport details

### NUS (CAN frame streaming)
- Device name: `CAN-DONGLE`
- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- TX notify characteristic: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

### OTA update service
- Service UUID: `6E410001-...` (base `6E41xxxx-B5A3-F393-E0A9-E50E24DCCA9E`)
  - `6E410002` — control (write)
  - `6E410003` — data (write-no-response)
  - `6E410004` — status (notify)
  - `6E410005` — version (read)

## Frame format from firmware to app

Each BLE notification contains **one or more** newline-delimited frame lines packed together (10 ms flush window, 400-byte early-flush threshold):

```
STD,0xNNN,DLC,B0,B1,...,Bn\n
EXT,0xNNNNNNNN,DLC,B0,B1,...,Bn\n
```

- `STD` / `EXT` — standard (11-bit) or extended (29-bit) frame type
- ID printed as 3 hex digits for STD, 8 hex digits for EXT
- Data bytes printed as two-digit uppercase hex, no `0x` prefix

Example notification payload (two frames packed):

```
STD,0x123,8,11,22,33,44,55,66,77,88
EXT,0x18DB33F1,3,02,01,00
```
