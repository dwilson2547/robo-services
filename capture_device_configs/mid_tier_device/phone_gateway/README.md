# phone_gateway

Android app that turns a phone into the vehicle's telemetry gateway: it reads
CAN frames and BNO085 IMU samples from the dbc-forge dongle over BLE, adds the
phone's GPS, and publishes one `TelemetryFrame` (`telemetry.proto` at the repo
root) per second to the cluster over MQTT. Design and the reasons for it:
`docs/decisions/2026-11-03-phone-gateway-public-mqtt.md`.

```
dongle --BLE NUS--> DongleLines --> FrameAssembler --> FrameLog (always)
phone GPS ----------------------------^          \--> Publisher --TLS 8883--> pub-sub mosquitto
                                                                  --> kreceiver --> Iggy
```

| Piece | File |
|---|---|
| BLE connection to the dongle | `ble/DongleClient.kt` |
| Dongle firmware update over BLE | `ble/DongleOta.kt` (protocol), `DongleClient.update` |
| Line parsing, timestamp reconstruction | `ble/DongleLines.kt` |
| One frame per second, proto scaling | `telemetry/FrameAssembler.kt` |
| Local log of every frame | `log/FrameLog.kt` |
| MQTT over TLS, token as password | `mqtt/Publisher.kt`, `mqtt/CertificateName.kt` |
| authentik sign-in and refresh | `auth/Auth.kt` |
| Endpoints | `Config.kt` |
| Foreground capture service | `GatewayService.kt` |

## Using it

Open the app and grant Bluetooth, location and notifications: it connects to
the dongle while it is open, and the indicator at the top shows whether the
dongle is connected and sending. Sign in (your authentik user must be in group
`phone-gateway-users`) and press Start capture. At the start/finish line press
"Mark start/finish here": the lap-segmentation pipeline counts laps from that
point. The status lines
show the dongle and broker state and frame counters. Frames stay in
`files/frames/session-<id>.bin` on the phone (4-byte big-endian length +
serialized frame, repeated); the background catch-up (`upload/CatchUpWorker.kt`)
sends whatever the pipeline is missing after a drive.

## Build

Needs JDK 17 and an Android SDK (on this machine `~/Android/Sdk`; Gradle reads
it from `android_app/local.properties`, which is not committed:
`echo "sdk.dir=$HOME/Android/Sdk" > android_app/local.properties`).

```bash
cd android_app
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

`scripts/build-install.sh` builds and installs over adb.

`PublisherBrokerTest` runs only when pointed at a broker configured like the
pub-sub one (mosquitto-go-auth + mqtt-auth); the variables it needs are in its
header comment. Without them it is skipped.

## Dongle firmware

The app carries a dongle firmware image (`android_app/app/src/main/assets/dongle/`:
`firmware.bin` and `version.txt`), built from dbc-forge's
`firmware/esp32_can_ble_bridge` (`embedded/dbc-forge` in the workspace). Settings →
Dongle shows the dongle's version and the bundled one, and offers "Update dongle"
when the bundled one is newer, or "Reinstall" when they match. It is offered only
while the dongle is connected and nothing is recording, and capture can't start
during an update. The dongle verifies the image before switching to it, so an
interrupted or corrupt upload leaves the old firmware running. From firmware 1.7.2 the
dongle keeps a new image only once a phone reconnects to it; one that can't serve
BLE is rolled back on its next reset (power cycle), verified on the bench. The app
reports an update done only when the dongle reconnects on the new version.

Releasing new dongle firmware:

1. Change the firmware in dbc-forge, bump `kFirmwareVersion` (the app compares
   dotted versions), add a dbc-forge `CHANGELOG.md` entry, commit and push.
2. `scripts/stage_dongle_firmware.sh` compiles it with `~/bin/arduino-cli`
   (`esp32:esp32:XIAO_ESP32C6`, dual-OTA partition table from the sketch folder)
   and copies the image and version into the assets. It warns if the firmware tree
   has uncommitted changes.
3. Build, test and install the app (above), and add a `CHANGELOG.md` entry here
   naming the firmware version it carries. Commit the staged assets with it.
4. On the phone: Settings → Dongle → Update. Check the new version shows after the
   dongle restarts, and that a short recording looks right (`dongle stats` in logcat).

USB flashing (`arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32C6 --port /dev/ttyACM0`
from the firmware folder) is only for recovery: the dongle unplugged from any
bus or simulator and powered by USB alone.

