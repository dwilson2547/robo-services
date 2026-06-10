# robo-services

## Conventions

### Changelog
Every change to `phone_companion/` — firmware or Android app — must have a corresponding entry in `phone_companion/CHANGELOG.md` before the session ends. Add it under a new `## YYYY-MM-DD` heading (or append to today's heading if one already exists). Cover: what was added/changed/fixed and why, not just what files were touched.

## Reference library

`docs/hardware/` and `docs/tooling/` are a shared ground-truth library built up from real testing across projects. Consult them before wiring up a component or running a CLI tool — they contain verified gotchas, pin assignments, and command patterns that apply repo-wide.

**Read before:**
- Wiring or configuring any component listed in `docs/hardware/` — pinouts, voltage requirements, and library quirks are documented there
- Running `arduino-cli`, reading serial output, or identifying a board/port — working command patterns are in `docs/tooling/`

**Add an entry when:**
- A new microcontroller, breakout, or sufficiently complex component is introduced — one file per component in `docs/hardware/`, following the existing format
- A bug or unexpected hardware behavior is found that would affect other projects using the same component — add it to that component's gotchas section
- A CLI workflow, flag combination, or tool pattern is discovered through trial and error to be the reliable way — capture it in `docs/tooling/` so it isn't re-learned

Each README in those directories has an index table; keep it up to date when adding files.

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
