# PlatformIO

Build system used for ESP32 projects that need a specific IDF/Arduino-ESP32 version or library combination that `arduino-cli` can't easily pin.

---

## Installation

PlatformIO is installed in the system Python (miniconda3 base, Python 3.13):

```bash
/home/daniel/miniconda3/bin/python3 -m pip install platformio
```

**Python version constraint:** PlatformIO 6.1.x requires Python **3.10–3.13**. Python 3.14+ is not supported and the install will fail or behave incorrectly. The active conda env at time of writing is Python 3.14 — always use `/home/daniel/miniconda3/bin/python3` (base env, 3.13), not the default `python3`.

---

## Build

```bash
/home/daniel/miniconda3/bin/python3 -m platformio run -e <env>
```

Example:
```bash
cd savvycan_companion/ESP32RET
/home/daniel/miniconda3/bin/python3 -m platformio run -e stable-s3
```

---

## Flash

```bash
/home/daniel/miniconda3/bin/python3 -m platformio run -e <env> --target upload --upload-port /dev/ttyACM0
```

---

## Serial monitor

```bash
/home/daniel/miniconda3/bin/python3 -m platformio device monitor --port /dev/ttyACM0 --baud 115200
```

Or use the pyserial patterns in `docs/tooling/serial-monitor.md` — they give more control.

---

## pioarduino platform for ESP32S3 projects

Projects targeting the XIAO ESP32-S3 use the `pioarduino` platform fork rather than the official `espressif32` platform. This provides a stable, recent IDF 5.5.1 / Arduino-ESP32 3.3.4 toolchain:

```ini
platform = https://github.com/pioarduino/platform-espressif32/releases/download/55.03.34/platform-espressif32.zip
```

The version number `55.03.34` maps to Arduino-ESP32 3.3.4. This is the version used across all XIAO ESP32-S3 projects in this repo.

---

## esptool (for NVS wipe and partition operations)

PlatformIO bundles esptool in its virtualenv:

```bash
~/.platformio/penv/bin/esptool --port /dev/ttyACM0 <command>
```

**Do not use** `python3 -m esptool` — esptool is not installed in the system/conda Python environments.

### Wipe NVS partition

```bash
~/.platformio/penv/bin/esptool --port /dev/ttyACM0 erase-region 0x9000 0x5000
```

This erases 20K starting at `0x9000`, which is the NVS partition in the `app_s3.csv` partition table used by ESP32RET. Use this when stale NVS settings are overriding firmware defaults (e.g. after changing system type).

Partition layout for `app_s3.csv`:
| Name | Offset | Size |
|---|---|---|
| nvs | 0x9000 | 20K |
| otadata | 0xE000 | 8K |
| app0 | 0x10000 | 3072K |
| app1 | 0x310000 | 3072K |
| eeprom | 0x610000 | 4K |
| spiffs | 0x620000 | 1468K |
