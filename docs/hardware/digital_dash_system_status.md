# Digital Dash System Status (Receiver + Publisher)

Current ground truth for `capture_device_configs/digital_dash` so the project can be resumed later without re-discovery.

---

## Topology

- **Receiver / screen unit:** Seeed XIAO ESP32-S3 + 4.0" ILI9488 SPI TFT + XPT2046 touch
- **Publisher / data unit:** Seeed XIAO ESP32-S3 running synthetic ESP-NOW telemetry stub
- **Transport:** ESP-NOW, STA mode
- **Known receiver MAC used in this build:** `1C:DB:D4:45:0C:80`
- **Known publisher MAC seen in recent flash sessions:** `1C:DB:D4:45:0C:88`

---

## Firmware files

- Receiver: `capture_device_configs/digital_dash/dash_receiver.ino`
- Publisher: `capture_device_configs/digital_dash/dash_publisher_stub.ino`
- Display smoke test: `capture_device_configs/digital_dash/minimal_display_test.ino`

---

## Receiver (dashboard) current behavior

### Display/touch implementation

- Uses a **raw SPI ILI9488 path** (not TFT_eSPI runtime drawing).
- ILI9488 pixel format is 18-bit (`COLMOD 0x66`), with 3-byte pixel writes.
- Touch is XPT2046 on shared SPI bus with dedicated touch CS.
- Touch transform currently tuned to:
  - `TOUCH_SWAP_XY = 1`
  - `TOUCH_INVERT_X = 0`
  - `TOUCH_INVERT_Y = 0`
  - raw ranges ~`200..3900`

### Dashboard modes (right-side 4-button rail)

- **Slot 0:** SPEED page (combined speed + RPM segmented display, black/white, unit badge KPH/MPH)
- **Slot 1:** RPM page (large RPM digits + shift threshold logic + external LED on D5)
- **Slot 2:** ENGINE page (current layout):
  - Row 1: `OILP`, `OILT`
  - Row 2: `CLT`, `BATT`
  - Row 3: `AFR`, `LOAD`
- **Status button (top-right):** toggles SETTINGS page

### Settings + persistence (NVS / Preferences)

Namespace: `dashcfg`

- `shift_rpm` (`UShort`) — shift threshold (clamped 3000..9000)
- `speed_mph` (`Bool`) — speed unit toggle (KPH/MPH)

Both settings persist across reboot.

### Render cadence / redraw strategy

- Active dashboards render at ~40 ms interval.
- Settings renders at ~100 ms interval.
- Numeric values use selective redraw (digit-diff updates) to avoid full-page repaint jank.
- Settings unit selector (KPH/MPH) is partial redraw only.

---

## Publisher current behavior

- Sends full `TelemetryPacket` at **~10 Hz** (`delay(100)`).
- Uses synthetic but realistic sweep values for:
  - vehicle speed, RPM, throttle, GPS, IMU
  - coolant/oil temps, oil pressure, battery voltage
  - IAT, MAP, lambda, ignition timing, knock retard, fuel rail pressure
  - fan state, STFT, LTFT, gear
- Attempts to set Wi-Fi TX power to `WIFI_POWER_8_5dBm` at boot.
  - There have been runs where this call reported failure in serial logs.

---

## Shared telemetry packet schema (must match exactly)

Both receiver and publisher currently define the same packed `TelemetryPacket` with these fields:

- `seq`, `timestamp_ms`
- `lat_deg7`, `lon_deg7`, `alt_mm`
- `speed_kmh10`, `heading_deg10`, `gps_fix`, `gps_sats`
- `accel_x_mg`, `accel_y_mg`, `accel_z_mg`, `roll_deg10`, `pitch_deg10`
- `rpm`, `throttle_pct10`
- `coolant_c10`, `oil_temp_c10`, `oil_psi10`, `batt_mv`
- `iat_c10`, `map_kpa10`, `lambda1000`, `ign_deg10`, `knock_ret_deg10`, `fuel_rail_kpa10`
- `fan_on`, `stft_pct10`, `ltft_pct10`
- `gear`, `_pad`

If receiver and publisher structs drift, receiver drops packets by length check (`len != sizeof(TelemetryPacket)`), which appears as "no data on dashboard".

---

## Pin map (receiver)

From `dash_receiver.ino`:

- TFT MOSI: D10 (`GPIO 9`)
- TFT MISO: D9 (`GPIO 8`)
- TFT SCLK: D8 (`GPIO 7`)
- TFT CS: D3 (`GPIO 4`)
- TFT DC: D1 (`GPIO 2`)
- TFT RST: D2 (`GPIO 3`)
- Backlight: D0 (`GPIO 1`)
- Touch CS: D4 (`GPIO 5`)
- Shift LED: D5 (`GPIO 6`)

---

## Flash/build workflow notes

- Always use repo tool: `./bin/arduino-cli`
- FQBN used: `esp32:esp32:XIAO_ESP32S3`
- `capture_device_configs/digital_dash/` contains multiple `.ino` files, so direct compile of that folder can fail with sketch-name mismatch.
  - Reliable pattern: copy target sketch into a temporary folder with matching filename before compile/upload.
  - This behavior is also documented in `docs/tooling/arduino-cli.md`.

---

## Common recovery checklist

If dash stops updating:

1. Confirm receiver and publisher are flashed with matching packet schema revisions.
2. Confirm receiver MAC in publisher (`RECEIVER_MAC`) matches actual receiver serial output.
3. Confirm receiver serial shows `ESP-NOW ready, waiting for packets...`.
4. Confirm publisher serial shows `Peer registered — sending telemetry`.
5. If touch behaves mirrored after rewiring, re-check touch transform flags before changing UI logic.
