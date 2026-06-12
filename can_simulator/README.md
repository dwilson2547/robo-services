# CAN Simulator

Bench CAN bus simulator for replaying vehicle CAN traffic without being at the car. Reads SavvyCAN CSV log files from an SD card and replays them over a live CAN bus at accurate timing.

**Hardware:** Seeed XIAO ESP32-S3 + TJA1051T CAN transceiver + SPI SD card module  
**Firmware:** `firmware/xiao_can_simulator/`  
**Captured from:** 2008 Chevy Impala HS-CAN via ESP32RET sniffer (see `savvycan_companion/`)

---

## Wiring

### CAN (TJA1051T)

| XIAO pin | GPIO | TJA1051T pin | Notes |
|---|---|---|---|
| D0 | GPIO1 | TXD | CAN TX from MCU |
| D1 | GPIO2 | RXD | CAN RX to MCU |
| 5V | — | VCC | Must be 5V |
| 3.3V | — | VIO | Logic level reference |
| GND | — | GND | |
| — | — | STB/S | Tie to GND |

Add a **120Ω termination resistor** across CANH/CANL on this end of the bench bus. The other end (the device under test) should provide the second terminator.

### SD Card (SPI)

| XIAO pin | GPIO | SD pin |
|---|---|---|
| D8 | GPIO7 | SCK |
| D9 | GPIO8 | MISO |
| D10 | GPIO9 | MOSI |
| D3 | GPIO4 | CS |
| 3.3V | — | VCC |
| GND | — | GND |

---

## SD card setup

Format the SD card as FAT32. Drop any SavvyCAN CSV exports into the root directory. The firmware auto-plays the first `.csv` it finds at boot.

SavvyCAN CSV format (what the firmware expects):
```
Time Stamp,ID,Extended,Dir,Bus,LEN,D1,D2,D3,D4,D5,D6,D7,D8
0.000000,0x0C1,false,Rx,0,8,00,01,00,04,00,01,00,04
...
```

Export from SavvyCAN: **File → Save Captured Frames**.

---

## Serial console

Connect at 115200 baud. Commands:

| Command | Action |
|---|---|
| `ls` | List CSV files on SD |
| `play <file>` | Start playback (e.g. `play impala_idle.csv`) |
| `stop` | Stop playback |
| `loop` | Toggle loop mode (default: on) |
| `speed <n>` | Playback speed multiplier (e.g. `speed 0.5`, `speed 2`) |
| `maxgap <ms>` | Cap inter-frame gaps in ms (default 1000); prevents long pauses or corrupt timestamps from stalling replay |
| `status` | Show current file, loop, speed, playing state, and TWAI error counters |
| `help` | Show command list |

---

## Flashing

```bash
cd can_simulator/firmware/xiao_can_simulator
/home/daniel/miniconda3/bin/python3 -m platformio run -e xiao_s3 --target upload --upload-port /dev/ttyACM0
```

See `docs/tooling/platformio.md` for PlatformIO setup (Python version requirement, esptool path).

---

## How it works

- TWAI initialized in **NO_ACK mode** at 500 kbps — does not require an ACK from any listener. Correct for bench use with a listen-only dongle (e.g. the phone companion). Bus-off recovery runs automatically if the error counter trips.
- Timing replay uses `esp_timer_get_time()` (microsecond resolution). For gaps >5 ms the loop yields every 1 ms so the serial console stays responsive; for the final ≤5 ms it busy-waits for accuracy.
- Inter-frame gaps are clamped to `[0, maxGap]` (default 1 s). This handles corrupt timestamps (large positive or negative jumps) that would otherwise stall or blast replay. A `[gap]` message is printed when capping occurs.
- SavvyCAN CSV timestamps are raw `micros()` values in microseconds — large integers, not floating-point seconds.
- Both `Rx` and `Tx` rows from the CSV are replayed — this reproduces the full bidirectional bus traffic, not just one side.
- Loop mode restarts the file from the top automatically; `speed 2` halves all inter-frame delays.

---

## Roadmap

- Android companion app (select/manage log files over BLE)
- Multi-bus replay (when second CAN channel hardware is added)
- Live injection mode (Android sends a frame on-demand during replay)
