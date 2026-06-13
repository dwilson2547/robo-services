## 2026-06-12

### Added
- Initial Android companion app for the race_logger firmware (SparkFun ESP32 Thing Plus).
- BLE scan filtered by race_logger service UUID (`6ba1c200-...`) — auto-scans on launch; reconnects on drop.
- Status screen: animated red/yellow/green LED circle driven by STATUS characteristic notifications (`0=boot`, `1=waiting`, `2=ready`), mirroring the physical LED on the device.
- "Mark Staging" button writes to the STAGING characteristic to publish a `tp="mrk"` marker packet.
- Config screen: MQTT host, port, topic, username, and password fields. Writes each characteristic in sequence then writes COMMIT to persist config to device SPIFFS.
- Config values pre-populated from device on connect (host, port, topic, user — password is write-only on firmware side).
- Dark theme matching phone_companion palette.
- Uses shared Android SDK and JDK from `phone_companion/.tools/`.
