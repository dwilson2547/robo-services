# robo-services

## Conventions

### Changelog
Every change to `phone_companion/` — firmware or Android app — must have a corresponding entry in `phone_companion/CHANGELOG.md` before the session ends. Add it under a new `## YYYY-MM-DD` heading (or append to today's heading if one already exists). Cover: what was added/changed/fixed and why, not just what files were touched.

## Tool paths

| Tool | Path |
|---|---|
| `arduino-cli` | `robo-services/bin/arduino-cli` (has boards and libraries pre-loaded) |
| `java` / `JAVA_HOME` | `robo-services/phone_companion/.tools/jdk-21` |
| `adb` | `robo-services/phone_companion/.tools/android-sdk/platform-tools/adb` |

### arduino-cli

Always use `robo-services/bin/arduino-cli`, not any `.tools` copy — only this one has the correct board definitions and libraries installed.

Example flash command (run from `phone_companion/firmware/esp32_can_ble_bridge/`):

```bash
../../../bin/arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32S3 . && \
../../../bin/arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32S3 --port /dev/ttyACM0 .
```

### Android builds

Set `JAVA_HOME` before running Gradle, then install with `adb`:

```bash
JAVA_HOME=/home/daniel/documents/workspace/robo-services/phone_companion/.tools/jdk-21 \
  ./gradlew assembleDebug

phone_companion/.tools/android-sdk/platform-tools/adb install -r \
  android_app/app/build/outputs/apk/debug/app-debug.apk
```
