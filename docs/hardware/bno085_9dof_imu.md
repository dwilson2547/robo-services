# Adafruit BNO085 9-DOF IMU

**Breakout:** BNO085 (also covers BNO080 / BNO086)
**Fusion:** CEVA/Hillcrest SH-2 firmware on-chip — outputs *fused, calibrated* reports
**Intended use here:** phone_companion sensor-aided signal discovery (Phase 11) — IMU on
the ESP32-C6 dongle, sharing the CAN capture clock.
**Library:** `SparkFun_BNO08x_Cortex_Based_IMU` (class `BNO08x`, depends on `Adafruit_BusIO`)
— this is what's installed in this repo's arduino-cli, **not** `Adafruit_BNO08x`.

> ✅ **Verified on XIAO ESP32-C6** (phone_companion v1.5.0 firmware). The config that
> works is captured below; see also the **C6 serial gotcha** in
> `docs/tooling/arduino-cli.md` (USB serial is silent unless `CDCOnBoot=cdc`).

## Verified config (XIAO ESP32-C6)

| Item | Value |
|---|---|
| I2C address | **`0x4B`** — AD0/SA0 strapped **high** (SparkFun lib default; Adafruit's is 0x4A) |
| SDA / SCL | **D4 (GPIO22) / D5 (GPIO23)** — the C6's native `Wire` bus |
| INT | D3 (GPIO21) — wired, but firmware uses **poll mode** (see below) |
| RST / BOOT | pulled **high** externally (not driven) |
| Bus clock | **100 kHz** (`Wire.setClock(100000)`) — clock-stretch |
| Reports | rotation vector + linear accel + gyro, all @ 50 Hz |

```cpp
#include "SparkFun_BNO08x_Arduino_Library.h"
BNO08x imu;
Wire.begin();                       // C6 default SDA=D4, SCL=D5
Wire.setClock(100000);
imu.begin(0x4B, Wire, -1, -1);      // INT=-1 RST=-1 → poll mode (see gotcha)
imu.enableRotationVector(20);
imu.enableLinearAccelerometer(20);
imu.enableGyro(20);
// loop: for (int i=0;i<8 && imu.getSensorEvent();i++) { switch(imu.getSensorEventID()) ... }
```

### Mount angle (e.g. OBD-II port) — yaw is fine, accel needs calibration

The dongle sits at whatever angle the OBD-II port dictates, so the sensor axes don't line
up with the vehicle. This is **not** a problem for **yaw rate**: project the gyro vector
onto the gravity direction (taken from the fusion quaternion) to get the true
vertical-axis rate, independent of tilt — no calibration step. World-up `[0,0,1]` expressed
in the sensor frame is the third row of the body→world rotation matrix; dot it with the
gyro:

```
ux = 2(qx*qz - qw*qy);  uy = 2(qy*qz + qw*qx);  uz = 1 - 2(qx*qx + qy*qy)
yawRate = gx*ux + gy*uy + gz*uz      // rad/s about true vertical
```

(See `ImuSample.verticalYawRateDegPerSec`.) **Linear acceleration** channels (lateral /
longitudinal) *do* need full orientation — they also need the horizontal heading, so they
require a calibration capture at rest (vehicle-frame rotation).

### Gotcha — retry `begin()`, don't call it once in `setup()`

The BNO085 needs >100 ms after power-on before it answers on I2C. A single
`imu.begin()` in `setup()` can run before the sensor is ready and **fail silently**
(`gImuReady` stays false → no IMU, with no obvious error). Retry from `loop()` at ~1 Hz
until it succeeds:

```cpp
bool tryInitImu() {
  if (gImuReady) return true;
  if (!imu.begin(0x4B, Wire, -1, -1)) return false;   // not up yet — caller retries
  gImuReady = true; enableImuReports(); return true;
}
// loop(): if (!gImuReady && millis()-lastTry > 1000) { lastTry = millis(); tryInitImu(); }
```

This bit phone_companion v1.5.0 — the LED-flicker bring-up probe accidentally hid it
(it already retried every second), so the single-shot `setup()` call looked fine until
the integrated firmware shipped it.

### Gotcha — poll, don't block on INT, when sharing a hot loop

The dongle's loop also services TWAI + NimBLE. Passing the INT pin to `begin()` makes the
library wait on INT inside `getSensorEvent()`, which can stall that loop. Use **poll mode**
(`begin(addr, Wire, -1, -1)`) and drain a bounded number of events per pass. Bring-up was
proven this way (LED flicker probe). The INT line is still wired if a future build wants to
gate I2C reads on `digitalRead(INT)==LOW` without letting the library block.

---

## Why this part (vs. a raw IMU or the phone's IMU)

The SH-2 runs sensor fusion on-chip, so you read *physical, calibrated* quantities
directly instead of filtering raw MEMS data:

| Report (SH-2 sensor ID) | Output | Used for |
|---|---|---|
| Rotation Vector (`0x05`) | absolute orientation quaternion (mag-corrected) | vehicle-frame calibration |
| Game Rotation Vector (`0x08`) | orientation without magnetometer | drift-free heading when mag is noisy |
| Linear Acceleration (`0x04`) | acceleration with **gravity removed** | `LATERAL_ACCEL`, `LONGITUDINAL_ACCEL` |
| Calibrated Gyroscope (`0x02`) | rad/s, bias-corrected | `YAW_RATE` (Z axis) |
| Accelerometer (`0x01`) | raw incl. gravity | tilt / mount detection |

Gravity removal + the rotation vector are the accuracy win: capture the static quaternion
once at rest, rotate every sample into the vehicle frame, and "lateral" actually means
lateral regardless of how the dongle is mounted.

---

## Interface modes — strapping (PS1 / PS0)

| PS1 | PS0 | Mode | Notes |
|---|---|---|---|
| 0 | 0 | **I2C** | default on the Adafruit breakout; addr set by SA0 |
| 0 | 1 | UART | full SHTP over UART |
| 1 | 0 | SPI | fastest; more pins |
| 1 | 1 | **UART-RVC** | fixed 100 Hz binary yaw/pitch/roll + accel, 115200 baud |

### I2C (primary path)

- **7-bit address:** `0x4A` default, `0x4B` if SA0 high
- **Logic level:** 3.3V (breakout is regulated/level-shifted)
- Wire RST and INT; the lib uses them for reset + data-ready.

```cpp
#include <Adafruit_BNO08x.h>
Adafruit_BNO08x bno08x(RESET_PIN);   // -1 if RST not wired

Wire.begin();
Wire.setClock(100000);               // see clock-stretch gotcha
bno08x.begin_I2C(0x4A, &Wire, INT_PIN);
bno08x.enableReport(SH2_LINEAR_ACCELERATION, 10000);  // µs interval → 100 Hz
bno08x.enableReport(SH2_GYROSCOPE_CALIBRATED, 10000);
bno08x.enableReport(SH2_ROTATION_VECTOR, 20000);      // 50 Hz is plenty for orientation
```

#### Gotcha — I2C clock stretching

The BNO08x **stretches the I2C clock** while the SH-2 core assembles a report. Keep the
bus at **100 kHz** (`Wire.setClock(100000)`); 400 kHz tends to drop/corrupt reads on
ESP32. If reads are still flaky (NACKs, stalled `getSensorEvent()`), fall back to
UART-RVC — it sidesteps I2C entirely.

### UART-RVC (fallback)

Strap PS1=1/PS0=1. The chip then emits a fixed **19-byte, 100 Hz, 115200-baud** frame:
`0xAA 0xAA index yaw(2) pitch(2) roll(2) ax(2) ay(2) az(2) reserved(3) csum`
(angles 0.01°, accel in mg). Dead simple to parse on a hardware UART; no SHTP, no
clock-stretch. Trade-off: gives **yaw angle, not gyro rate** — derive yaw rate as
d(yaw)/dt for `YAW_RATE` correlation. Use `Adafruit_BNO08x_RVC` or parse the frame by hand.

---

## Pin budget on XIAO ESP32-C6

CAN (TWAI) uses 2 GPIO + 1 for the TJA1051 S-pin; I2C needs SDA/SCL (+ optional RST/INT).
Comfortably within the XIAO's GPIO count. Power the breakout from 3V3.

---

## Notes for Phase 11 integration

- Stream as `IMU,<ts_ms>,<qw>,<qx>,<qy>,<qz>,<ax>,<ay>,<az>,<gz>` NUS lines on the shared
  dongle `millis()` clock so IMU samples align to CAN frames without cross-clock search.
- 100 Hz × ~40 B ≈ 4 KB/s on top of CAN — fits NUS framed-packing headroom; down-sample to
  50 Hz if it crowds throughput.
- Calibration: BNO08x ships its own dynamic calibration; let it settle (figure-eight for
  mag) before trusting the absolute rotation vector. For vehicle-frame rotation we only
  need a stable *relative* quaternion captured at rest, so Game Rotation Vector is fine.
