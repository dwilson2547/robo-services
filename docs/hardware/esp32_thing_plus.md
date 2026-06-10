# SparkFun ESP32 Thing Plus

**Product:** SparkFun ESP32 Thing Plus (DEV-15663)  
**MCU:** ESP32-WROOM-32  
**FQBN:** `esp32:esp32:esp32thing_plus`

---

## Critical: do not confuse with Thing Plus C

The **Thing Plus C** (DEV-20168) uses the ESP32-WROOM-32**C** module and has different Qwiic pin assignments. Using the wrong FQBN bakes the wrong pin numbers into the binary.

| Board | FQBN | Qwiic SDA | Qwiic SCL |
|-------|------|-----------|-----------|
| Thing Plus | `esp32:esp32:esp32thing_plus` | GPIO **23** | GPIO **22** |
| Thing Plus C | `esp32:esp32:esp32thing_plus_c` | GPIO **21** | GPIO **22** |

This difference caused weeks of failed I2C debugging in the RTK base station project (all traffic was going to an unconnected GPIO). See `rtk_base_station/docs/issues/2026_06_10_wrong_esp32_board_variant_i2c_pins.md`.

---

## I2C (Qwiic)

- **SDA:** GPIO 23
- **SCL:** GPIO 22
- **Logic level:** 3.3V
- **Default address for Wire.begin():** no arguments needed — the board variant sets SDA/SCL correctly

Always call `Wire.begin()` with no arguments. Never hardcode `Wire.begin(21, 22)` or similar — use the board variant constants:

```cpp
Wire.begin();  // correct: uses SDA=23, SCL=22 from esp32thing_plus variant
```

If you need the pin numbers explicitly (e.g. for bit-bang bus recovery), use the variant constants `SDA` and `SCL` rather than literals so the code stays portable.

---

## USB / Serial

- **USB chip:** CP2102 (USB-to-UART bridge)
- **Port:** `/dev/ttyUSB0` (when only one CP210x device is connected)
- **Baud:** 115200 in all sketches in this repo
- **Reset via RTS:** `arduino-cli upload` triggers reset automatically; DTR toggle also works from Python

---

## Power

- **Logic level:** 3.3V
- **Operating voltage:** 3.3V (regulated onboard from USB 5V or LiPo)
- **LiPo connector:** JST 2-pin; onboard MCP73831 charger
- **3.3V max draw from 3V3 pin:** ~600mA

---

## Flash / RAM

- **Flash:** 16MB (partition scheme default: 6.5MB app)
- **RAM:** 320KB SRAM
- **Compile output reference:** `Sketch uses X bytes (Y%) of 6553600`

---

## Known gotchas

- **`Wire.begin()` hangs if SDA is held LOW at init time.** The ESP32 I2C peripheral can deadlock if a downstream device is holding SDA when Wire initialises. Always drain any pending device output before calling `Wire.begin()`, and/or run a 9-clock bus recovery sequence first. See `rtk_base_station/docs/issues/2026_06_10_f9p_i2c_bus_lockup_rawx_buffer.md`.

- **`Wire.endTransmission(false)` returns 0 even with no device.** On ESP32, a repeated-start write transaction (no STOP) returns 0 regardless of whether the device ACK'd. Use `endTransmission(true)` (with STOP) to get a reliable NACK. Do not rely on `endTransmission(false)` for device-present checks.
