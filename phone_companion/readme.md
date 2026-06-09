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

Open this sketch in Arduino IDE / arduino-cli:

`firmware/esp32_can_ble_bridge/esp32_can_ble_bridge.ino`

Board target: **Seeed Studio XIAO ESP32-S3**

## BLE transport details

- Device name: `CAN-DONGLE`
- Service UUID: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- Notify characteristic UUID: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`

## Frame format from firmware to app

Each BLE notification sends one text line:

`STD|EXT,0xID,DLC,B0,B1,...,Bn`

Example:

`STD,0x123,8,11,22,33,44,55,66,77,88`
