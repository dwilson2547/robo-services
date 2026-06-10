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
