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
`files/frames/frames-YYYYMMDD.bin` on the phone (4-byte big-endian length +
serialized frame, repeated); there is no backfill uploader yet.

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
