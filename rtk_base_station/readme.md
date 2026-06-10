# RTK Base Station — Claude Code Handoff

## Project Goal

Build a permanent rooftop RTK GNSS base station that serves centimeter-accurate RTCM3 corrections to rovers on the local network via NTRIP. The final output is a homelab-hosted correction source for 3D scanning, vehicle SLAM, and robotics projects.

---

## Hardware

| Component | Part | Notes |
|-----------|------|-------|
| GNSS Receiver | u-blox ZED-F9P (SparkFun breakout) | L1/L2 dual-band RTK |
| Microcontroller | SparkFun ESP32 | Connected to F9P via **Qwiic (I2C)** — NOT UART |
| Antenna | SPK6618H | Mounted on dish pole, rooftop |
| Power | PoE splitter | Weatherproof enclosure, roof-mounted |
| Backup | 1200mAh LiPo | Sufficient for short outages |

**Critical:** The F9P ↔ ESP32 link is **I2C over Qwiic**, not UART. Any Arduino sketch must use the SparkFun u-blox GNSS v3 library (I2C mode). A UART-based sketch will not work.

---

## Current Project State

The project is in **Phase 1 (raw observation logging)**. The 24-hour capture is actively running as of 2026-06-10. The ESP32 is streaming raw UBX data to the Python receiver on devnode (192.168.0.55). See `roadmap.md` for full phase status.

### Phase 1 — Raw Observation Logging (current phase)
Collect a 24-hour raw UBX observation file, convert to RINEX, submit to NGS OPUS to get precise base coordinates.

### Phase 2 — Permanent NTRIP Caster (final phase)
Flash NTRIP caster firmware, enter OPUS-derived fixed coordinates into F9P TMODE3, and leave running permanently.

---

## F9P Configuration (already applied via u-center)

All settings saved to BBR + Flash. Verify these survived if firmware was updated.

**UBX-CFG-NAV5**
- Dynamic Platform Model: `Stationary (2)`

**UBX-CFG-RATE**
- Measurement Period: `1000ms (1 Hz)`

**UBX-CFG-GNSS** — All four constellations enabled, L1+L2:
- GPS (L1C/A + L2C)
- GLONASS (L1 + L2)
- Galileo (E1 + E5b)
- BeiDou (B1I + B2I)

**UBX-CFG-MSG** — RTCM3 output on I2C port:

| Hex ID | Message | Description | Rate |
|--------|---------|-------------|------|
| F5-05 | 1005 | Stationary ARP | 1 |
| F5-4D | 1077 | GPS MSM7 | 1 |
| F5-57 | 1087 | GLONASS MSM7 | 1 |
| F5-61 | 1097 | Galileo MSM7 | 1 |
| F5-7F | 1127 | BeiDou MSM7 | 1 |
| F5-E6 | 1230 | GLONASS code-phase biases | 5 |

MSM7 chosen over MSM4 for full-resolution carrier phase, Doppler, and signal strength.

**UBX-CFG-TMODE3**
- Currently: `Mode 0 — Disabled` (correct for Phase 1 logging)
- After OPUS: change to `Mode 2 — Fixed Position` with OPUS-derived coordinates

**UBX-CFG-PRT / I2C output**
- Protocol Out: UBX only (NMEA disabled on I2C)

---

## Phase 1: ESP32 Raw Logger Sketch

### What it does
- Connects to WiFi
- Programmatically enables RXM-RAWX and RXM-SFRBX on the F9P via I2C (SparkFun GNSS v3 library file buffer feature)
- Acts as TCP **client**, pushing raw UBX bytes to a Python receiver on the homelab at a hardcoded IP:port (5555)
- Prints periodic status to Serial (bytes sent, connection state)

### Arduino dependencies
- Board: `esp32` by Espressif (install via Boards Manager)
- Library: `SparkFun u-blox GNSS v3` (install via Library Manager)

### Configuration variables to set before flashing
```cpp
const char* ssid       = "YOUR_WIFI_SSID";
const char* password   = "YOUR_WIFI_PASSWORD";
const char* serverIP   = "HOMELAB_IP";   // machine running the Python receiver
const int   serverPort = 5555;
```

### Expected Serial output
On boot should print:
```
F9P connected
RXM-RAWX enabled
RXM-SFRBX enabled
WiFi connected: 192.168.x.x
TCP connected to 192.168.x.x:5555
Streaming... [bytes_sent] bytes
```

If it prints `F9P not detected` — check I2C wiring (SDA/SCL on Qwiic) and confirm F9P is powered.

---

## Phase 1: Python Receiver Script (homelab side)

