# 2026-06-12 — ESP32-S3 media remote reset on button press

## Summary
The XIAO ESP32-S3 media remote firmware was resetting when any button was pressed.

## Impact
Button input was unusable because every press immediately restarted the device.

## Root cause
USB HID reports were sent unconditionally on button press, even when the USB HID interface was not ready.

## Resolution
Guarded USB media and keyboard report sends behind `usbHid.ready()` checks in:

- `capture_device_configs/media_remote/esp32_s3_media_remote/esp32_s3_media_remote.ino`

Then reflashed the board with the updated firmware.

## Prevention
When using ESP32 Arduino USB HID classes (`USBHIDKeyboard`, `USBHIDConsumerControl`, etc.), check HID readiness before sending reports, especially for dual-mode firmware that can run both BLE and USB paths.
