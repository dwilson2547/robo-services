# ESP32-S3 Bluetooth/USB media remote (XIAO ESP32-S3)

Reference for the 9-button media remote firmware at:

`capture_device_configs/media_remote/esp32_s3_media_remote/esp32_s3_media_remote.ino`

---

## Board and build target

- Board: Seeed XIAO ESP32-S3
- FQBN: `esp32:esp32:XIAO_ESP32S3`
- Uses repo Arduino CLI: `./bin/arduino-cli`

Compile/upload:

```bash
./bin/arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32S3 \
  capture_device_configs/media_remote/esp32_s3_media_remote/

./bin/arduino-cli upload --fqbn esp32:esp32:XIAO_ESP32S3 --port /dev/ttyACM0 \
  capture_device_configs/media_remote/esp32_s3_media_remote/
```

---

## Button map (active-low, INPUT_PULLUP)

Each button is wired from the listed pin to GND.

| Function | Pin |
|---|---|
| Play/Pause | D1 |
| Stop | D2 |
| Next | D3 |
| Previous | D4 |
| Volume Up | D5 |
| Volume Down | D8 |
| Mute | D9 |
| Microphone Mute | D10 |
| Unpair/reset hold (action disabled) | D6 |

Indicator LED: `LED_BUILTIN`

---

## Behavior (current firmware)

- BLE HID media remote only (USB HID path temporarily disabled).
- LED blinks while advertising/not connected, solid when BLE connected.
- Unpair/reset button is currently wired as input but firmware action is disabled while stabilizing input/reset behavior.
- Microphone mute key sends a keyboard shortcut (`GUI`+`ALT`+`K`) over BLE.
