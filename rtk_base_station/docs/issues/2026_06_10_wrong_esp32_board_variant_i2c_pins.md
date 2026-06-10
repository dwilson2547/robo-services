# Wrong ESP32 board variant causes all I2C traffic to go to unconnected GPIO

**Date:** 2026-06-10  
**Component:** `firmware/*/` — all sketches using explicit `SDA_PIN`/`SCL_PIN` definitions  
**Severity:** High — completely prevented any I2C communication with the F9P; caused weeks of apparent debugging with no meaningful signal

---

## Observed symptom

Every I2C attempt to reach the ZED-F9P (address 0x42) returned either `err=2` (NACK) or inconsistent results. `Wire.endTransmission(false)` returned `err=0` (suggesting success) while `Wire.requestFrom()` always returned 0 bytes. The F9P's I2C was confirmed enabled via USB VALGET, yet the GNSS library's `gnss.begin()` failed on every attempt. An address scan over the full 7-bit range showed no devices.

---

## Root cause

### Hardcoded SDA pin 21 instead of 23

All sketches defined `SDA_PIN 21` and called `Wire.begin(SDA_PIN, SCL_PIN)`. The SparkFun ESP32 Thing Plus board (FQBN `esp32:esp32:esp32thing_plus`) routes the Qwiic connector to **GPIO 23 (SDA) and GPIO 22 (SCL)**, as defined in the board variant file:

```
/home/daniel/.arduino15/packages/esp32/hardware/esp32/3.3.8/variants/esp32thing_plus/pins_arduino.h
static const uint8_t SDA = 23;
static const uint8_t SCL = 22;
```

GPIO 21 is an unconnected pin on this board. All I2C transactions were occurring on a floating, unloaded bus. The F9P on GPIO 23 never saw any traffic.

### Wrong FQBN used initially

Sketches were initially compiled and flashed with `esp32:esp32:esp32thing_plus_c` (Thing Plus **C**), which has SDA on GPIO 21. While the binary ran (same ESP32 chip), the pin definitions baked into the sketch matched the C variant, not the standard Thing Plus. The user's board was confirmed as the standard Thing Plus (DEV-15663), not the USB-C variant (DEV-20168).

### `endTransmission(false)` ESP32 quirk masked the problem

On ESP32, `Wire.endTransmission(false)` (repeated-start, no STOP) returns `0` even when no device is present at the address. This masked the NACK that would normally confirm the device is absent, making it appear as though writes were succeeding when nothing was connected.

---

## Troubleshooting steps taken

1. **Checked Qwiic cable continuity and orientation** — ruled out as a cause; cable was straight-through and correctly oriented.

2. **Verified F9P I2C config via USB** — used Python/pyserial on `/dev/ttyACM0` to send VALGET for `CFG-I2C-ENABLED`, `CFG-I2CINPROT-UBX`, `CFG-I2COUTPROT-UBX`; all returned `1` in all layers. Ruled out F9P misconfiguration.

3. **Added `enableDebugging(Serial)` to GNSS library** — revealed no serial output at all from sketch using `SFE_UBLOX_GNSS gnss` as a global; tracked down to static initialisation crash (separate issue). Did not surface the pin problem.

4. **Ran Wire-only diagnostic sketch** — removed GNSS library, called `Wire.beginTransmission(0x42)` + `Wire.write(0xFD)` + `endTransmission(false)` directly; returned `err=0`, confirming the ESP32 quirk rather than a real ACK.

5. **Ran full address scan** — `Wire.beginTransmission(addr)` + `endTransmission(true)` for all 7-bit addresses; found `ACK at 0x21`, not at `0x42`. This made the pin/variant discrepancy clear.

6. **Checked variant file for Thing Plus** — confirmed SDA=23, SCL=22, vs. SDA=21 hardcoded in all sketches. Root cause identified.

---

## Fix

### All sketch files — remove hardcoded pins, use `Wire.begin()` without arguments

Replaced all `Wire.begin(SDA_PIN, SCL_PIN)` calls with `Wire.begin()`. The board variant file supplies the correct pin numbers when the right FQBN is used, so no hardcoding is necessary or desirable.

```cpp
// Before
#define SDA_PIN 21
#define SCL_PIN 22
Wire.begin(SDA_PIN, SCL_PIN);

// After
Wire.begin();  // uses SDA=23, SCL=22 from esp32thing_plus variant
```

The `busRecovery()` function in diagnostic sketches was also updated to use the board-defined `SDA` and `SCL` constants instead of literals.

### All sketch files — correct FQBN

All compile and upload commands updated from `esp32:esp32:esp32thing_plus_c` to `esp32:esp32:esp32thing_plus`.

---

## Files changed

- `firmware/i2c_scan/i2c_scan.ino` — removed `SDA_PIN`/`SCL_PIN` defines, updated `Wire.begin()`, corrected FQBN in comments
- `firmware/f9p_verify/f9p_verify.ino` — corrected FQBN comment
- `firmware/phase1_raw_logger/phase1_raw_logger.ino` — corrected FQBN comment
