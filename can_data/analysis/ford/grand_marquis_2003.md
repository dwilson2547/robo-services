# 2003 Mercury Grand Marquis (planned platform, no CAN captured)

Vehicle facts as stated by the owner, 2026-11-25 (not read from a VIN or the door sticker):
4.6 L V8, 4R70W four-speed automatic.

## First capture, 2026-11-25: no CAN data

One short drive: 367 frames, 366 s. Log: session-1795633021384. The frames hold phone GPS and dongle IMU
and no CAN section at all, so `tools/dbc_check.py` reports "no CAN messages in the given logs" and has nothing to test.

The dongle was working. The phone logged the dongle's 5-second stats (logcat, 14:02:52 to 14:03:12):

```
can 0 (drop 0)  can-mode listen  repeats 0  bus-errors 0    imu ~250 lines per window, 0 dropped
```

- The IMU samples in the log come from the dongle's BNO085 over BLE (`ble/DongleLines.kt`), and 18,350 samples
  arrived with 56 grid points filled, so the dongle was powered and connected for the whole drive.
- It forwarded 0 CAN frames in every window, with 0 bus errors.
- The dongle is fixed at 500 kbit/s. The firmware default is 500000 and it accepts only 125k, 250k, 500k and 1M.
  canstream has no bitrate setting, so no other rate was tried.
- The firmware is CAN-only (TWAI controller); it cannot read J1850.

## What this suggests

⚠ unverified: the best fit is that this car has no CAN on OBD pins 6/14. The owner expects an older platform
without HS-CAN. Pre-2008 Fords commonly use J1850 PWM on OBD pins 2/10, but this car's protocol has not been
confirmed from a source or by measurement.

⚠ unverified: a wrong bitrate on a live bus would normally show as bus errors on a listen-only controller.
That is an expectation from how the controller behaves, not something tested here. Zero errors with zero frames
points at a bus with no activity at the dongle's pins.

Not excluded: a fault in the receive path of this particular dongle (the changelog records one unit whose
transceiver RXD was stuck low). Whether the F-150 drive used the same dongle has not been checked; if it did,
the F-150 log (CAN received at ~113 Hz on `0x201`) already rules this out.

## To confirm or rule out

1. Check the dongle on a CAN car (the F-150 or EcoSport) and note whether its stats show frames arriving.
2. Read the protocol off the car: the OBD-II connector pins that carry wires (pins 2 and 10 for J1850 PWM,
   6 and 14 for CAN) or the factory service data for this model year.

## Consequences

- A longer drive would not help; the current hardware cannot see this car.
- Decoding it needs a J1850 PWM interface and a parser. This car is kept out of the CAN coverage table
  in `README.md` until the protocol is confirmed.

## Related platforms (owner-stated)

- A J1850 PWM device was started earlier, on a Raspberry Pi Pico or another microcontroller, and the electronics
  of a 1999 Taurus were pulled out complete to serve as its test platform. No notes on it were found in the
  workspace markdown (searched for J1850 and Taurus); the only mention is two bookmarked links in
  `docs/Links_and_side_content.md`.
- This Grand Marquis is the only other car on hand that speaks that protocol.
- The owner's 1994 Mustang runs a Holley ECU, so it is a separate decoding problem (Holley's own interface,
  not Ford's); not looked at yet.
