# u-blox MAX-M10S (breakout)

**Used in:** the XIAO C6 CAN dongle (`xiao_c6_can_dongle_build.md`), dbc-forge firmware 1.8.0+
**Interface here:** UART, UBX protocol only (NMEA turned off)
**Data sheet:** u-blox MAX-M10S, UBX-20035208 R08

Status: **verified** = measured on the bench unit, 2026-11-17; **data sheet** = from UBX-20035208,
not measured; **⚠ unverified** = not checked.

## The breakout

- A MAX-M10S breakout with a u.FL antenna connector (make not recorded). The seller lists a **5 V supply**;
  VCC goes through a few passives into a 5-pin package, probably a regulator for the module's
  3.6 V maximum (data sheet: VCC max 3.6 V). The part is ⚠ unverified.
- Active antenna power: 3.3 V on the bench unit (the user's measurement). The magnetic roof antenna
  is rated 3–5 V.
- **Factory baud on this breakout: 38400**, not the data sheet's 9600. Verified by a baud scan,
  which found NMEA only at 38400. Why it differs is ⚠ unverified; another unit may ship at 9600, so
  send the baud change at both rates (the firmware does).

## Rates and constellations (data sheet, table 2)

| Constellations | Max navigation rate (default mode) |
|---|---|
| GPS + Galileo | **10 Hz** |
| GPS + Galileo + GLONASS | 6 Hz |
| GPS + Galileo + BeiDou B1I (factory default) | **3 Hz** |
| GPS + Galileo + BeiDou B1C | 8 Hz |

For 10 Hz, turn BeiDou (and GLONASS) off. The firmware does this.

Bench, GPS + Galileo, 100 ms rate, automotive dynamic model (verified):
- 50 NAV-PVT per 5 s, 3D fix, 13–17 satellites, hAcc 0.4–0.8 m, 5 PPS edges per 5 s.
- Cold power-up to "configured" in 7.1 s; warm boots in 0.7–1.3 s.

## TIMEPULSE and safeboot

TIMEPULSE (1 PPS by default) is wired inside the module to SAFEBOOT_N through 1 kΩ (data sheet,
pin table note 15). **If the pin is held low at start-up, the receiver boots into safeboot mode.**
So the MCU pin reading PPS must be a plain input: no pull-down, never driven. On the dongle that's
D10 (GPIO18), `INPUT` with a rising-edge interrupt.

## Configuration gotchas (verified)

Configuration uses UBX-CFG-VALSET to the RAM layer only. It is lost on power-off and redone on
every boot, so nothing in the module's flash is changed.

- **Arduino-ESP32 on the C6: `Serial1.updateBaudRate()` broke transmit.** After changing to
  115200 with it, the module acknowledged nothing it was sent, though receiving its output still
  worked. Reopening the port (`Serial1.end()`, then `begin(115200, ...)`) works.
- **The module ignores commands for 110–400 ms after a baud change.** In practice the baud change
  goes out twice, at 38400 and 9600, because the factory baud isn't known for sure. The first
  command at 115200 after that went unanswered: first ACK at 110 ms on one run and 400 ms on
  another. With no wrong-baud copies sent, it acknowledges at once.
  - Fix: wait for UBX-ACK-ACK (class 0x05, id 0x01, acked message 0x06 0x8A) and resend every
    200 ms, up to 10 times. A 4 × 150 ms budget still failed when the module was already at 115200.
- **Boot logs are missed over USB CDC**: the port opens after they're printed. The firmware puts
  the configuration result (`cfg=ok@<ms>` or `noack<step>@<ms>`) on its periodic `[GPS]` stats
  line instead.

## Output used

UBX-NAV-PVT (class 0x01, id 0x07, 92 bytes), once per measurement. Fields the dongle uses:
- `fixType`, and the `gnssFixOK` flag. With no `gnssFixOK` the fix is reported as 0.
- `numSV`
- `lat`, `lon` (1e-7 deg)
- `hMSL` (mm)
- `gSpeed` (mm/s)
- `headMot` (1e-5 deg)
- `hAcc` (mm)
- the UTC date and time, once valid

The dongle's line format is in the sketch header
(`embedded/can-dongle-firmware/esp32_can_ble_bridge`).
