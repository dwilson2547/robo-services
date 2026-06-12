# ESP32RET on Seeed XIAO ESP32-S3

**Firmware:** [collin80/ESP32RET](https://github.com/collin80/ESP32RET)  
**Role:** CAN bus sniffer / GVRET device for SavvyCAN  
**Hardware:** Seeed XIAO ESP32-S3 + TJA1051T CAN transceiver  
**Project path:** `robo-services/savvycan_companion/ESP32RET/`

---

## What it does

ESP32RET implements the GVRET protocol, which SavvyCAN uses to stream raw CAN frames in real time. It supports WiFi (TCP port 23) and USB serial connections. This is the capture device for building DBC files — raw frames in, decode in SavvyCAN.

---

## Wiring (XIAO ESP32-S3 + TJA1051T)

| XIAO pin | GPIO | TJA1051T pin | Notes |
|---|---|---|---|
| D0 | GPIO1 | TXD | CAN TX from MCU |
| D1 | GPIO2 | RXD | CAN RX to MCU |
| 5V | — | VCC | TJA1051T requires 5V — not 3.3V |
| 3.3V | — | VIO | Logic level reference |
| GND | — | GND | |
| — | — | STB/S | Tie to GND (normal mode) |

CANH/CANL to the OBD-II port: pin 6 = CANH, pin 14 = CANL. **Do not add a termination resistor** — the vehicle bus is already terminated.

---

## Patches applied to upstream firmware

The upstream firmware does not support the XIAO ESP32-S3. The following changes were made to `savvycan_companion/ESP32RET/`:

### 1. `platformio.ini` — new `[env:stable-s3]`

```ini
[env:stable-s3]
platform = https://github.com/pioarduino/platform-espressif32/releases/download/55.03.34/platform-espressif32.zip
framework = arduino
board_build.partitions = app_s3.csv
board = seeed_xiao_esp32s3
build_flags = -DCORE_DEBUG_LEVEL=0 -D ARDUINO_USB_MODE=1 -D ARDUINO_USB_CDC_ON_BOOT=1 -D XIAO_ESP32S3
lib_deps =
    https://github.com/collin80/can_common.git
    https://github.com/collin80/esp32_can.git
    https://github.com/collin80/esp32_mcp2517fd.git
    fastled/FastLED
```

**`CORE_DEBUG_LEVEL=0` is mandatory.** Level 5 floods the USB CDC port with IDF log messages, which corrupts the binary GVRET protocol and causes SavvyCAN to receive nothing even with a fully working hardware setup.

### 2. `src/ESP32RET.cpp` — system type 4 (XIAO)

Added `#ifdef XIAO_ESP32S3` to default to system type 4, and added the full type 4 init block:

```cpp
// Default system type selection
uint8_t defaultVal = (espChipRevision > 2) ? 0 : 1;
#ifdef XIAO_ESP32S3
    defaultVal = 4;
#elif defined(CONFIG_IDF_TARGET_ESP32S3)
    defaultVal = 3;
#endif
settings.systemType = nvPrefs.getUChar("systype", defaultVal);

// System type 4 init block (further down in setup()):
case 4:  // XIAO ESP32S3
    CAN0.setCANPins(GPIO_NUM_2, GPIO_NUM_1);  // rx=D1(GPIO2), tx=D0(GPIO1)
    SysSettings.numBuses = 1;
    SysSettings.lawicelMode = false;
    SysSettings.isWifiActive = false;  // or true depending on desired mode
```

**`setCANPins` takes (rx, tx) — RX first.** See `docs/hardware/tja1051_breakout.md` for the full explanation of why getting this backwards produces silent failure.

### 3. `src/can_manager.cpp` — remove double TWAI init

The upstream code called `setListenOnlyMode(false)` unconditionally for non-listen-only buses:

```cpp
// REMOVED — caused disable()+enable() cycle, leaving orphaned FreeRTOS task:
if (settings.canSettings[i].listenOnly)
    canBuses[i]->setListenOnlyMode(true);
else
    canBuses[i]->setListenOnlyMode(false);  // ← this line removed

// NOW — only set mode when actually needed:
if (settings.canSettings[i].listenOnly)
    canBuses[i]->setListenOnlyMode(true);
```

`setListenOnlyMode()` calls `disable()` + `enable()` internally. Calling it on every boot created an orphaned `task_LowLevelRX` FreeRTOS task with a NULL handle that competed with the properly-tracked task. Symptom: SavvyCAN shows "1 bus connected" then immediately disconnects.

---

## Flashing

```bash
cd savvycan_companion/ESP32RET
/home/daniel/miniconda3/bin/python3 -m platformio run -e stable-s3 --target upload --upload-port /dev/ttyACM0
```

Disconnect SavvyCAN before flashing — it holds the port open and upload will fail.

---

## NVS wipe

After a firmware change that alters system type defaults, wipe NVS so stale config doesn't override the new defaults:

```bash
~/.platformio/penv/bin/esptool --port /dev/ttyACM0 erase-region 0x9000 0x5000
```

Then reflash. The `app_s3.csv` partition table places NVS at `0x9000`, 20K (`0x5000` bytes).

---

## Connecting with SavvyCAN

**WiFi:**  
AP SSID and password are in the ESP32RET source (`config.h` or printed on serial at boot). Connect your laptop to the AP, then in SavvyCAN: **Connection → Add New Device Connection → GVRET**, host `192.168.4.1`, port `23`.

**USB serial:**  
SavvyCAN → **Connection → Add New Device Connection → GVRET**, select the serial port (`/dev/ttyACM0`). USB serial is useful for debugging because the laptop stays on its normal network.

---

## Known gotchas

- **`CORE_DEBUG_LEVEL` must be 0.** Any value above 0 corrupts GVRET over USB CDC. No frames will appear in SavvyCAN. The device will appear connected but silent.
- **`setCANPins(rx, tx)` — RX is first.** See `tja1051_breakout.md`. This cost two days of debugging.
- **Stale NVS overrides compiled defaults.** After reflashing with a different system type, the old system type stored in NVS takes precedence. Always wipe NVS when changing system type.
- **SavvyCAN must be disconnected before flashing.** It holds `/dev/ttyACM0` exclusively. Upload will fail with "port busy".
- **The `esp32_can` library creates two `task_LowLevelRX` tasks** during `init()`. One is tracked (from `enable()`), one is orphaned (created again in `init()` with a NULL handle). This is an upstream library bug. Both tasks read the same TWAI queue and call `processFrame()`, so frames still get through — but be aware if debugging task counts.
- **Mixed TWAI v1/v2 API in `esp32_can`.** `enable()` uses `twai_driver_install_v2` / `twai_start_v2` (IDF 5.2+ v2 API) while `disable()` uses the legacy `twai_get_status_info` / `twai_stop` / `twai_driver_uninstall` (v1 API). On IDF 5.x these are compatible wrappers for the single-controller case, but may cause issues if the library is updated or the MCU is changed.
