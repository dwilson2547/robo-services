# robo-services

## Conventions

### Changelog
Every change to the phone companion app — firmware or Android app — must have a corresponding entry in `embedded/dbc-forge/CHANGELOG.md` before the session ends. (The app moved from `robo-services/phone_companion/` to `embedded/dbc-forge` in 2026; older references to `phone_companion/` below are historical.) Add it under a new `## YYYY-MM-DD` heading (or append to today's heading if one already exists). Cover: what was added/changed/fixed and why, not just what files were touched. The new `capture_device_configs/mid_tier_device/phone_gateway/` app keeps its own CHANGELOG there.

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
| `arduino-cli` | `~/bin/arduino-cli` (boards/libraries pre-loaded in `~/.arduino15`) |
| `java` | system OpenJDK 17 (`/usr/bin/java`); no repo-local `.tools/jdk-21` anymore |
| Android SDK | `~/Android/Sdk` (platform 35, build-tools 34 + 35, platform-tools); `adb` is `~/Android/Sdk/platform-tools/adb`. Gradle finds it through each app's uncommitted `local.properties` (`sdk.dir=...`) |

### arduino-cli

Always use `~/bin/arduino-cli` — it has the correct board definitions and libraries installed.

Example flash command (run from `embedded/dbc-forge/firmware/esp32_can_ble_bridge/`):

```bash
arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6 . && \
arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32C6 --port /dev/ttyACM0 .
```

### Android builds

Run from the app's `android_app/` directory (e.g. `embedded/dbc-forge/android_app` or
`capture_device_configs/mid_tier_device/phone_gateway/android_app`), then install with `adb`:

```bash
./gradlew assembleDebug
```

Install with Windows adb over wireless debugging, not WSL's adb over USB (it stays
unauthorized): see `docs/tooling/adb.md`.

```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/dwils/android-platform-tools/
/mnt/c/Users/dwils/android-platform-tools/platform-tools/adb.exe install -r \
  'C:\Users\dwils\android-platform-tools\app-debug.apk'
```