Runs on any homelab machine (OptiPlex). Listens on TCP port 5555, writes a timestamped `.ubx` file, prints progress every 60 seconds, and on exit prints the `convbin` command and OPUS submission link.

### Run it
```bash
python3 ubx_receiver.py
```

### Expected output
```
Listening on 0.0.0.0:5555...
Client connected from 192.168.x.x
[00:05:00] 1,842,176 bytes received (1.76 MB)
[00:10:00] 3,684,352 bytes received (3.51 MB)
...
Session complete. File: raw_obs_20260610_120000.ubx
Convert with: convbin raw_obs_20260610_120000.ubx -o observation.obs -r ubx
Submit to OPUS: https://www.ngs.noaa.gov/OPUS/
```

---

## Phase 1 → Phase 2 Transition Workflow

Once 24-hour log is collected:

1. **Convert UBX → RINEX:**
   ```bash
   convbin raw_obs_YYYYMMDD_HHMMSS.ubx -o observation.obs -r ubx
   ```
   `convbin` is part of RTKLIB. Install on Linux: build from source or grab a binary from `rtklib/RTKLIB` on GitHub.

2. **Submit to OPUS:**
   - URL: https://www.ngs.noaa.gov/OPUS/
   - Upload the `.obs` file
   - Provide your approximate position and antenna height
   - OPUS emails back precise ECEF X/Y/Z and LLA coordinates
   - Warren, MI has excellent SE Michigan CORS coverage — solution should be solid

3. **Enter fixed coordinates in u-center:**
   - Connect F9P via USB
   - Open `UBX-CFG-TMODE3`
   - Set Mode to `2 — Fixed Position`
   - Enter OPUS coordinates (ECEF or LLA)
   - Save to BBR + Flash via `UBX-CFG-CFG`

4. **Flash NTRIP caster firmware** (Phase 2 sketch — see below)

---

## Phase 2: NTRIP Caster Architecture

The ESP32 acts as a self-hosted NTRIP **caster** — rovers connect directly to it on the LAN. No dependency on RTK2Go or any external service.

```
[ZED-F9P] --I2C/Qwiic--> [ESP32] --WiFi/TCP--> [Rover NTRIP client]
                           :2101/BASE1
```

- Rover NTRIP client points to: `http://<esp32-static-ip>:2101/BASE1`
- The ESP32 reads RTCM3 from F9P over I2C, handles the NTRIP HTTP handshake, streams bytes to connected clients
- F9P is the brain (all GNSS math); ESP32 is purely a network interface
- Assign a static IP to the ESP32 on the MikroTik DHCP server

### Note on SparkFun tutorial
The SparkFun DIY GNSS tutorial (https://learn.sparkfun.com/tutorials/how-to-build-a-diy-gnss-reference-station/esp32-setup-option-2) implements an **NTRIP Server** (pushes to RTK2Go), not a **caster**. Do not use that sketch for the self-hosted approach — it routes correction data out to the internet and back, which is unnecessary and adds an external dependency.

---

## What Still Needs to Be Done

- [x] Confirm u-center config (TMODE3 Disabled, RTCM messages on I2C, Stationary model) — all 20 checks pass
- [x] Set WiFi credentials and homelab IP in the logger sketch
- [x] Flash Phase 1 logger sketch to ESP32
- [x] Start Python receiver on devnode (192.168.0.55), verify data flowing
- [ ] Let run 24 hours — **in progress**
- [ ] Run convbin, submit to OPUS
- [ ] NOTE: F9P firmware is HPG 1.13; latest is HPG 1.32. Update before Phase 3 install if desired.
- [ ] Update TMODE3 to Fixed Position with OPUS coordinates
- [ ] Write / flash Phase 2 NTRIP caster sketch
- [ ] Assign static IP to ESP32 on MikroTik
- [ ] Test rover connection

---

## Repo / File Notes

- The Phase 1 `.ino` sketch and Python receiver were written in a previous Claude chat session. If they need to be regenerated, all relevant context is in this document.
- Reference config doc: `f9p_base_station_config.md` (generated previously, covers all u-center settings in table form)

---

## Key Decisions Made (don't re-debate these)

- **I2C not UART** — SparkFun GNSS v3 library, file buffer capture mode
- **Self-hosted NTRIP caster** over RTK2Go — avoids internet dependency, keeps corrections on LAN
- **OPUS over survey-in** — PPP post-processing gives significantly more accurate base coordinates than survey-in averaging; Warren area has excellent CORS coverage
- **MSM7 over MSM4** — full-resolution carrier phase data, worth the slightly larger RTCM messages
- **TCP push from ESP32** — ESP32 is the TCP client pushing to homelab, not the server; avoids NAT/firewall issues reaching a roof-mounted device
