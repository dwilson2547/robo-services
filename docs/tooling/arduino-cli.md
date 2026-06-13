# arduino-cli

**Binary location:** `robo-services/bin/arduino-cli`  
Always use this copy — it has the correct board definitions and libraries pre-loaded. Do not use any other copy found under `.tools/` or system paths.

---

## Compile

```bash
bin/arduino-cli compile --fqbn <fqbn> <sketch-dir>/
```

Example:
```bash
bin/arduino-cli compile --fqbn esp32:esp32:esp32thing_plus rtk_base_station/firmware/phase1_raw_logger/
```

The sketch directory must match the `.ino` filename (e.g. `phase1_raw_logger/phase1_raw_logger.ino`).

If a directory contains multiple top-level `.ino` files, `arduino-cli` treats the folder name as the sketch name and compile can fail with:

`Can't open sketch: main file missing from sketch`

Use one sketch per folder (preferred), or compile from a temporary folder containing only the target `.ino` renamed to match folder name.

---

## Upload

```bash
bin/arduino-cli upload --fqbn <fqbn> --port <port> <sketch-dir>/
```

Example:
```bash
bin/arduino-cli upload --fqbn esp32:esp32:esp32thing_plus --port /dev/ttyUSB0 rtk_base_station/firmware/phase1_raw_logger/
```

Compile and upload in one shot:
```bash
bin/arduino-cli compile --fqbn esp32:esp32:esp32thing_plus rtk_base_station/firmware/phase1_raw_logger/ && \
bin/arduino-cli upload  --fqbn esp32:esp32:esp32thing_plus --port /dev/ttyUSB0 rtk_base_station/firmware/phase1_raw_logger/
```

---

## Find the FQBN for a board

List all installed boards and grep for the board name:
```bash
bin/arduino-cli board listall | grep -i "thing plus"
```

To check what board is connected on a port (only works if the board has USB descriptor info):
```bash
bin/arduino-cli board list
```

For XIAO variants in this repo:

```bash
bin/arduino-cli board listall | grep -i xiao
```

### Known FQBNs in this repo

| Board | FQBN |
|-------|------|
| SparkFun ESP32 Thing Plus | `esp32:esp32:esp32thing_plus` |
| SparkFun ESP32 Thing Plus C | `esp32:esp32:esp32thing_plus_c` |

**Do not confuse Thing Plus and Thing Plus C.** They have different Qwiic I2C pin assignments — see `docs/hardware/esp32_thing_plus.md`.

---

## Find the port for a connected device

Plug in the device, then:
```bash
ls /dev/ttyUSB* /dev/ttyACM* 2>/dev/null
```

Or watch `dmesg` while plugging in:
```bash
dmesg | tail -5
```

Typical ports in this repo:
- ESP32 (CH340/CP210x USB-UART): `/dev/ttyUSB0`
- u-blox ZED-F9P (USB direct): `/dev/ttyACM0`

---

## Install a library

```bash
bin/arduino-cli lib install "SparkFun u-blox GNSS v3"
```

List installed libraries:
```bash
bin/arduino-cli lib list
```

---

## Install board support

```bash
bin/arduino-cli core install esp32:esp32
```

List installed cores:
```bash
bin/arduino-cli core list
```

---

## Gotcha — XIAO ESP32-C6 USB serial is silent by default

Reading `Serial.print` output from a C6 over `/dev/ttyACM0` fails out of the box, which
makes headless bring-up debugging look like a dead board when it isn't.

- The board default is **`cdc_on_boot=0`**, so `Serial` is routed to **UART0 (D6/D7)**,
  not USB. Nothing reaches `/dev/ttyACM0` (that port is the USB-Serial/JTAG used for
  flashing). Build with the menu option to put `Serial` on USB:
  ```bash
  bin/arduino-cli compile --fqbn esp32:esp32:XIAO_ESP32C6:CDCOnBoot=cdc .
  bin/arduino-cli upload  --fqbn esp32:esp32:XIAO_ESP32C6:CDCOnBoot=cdc --port /dev/ttyACM0 .
  ```
- Even with CDC enabled, the C6 HWCDC **drops TX when no host is attached** and a raw
  `cat`/`stty -clocal` reader may never see output (boot prints are gone by the time you
  attach; `begin()` failures look like total silence). For automated/headless checks,
  prefer a **free-running heartbeat** in `loop()` and/or an **LED status pattern** over
  relying on USB serial. (BNO085 bring-up was verified via an LED-flicker probe.)
- Production phone_companion firmware ships with `cdc_on_boot=0` (it talks over BLE, not
  USB serial), so its `Serial.print` debug only appears on UART0 — expected, not a bug.
