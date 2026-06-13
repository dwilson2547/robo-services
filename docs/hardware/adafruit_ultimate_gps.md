# Adafruit Ultimate GPS (USB variant)

**Chipset:** MTK3339 (66-channel, 10 Hz max)
**Unit on hand:** Adafruit Ultimate GPS **USB** — exposes only `5V / GND / D+ / D-`
**Intended use here:** general GNSS reference. _Note:_ phone_companion's signal-discovery
correlation (Phase 11) went **phone-GNSS-only** — this USB unit is **not** used there (no
desire to plug a module into the phone). Doc retained as repo-wide hardware reference.

> ⚠️ **Not yet bench-verified in this repo.** Planning/reference entry. The USB-serial
> bridge chip (VID:PID) is **unconfirmed** — verify with `lsusb` before relying on it.

---

## Why no F9P for this

Signal correlation matches against **velocity**, not position. GNSS Doppler-derived
speed-over-ground is accurate to ~±0.1 m/s even on the MTK3339; RTK improves *position*
by orders of magnitude but barely moves *velocity* accuracy. So a ZED-F9P is **not worth
buying for this** — keep the F9P on the RTK base station (`zed_f9p.md`) where its position
accuracy is actually used. The MTK3339's 10 Hz update is the real advantage here (more
correlation samples, finer lag alignment).

---

## The USB-variant catch — it needs a USB *host*

The board's UART runs through an onboard USB-serial bridge; only the USB lines are
broken out. It therefore needs a USB **host** to read.

- **ESP32-C6 cannot host it.** The C6 USB peripheral is a fixed-function Serial/JTAG
  *device* — no OTG host mode. The USB GPS cannot hang off the dongle. (An ESP32-S3 has
  full USB-OTG and could host one, but phone_companion is C6-only.)
- **The phone can host it** via USB-OTG + [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android),
  reading NMEA directly. This is the "Option B" 10 Hz path.

### Prerequisite — verify the bridge chip

`usb-serial-for-android` supports CDC-ACM, CP210x, FTDI, CH34x, PL2303. Confirm this unit
matches before committing:

```bash
lsusb            # note the VID:PID for the GPS
# cross-check the VID:PID against usb-serial-for-android's supported device list
```

If the bridge isn't supported, fall back to the phone's internal `FusedLocationProvider`
(Option A — ~1 Hz Doppler speed, zero hardware).

---

## NMEA / configuration (MTK3339)

- Default **9600 baud**, NMEA sentences. Speed/course come from **RMC** and **VTG**
  (VTG = speed-over-ground in km/h + true course); GGA carries fix quality + HDOP.
- Bump rate/baud with PMTK commands (send over the serial link once opened):

| PMTK command | Effect |
|---|---|
| `$PMTK220,100*2F` | 10 Hz position update |
| `$PMTK220,200*2C` | 5 Hz |
| `$PMTK251,57600*2C` | set 57600 baud (needed to sustain 10 Hz) |
| `$PMTK314,...` | select which NMEA sentences are emitted |

At 10 Hz you **must** raise the baud (9600 can't carry full NMEA at 10 Hz) and ideally
trim sentences to RMC+VTG+GGA.

---

## Notes for Phase 11 integration

- GPS lives in the **phone** clock domain (whether internal GNSS or this USB unit), so its
  samples align to the dongle's CAN clock via **lag-search cross-correlation**, not a
  shared clock. Speed is a slow, smooth signal that tolerates this well.
- Record as `GPS,<ts_ms>,<speed_mps>,<course_deg>,<hdop>,<fix>` rows in the session log.
- Start the correlation engine on the speed channel only — it's orientation-immune and
  the cleanest reference, so it validates the whole pipeline before IMU rotation is added.
