# Seeed XIAO ESP32-S3 / ESP32-C6

Shared board reference for the Seeed XIAO variants currently used in this repo.

---

## Board/FQBN mapping

| Board | FQBN |
|---|---|
| XIAO ESP32-S3 | `esp32:esp32:XIAO_ESP32S3` |
| XIAO ESP32-C6 | `esp32:esp32:XIAO_ESP32C6` |

List checked with:

```bash
./bin/arduino-cli board listall | grep -i xiao
```

---

## XIAO ESP32-S3 (verified in digital dash)

Project: `capture_device_configs/digital_dash`

- Receiver/transmitter sketches compile and flash with `esp32:esp32:XIAO_ESP32S3`.
- ESP-NOW callback signatures must match ESP32 Arduino core v3 APIs.
- Stable dash display path used raw SPI ILI9488 rendering (see `ili9488_xpt2046_4in_spi_tft.md`).
- Dashboard receiver MAC used in testing: `1C:DB:D4:45:0C:80`.

---

## XIAO ESP32-C6 (verified in phone_companion)

Project: `phone_companion/firmware/esp32_can_ble_bridge`

- FQBN confirmed: `esp32:esp32:XIAO_ESP32C6`.
- Serial port appears as `/dev/ttyACM*` (USB-Serial/JTAG, same as S3).
- Flash size: **4 MB** — use `partitions_c6.csv` (two 1.75 MB OTA partitions). The S3 8 MB partition table will cause a boot loop ("partition 3 invalid — exceeds flash chip size").
- CAN/TJA1051 wiring confirmed working: TX=D0 (GPIO0), RX=D1 (GPIO1), S=D2 (GPIO2), LED=GPIO15.

## XIAO ESP32-C6 pin map

Arduino `D` labels to GPIO numbers:

| Arduino label | GPIO | Notes |
|---|---|---|
| D0 | GPIO0 | CAN TX (phone_companion convention) |
| D1 | GPIO1 | CAN RX (phone_companion convention) |
| D2 | GPIO2 | TJA1051 S pin — LOW=listen, HIGH=normal |
| D3 | GPIO21 | |
| D4 | GPIO22 | SDA |
| D5 | GPIO23 | SCL |
| D6 | GPIO16 | TX (UART0) |
| D7 | GPIO17 | RX (UART0) |
| D8 | GPIO19 | |
| D9 | GPIO20 | |
| D10 | GPIO18 | |
| LED | GPIO15 | Built-in LED |

---

## XIAO ESP32-S3 pin map

Arduino `D` labels to GPIO numbers:

| Arduino label | GPIO | Notes |
|---|---|---|
| D0 | GPIO1 | CAN TX (this repo convention) |
| D1 | GPIO2 | CAN RX (this repo convention) |
| D2 | GPIO3 | |
| D3 | GPIO4 | SD CS (can_simulator) |
| D4 | GPIO5 | SDA |
| D5 | GPIO6 | SCL |
| D6 | GPIO43 | TX (UART0) |
| D7 | GPIO44 | RX (UART0) |
| D8 | GPIO7 | SD SCK (can_simulator) |
| D9 | GPIO8 | SD MISO (can_simulator) |
| D10 | GPIO9 | SD MOSI (can_simulator) |

**CAN convention across this repo:** D0 (GPIO1) = CAN TX → transceiver TXD, D1 (GPIO2) = CAN RX ← transceiver RXD.

---

## CAN / TWAI with collin80/esp32_can library

When using the `esp32_can` library (as in ESP32RET), `setCANPins` takes **(rx, tx)** — RX first, TX second. This is **backwards** from `TWAI_GENERAL_CONFIG_DEFAULT` which takes TX first.

```cpp
// esp32_can library — RX first:
CAN0.setCANPins(GPIO_NUM_2, GPIO_NUM_1);  // rx=D1, tx=D0

// Direct TWAI API — TX first:
TWAI_GENERAL_CONFIG_DEFAULT(GPIO_NUM_1, GPIO_NUM_2, TWAI_MODE_NORMAL);  // tx=D0, rx=D1
```

Getting this wrong produces a device that initializes cleanly and reports no errors but receives zero CAN frames. See `docs/hardware/tja1051_breakout.md` for the full gotcha.

---

## USB CDC serial (ARDUINO_USB_CDC_ON_BOOT=1)

The XIAO ESP32-S3 uses native USB CDC (not a UART bridge). Build flags required:

```ini
build_flags =
    -D ARDUINO_USB_MODE=1
    -D ARDUINO_USB_CDC_ON_BOOT=1
```

**Important behaviors:**
- **Boot messages are lost** if no host has opened the port yet. `Serial.print()` calls in `setup()` fire before the USB CDC connection is established and are silently discarded. To capture boot output, either manually press reset after opening the port, or use the repeated-print pattern (see `docs/tooling/serial-monitor.md`).
- **`CORE_DEBUG_LEVEL` must be 0** for any sketch that uses the serial port for binary protocols (e.g. GVRET). The IDF verbose log output (`[V]`, `[D]` prefixed lines) is sent to the same USB CDC port and will corrupt any binary framing. Confirmed: `CORE_DEBUG_LEVEL=5` completely breaks SavvyCAN GVRET reception even when the CAN hardware is working perfectly.
- **DTR toggle sends XIAO into bootloader mode** (unlike UART-bridge boards where DTR only resets). Do not toggle DTR from Python `pyserial` to trigger a reset — use the physical reset button instead, or connect to the port *after* the device has fully booted.

---

## Known gotchas across XIAO bring-up

- `arduino-cli board list` can show generic labels (e.g., "ESP32 Family Device"); do not rely on label alone for board variant selection.
- In this repo, always use `./bin/arduino-cli` from repo root so installed cores/libraries match project expectations.
- Wi-Fi TX power changes may fail silently in some runs/variants; log and verify return status at boot rather than assuming power level was applied.
