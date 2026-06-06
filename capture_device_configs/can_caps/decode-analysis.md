# CAN Decode Analysis — 2008 Chevrolet Impala

**Bus:** HS-CAN (OBD-II pins 6/14) · 500 kbps · 11-bit standard IDs  
**Captures:** 5 × ~15 s targeted captures + 1 × ~90 s accumulation baseline  
**Capture files:** `idle-baseline`, `brake-pedal`, `gear-selection`, `left-turn-signal`, `steering-sweep`, `can-capture-acc`  
**Unique IDs observed:** 50  
**Vehicle state for all captures:** stationary, engine idling — no wheel speed or vehicle speed data captured

---

## Quick-Reference Decodes

| ID    | Source | Signal               | Formula                              | Units  |
|-------|--------|----------------------|--------------------------------------|--------|
| 0x0C9 | PCM    | Engine RPM           | `uint16_BE(b[1], b[2]) / 4`          | RPM    |
| 0x0C9 | PCM    | Brake pedal flag     | `b[5] & 0x01`                        | bool   |
| 0x0F1 | EBCM   | Brake pressure       | `b[1]` (raw, est. ~1.3 kPa/unit)     | kPa    |
| 0x0F1 | EBCM   | Brake applied flag   | `(b[3] >> 7) & 0x01`                 | bool   |
| 0x1F5 | TCM    | PRNDL position       | `b[3]` (see table below)             | enum   |
| 0x191 | PCM    | Injector pulse width | `uint16_BE(b[0], b[1]) * 4`          | µs     |
| 0x4C1 | PCM    | Engine coolant temp  | `b[2] - 40`                          | °C     |
| 0x4C1 | PCM    | Intake air temp      | `b[3] - 40`                          | °C     |
| 0x4D1 | PCM    | Engine oil temp      | `b[5] - 40`                          | °C     |
| 0x3C1 | BCM    | Tire pressure (FL?)  | `b[1] * 2`                           | kPa    |
| 0x3C9 | BCM    | Tire pressure (FR?)  | `b[1] * 2`                           | kPa    |
| 0x514 | PCM    | VIN chars 2–9        | ASCII `b[0:8]`                       | string |
| 0x4E1 | PCM    | VIN Part 2           | ASCII `b[0:8]`                       | string |
| 0x52A | PCM    | Calibration ID pt 2  | ASCII `b[0:8]`                       | string |
| 0x1E5 | PSCM   | Steering wheel angle | `int16_BE(b[0],b[1]) * 0.0625`       | deg    |
| 0x1E5 | PSCM   | Steering wheel rate  | `int12_BE(b[2:3]) * 1.0`             | deg/s  |
| 0x1E9 | EBCM   | Lateral acceleration | `int10 * 0.161`                      | m/s²   |
| 0x1E9 | EBCM   | Yaw rate             | `int12 * 0.0625`                     | grad/s |
| 0x3E9 | ECM    | Vehicle speed        | `uint16_BE(b[0],b[1]) * 0.01`        | mph    |
| 0x348 | EBCM   | FL/FR wheel speed    | `uint16_BE(bN,bN+1) * 0.0311`        | km/h   |
| 0x34A | EBCM   | RL/RR wheel speed    | `uint16_BE(bN,bN+1) * 0.0311`        | km/h   |

---

## Important Note: RPM Formula Correction

The original `can test/esp32_can_test/esp32_can_test.ino` sketch decoded RPM as:

```cpp
// ❌ WRONG — original sketch
uint16_t rpm = ((data[0] << 8) | data[1]) / 4;
```

The correct formula, verified against live idle data (~650–810 RPM):

```cpp
// ✅ CORRECT
uint16_t rpm = ((data[1] << 8) | data[2]) / 4;
```

Byte 0 of 0x0C9 is a 4-bit rolling counter in the high nibble, not RPM data.

---

## opendbc Cross-Reference

The [commaai/opendbc](https://github.com/commaai/opendbc) project maintains DBC files for modern GM Global A vehicles (2016+ Volt, Malibu, Bolt, Silverado). While the 2008 Impala is **not** a supported vehicle and the signal formats may differ in detail, the message IDs are largely consistent with our captures.

**Source file:** `opendbc/dbc/generator/gm/gm_global_a_powertrain.dbc`

**Match rate:** 20 of our 50 captured IDs have corresponding opendbc entries.

### ID Mapping Table

| Hex ID | Dec | opendbc Message Name | ECU         | Key Signals                                               |
|--------|-----|----------------------|-------------|-----------------------------------------------------------|
| 0x0C9  | 201 | ECMEngineStatus      | K20_ECM     | EngineRPM `×0.25`, EngineTPS `×0.392%`, BrakePressed, CruiseMainOn |
| 0x0F1  | 241 | EBCMBrakePedalPosition | K17_EBCM  | BrakePedalPosition b[1], BrakePressed b[0] bit6          |
| 0x12A  | 298 | BCMDoorBeltStatus    | K9_BCM      | FrontLeft/Right Door, RearLeft/Right Door, LeftSeatBelt, RightSeatBelt |
| 0x17D  | 381 | ESPStatus            | K20_ECM     | TractionControlOn                                         |
| 0x184  | 388 | PSCMStatus           | K43_PSCM    | LKADriverAppldTrq, LKATorqueDelivered, LKATorqueDeliveredStatus, RollingCounter |
| 0x1A1  | 417 | AcceleratorPedal     | K20_ECM     | AcceleratorPedal b[6] (0 at idle — correct)              |
| 0x1C3  | 451 | GasAndAcc            | K20_ECM     | GasPedalAndAcc2 b[6] (see note on injector decode below) |
| 0x1E1  | 481 | ASCMSteeringButton   | K124_ASCM   | ACCButtons, LKAButton, DistanceButton, DriveModeButton, RollingCounter |
| 0x1E5  | 485 | PSCMSteeringAngle    | K43_PSCM    | SteeringWheelAngle int16 `×0.0625` deg; SteeringWheelRate int12 `×1.0` deg/s |
| 0x1E9  | 489 | EBCMVehicleDynamic   | K17_EBCM    | BrakePedalPressed, LateralAcceleration `×0.161` m/s², YawRate `×0.0625` grad/s |
| 0x1F1  | 497 | BCMGeneralPlatformStatus | K9_BCM  | SystemPowerMode (0=Off,1=Acc,2=Run,3=Crank), ParkBrakeSwActive |
| 0x1F5  | 501 | ECMPRDNL2            | K20_ECM     | PRNDL2 (1=P,2=R,3=N,4=D), ManualMode, TransmissionState  |
| 0x2F9  | 761 | BRAKE_RELATED_2      | –           | UserBrakePressure2 (9-bit; NOT a BCM heartbeat — see note) |
| 0x348  | 840 | EBCMWheelSpdFront    | K17_EBCM    | FLWheelSpd `×0.0311` km/h, FRWheelSpd `×0.0311` km/h    |
| 0x34A  | 842 | EBCMWheelSpdRear     | K17_EBCM    | RLWheelSpd `×0.0311` km/h, RRWheelSpd `×0.0311` km/h, direction |
| 0x3D1  | 977 | ECMCruiseControl     | K20_ECM     | CruiseActive, CruiseSetSpeed `×0.0625` km/h              |
| 0x3E9  | 1001| ECMVehicleSpeed      | K20_ECM     | VehicleSpeed `×0.01` mph (b[0:2]); VehicleSpeedLeft `×0.01` mph |
| 0x4C1  | 1217| ECMEngineCoolantTemp | K20_ECM     | EngineCoolantTemp b[2] `raw−40` °C                       |
| 0x4E1  | 1249| VIN_Part2            | K20_ECM     | VINPart2 ASCII b[0:8] (**not** cal ID — see correction below) |
| 0x514  | 1300| VIN_Part1            | K20_ECM     | VINPart1 ASCII b[0:8]                                    |

### opendbc BCMTurnSignals — Absent from Captures

opendbc defines `BCMTurnSignals` (K9_BCM) at **ID 0x140 (320 dec)** with signal `TurnSignals: 0=None, 1=Left, 2=Right`. This ID is **not present in our 50 captured IDs**. Since the BCM is clearly active on HS-CAN (we see `BCMDoorBeltStatus` at 0x12A, `BCMGeneralPlatformStatus` at 0x1F1), two possibilities:

1. The 2008 Impala uses a different ID for turn signal broadcast (model year difference)
2. The BCM only transmits this message when a signal is actually active (event-driven, not periodic)

Either way, our earlier conclusion stands: the turn signal state was not visible in our captures. If event-driven, a dedicated "turn signal active throughout" capture at 0x140 could reveal it.

### Corrections and Notes

- **0x4E1 correction:** Previously labeled "Calibration ID pt 1" — opendbc confirms this is `VIN_Part2`, second 8 characters of the 17-digit VIN.
- **0x2F9 correction:** Previously labeled "BCM heartbeat/counter" — opendbc identifies this as `BRAKE_RELATED_2` (user brake pressure, 9-bit value at bit offset 47). The incrementing b[0] counter is likely an internal rolling counter within that message.
- **0x1C3 note:** opendbc identifies this as `GasAndAcc` (gas pedal + ACC). Our observation of injector-like uint16 pairs in b[0:4] may reflect that the 2008 Impala uses this ID differently, or the gas pedal signal occupies b[6] while b[0:4] encodes fuel-related data for this model year.
- **0x184 note:** opendbc identifies this as `PSCMStatus` (Power Steering Control Module), not a BCM rolling counter. The 4-value cycle in b[1] is the `RollingCounter` signal.

---

## Master ID Table

| ID    | DLC | ~Hz  | ECU           | Description                        | Confidence |
|-------|-----|------|---------------|------------------------------------|------------|
| 0x0C1 | 8   | 110  | EBCM          | Wheel speed sensors (FL+RL)        | Medium     |
| 0x0C5 | 8   | 110  | EBCM          | Wheel speed sensors (FR+RR)        | Medium     |
| 0x0C9 | 7   | 80   | PCM           | Engine RPM, throttle, brake flag   | High ✓     |
| 0x0F1 | 4   | 100  | EBCM          | Brake line pressure + applied flag | High ✓     |
| 0x0F9 | 8   | 80   | PCM/BCM?      | Unknown — mostly constant          | Low        |
| 0x120 | 5   | <1   | Unknown       | Very rare, unknown                 | None       |
| 0x12A | 8   | 10   | K9_BCM        | BCMDoorBeltStatus — door+seatbelt  | High ✓     |
| 0x134 | 3   | 10   | Unknown       | Mostly constant `00 00 10`         | Low        |
| 0x138 | 5   | 1    | Unknown       | Mostly constant                    | Low        |
| 0x17D | 8   | 10   | K20_ECM       | ESPStatus — TractionControlOn      | Medium     |
| 0x17F | 8   | 10   | Unknown       | All zeros                          | Low        |
| 0x184 | 6   | 56   | K43_PSCM      | PSCMStatus — LKA steering module   | Medium     |
| 0x191 | 8   | 80   | PCM           | Injector pulse width (all cyl)     | High ✓     |
| 0x199 | 8   | 80   | BCM           | BCM status — rolling counter       | Medium     |
| 0x19D | 8   | 40   | EBCM/SSCM?   | Suspension/steering area           | Low        |
| 0x1A1 | 3   | 40   | K20_ECM       | AcceleratorPedal — 0 at idle ✓     | Medium     |
| 0x1C3 | 5   | 40   | K20_ECM       | GasAndAcc — gas+ACC (see note)     | Medium     |
| 0x1C7 | 7   | 56   | PCM           | High-res timer / crank counter     | Medium     |
| 0x1CD | 5   | 56   | Unknown       | All zeros                          | None       |
| 0x1E1 | 3   | 33   | K124_ASCM     | ASCMSteeringButton — ACC buttons   | Medium     |
| 0x1E5 | 8   | 110  | K43_PSCM      | PSCMSteeringAngle — angle+rate     | High ✓     |
| 0x1E9 | 8   | 56   | K17_EBCM      | EBCMVehicleDynamic — accel+yaw     | High ✓     |
| 0x1F1 | 8   | 10   | K9_BCM        | BCMGeneralPlatformStatus — power   | High ✓     |
| 0x1F3 | 2   | 31   | Unknown       | All zeros                          | None       |
| 0x1F5 | 7   | 40   | TCM           | PRNDL gear position                | High ✓     |
| 0x1F9 | 8   | 33   | Unknown       | Near-constant                      | None       |
| 0x2C3 | 6   | 20   | Unknown       | 3 uint16 pairs — analog signals    | Low        |
| 0x2F9 | 5   | 21   | –             | BRAKE_RELATED_2 — user brake pres  | Medium     |
| 0x334 | 2   | 33   | Unknown       | All zeros                          | None       |
| 0x348 | 4   | 21   | K17_EBCM      | EBCMWheelSpdFront FL/FR ×0.0311    | High ✓     |
| 0x34A | 4   | 21   | K17_EBCM      | EBCMWheelSpdRear RL/RR ×0.0311+dir | High ✓     |
| 0x3C1 | 8   | 10   | BCM/TPMS      | Tire pressure sensor 1 (FL?)       | Medium     |
| 0x3C9 | 8   | 10   | BCM/TPMS      | Tire pressure sensor 2 (FR?)       | Medium     |
| 0x3D1 | 8   | 10   | K20_ECM       | ECMCruiseControl — speed setpoint  | Medium     |
| 0x3E9 | 8   | 10   | K20_ECM       | ECMVehicleSpeed ×0.01 mph          | High ✓     |
| 0x3F1 | 6   | 4    | PCM/BCM       | Cumulative runtime counter (slow)  | Low        |
| 0x3F9 | 8   | 4    | PCM           | ECM runtime timer                  | Medium     |
| 0x4C1 | 8   | 2    | PCM           | Engine coolant + intake air temps  | High ✓     |
| 0x4C9 | 6   | 2    | PCM           | IAT or aux temp channel            | Medium     |
| 0x4D1 | 8   | 2    | PCM           | Oil temp + aux channels            | High ✓     |
| 0x4E1 | 8   | 1    | K20_ECM       | VIN_Part2 — VIN chars 10–17        | High ✓     |
| 0x4E9 | 2   | 1    | PCM/BCM       | Status flags — constant `C1 40`    | Low        |
| 0x4F1 | 8   | 1    | PCM           | Fuel rail pressure                 | Medium     |
| 0x500 | 4   | 1    | Unknown       | All zeros                          | None       |
| 0x514 | 8   | 1    | PCM           | VIN broadcast (chars 2–9)          | High ✓     |
| 0x52A | 8   | 1    | PCM           | Calibration ID part 2              | High ✓     |
| 0x771 | 7   | 1    | Diag module A | UDS/KWP diagnostic response node   | Medium     |
| 0x772 | 7   | 1    | Diag module B | UDS/KWP diagnostic response node   | Medium     |
| 0x773 | 7   | 1    | Diag module C | UDS/KWP diagnostic response node   | Medium     |
| 0x77F | 7   | 1    | Diag module D | UDS/KWP diagnostic response node   | Medium     |

---

## Confirmed Decodes

### 0x0C9 — PCM: Engine RPM + Throttle + Brake Flag (80 Hz)

**Sample payload (idle):** `84 0A 65 00 00 40 00`

| Byte(s) | Description                                    | Formula / Notes                  |
|---------|------------------------------------------------|----------------------------------|
| b[0]    | Rolling counter in high nibble + flags         | `(b[0] >> 4)` = 0–F sequence    |
| b[1:2]  | Engine RPM (big-endian uint16)                 | `((b[1]<<8) | b[2]) / 4`        |
| b[3]    | Rolling sub-counter                            | Cycles 0x00 → 0x07 → 0x0A → 0x0D |
| b[5]    | Brake pedal flag in bit 0                      | `0x40` = no brake, `0x41` = brake |

**Idle RPM range:** raw ≈ 2624–3240 → 656–810 RPM ✓  
**Brake flag:** b[5] transitions cleanly `0x40 → 0x41` on pedal press, instantaneous response.

```cpp
// Verified RPM decode
uint16_t raw = ((uint16_t)data[1] << 8) | data[2];
uint16_t rpm = raw / 4;
bool brake = data[5] & 0x01;
```

---

### 0x0F1 — EBCM: Brake Line Pressure + Applied Flag (100 Hz)

**Sample payload (idle):** `28 05 00 40`  
**Sample payload (braking):** `0A 96 00 C0`

| Byte | Description                    | Notes                                    |
|------|--------------------------------|------------------------------------------|
| b[0] | Rolling counter                | Cycles 0x00 → 0x1C → 0x28 → 0x34       |
| b[1] | Brake pressure proxy (analog)  | 5–9 at rest, rises to ~196 under braking |
| b[2] | Differential/noise byte        | Mostly 0x00, small noise during apply    |
| b[3] | Status byte                    | Bit 7 = brake applied; bit 6 = always 1 |

**Pressure ramp:** b[1] climbs steadily as driver pushes harder (9 → 186 typical firm stop),  
then descends on release. Estimated scale: ~1.3 kPa/unit (186 units ≈ 242 kPa ≈ 35 PSI,  
plausible for moderate braking). Formula unconfirmed — needs a pressure transducer reference.

```cpp
bool brake_applied = (data[3] >> 7) & 0x01;
uint8_t pressure_raw = data[1];   // 0 = no pressure
```

---

### 0x1F5 — TCM: PRNDL Gear Position (40 Hz)

**Sample payloads:**

| b[0]  | b[3] | Gear     | Confirmed |
|-------|------|----------|-----------|
| `0F`  | `01` | **Park** | ✓         |
| `0E`  | `02` | **Reverse** | ✓      |
| `0D`  | `03` | **Neutral** | ✓      |
| `01`  | `04` | **Drive** | ✓        |

Sequence in gear-selection capture: P → R → N → D → N → R → P (verified full cycle).  
b[0] = display code (gear indicator character); b[3] = numeric position enum.

```cpp
uint8_t gear = data[3];  // 1=P 2=R 3=N 4=D
```

---

### 0x191 — PCM: Injector Pulse Width (80 Hz)

**Sample payload (idle):** `06 AA 06 AA 06 AA 00 00`

Bytes 0–1, 2–3, 4–5 are three identical big-endian uint16 values (one per cylinder bank or fuel delivery phase). At idle all three are equal, confirming balanced fuel delivery.

| Value | Formula          | Result            |
|-------|------------------|-------------------|
| 0x06AA = 1706 | × 4 µs | **6,824 µs = 6.82 ms** injector on-time |

6.82 ms at idle is consistent with GM 3.9L V6 port injection pulse width. ✓  
During brake captures (slightly elevated idle load): values drop slightly to ~1674–1706.

```cpp
uint16_t raw = ((uint16_t)data[0] << 8) | data[1];
uint32_t pulse_us = (uint32_t)raw * 4;
```

---

### 0x4C1 — PCM: Engine Temperature Channels (2 Hz)

**Sample payload:** `11 C5 7F 47 81 00 00 00`

| Byte | Signal               | Formula     | Captured value      |
|------|----------------------|-------------|---------------------|
| b[1] | Unknown (MAP/other?) | TBD         | 0xC5 = 197 (stable) |
| b[2] | Engine coolant temp  | raw − 40 °C | 0x7F = 127 → **87°C** ✓ |
| b[3] | Intake air temp      | raw − 40 °C | 0x47 = 71  → **31°C** ✓ |

Both temps are consistent with a fully warmed engine on a moderate day.

```cpp
int8_t coolant_c = (int16_t)data[2] - 40;
int8_t iat_c     = (int16_t)data[3] - 40;
```

---

### 0x4D1 — PCM: Oil + Auxiliary Temperatures (2 Hz)

**Sample payload:** `80 00 3A 02 12 6F 00 7D`

| Byte | Signal          | Formula     | Captured value          |
|------|-----------------|-------------|-------------------------|
| b[2] | Possible ambient/other | raw − 40 °C | 0x3A = 58 → 18°C (ambient?) |
| b[5] | Engine oil temp | raw − 40 °C | 0x6F = 111 → **71°C** ✓  |

71°C oil temp with 87°C coolant temp is plausible — oil lags coolant on warmup.

```cpp
int8_t oil_c = (int16_t)data[5] - 40;
```

---

### 0x4F1 — PCM: Fuel Rail Pressure (1 Hz)

**Sample payload:** `01 B3 01 B3 00 00 00 7D`

Bytes 0–1 and 2–3 both encode the same big-endian uint16 = **0x01B3 = 435**.  
At 435 kPa = **63 PSI**, which is slightly elevated but within range for a returnless  
fuel system under no load. The formula may involve an offset: `(raw - offset) * scale`.  
Values are identical across all captures (fuel pressure stable at idle).

```cpp
uint16_t fuel_kpa = ((uint16_t)data[0] << 8) | data[1];
// ~63 PSI at idle, formula/scale unconfirmed
```

---

### 0x514 — PCM: VIN Broadcast Part 1 (1 Hz)

**Payload:** `47 31 57 54 35 38 4B 33`  
**Decoded ASCII:** `G1WT58K3`

This matches VIN positions 2–9 of a 2008 Chevrolet Impala: `2G1WT58K?8???????`. ✓

---

### 0x4E1 / 0x52A — PCM: VIN Part 2 + Calibration ID (1 Hz)

**0x4E1 payload:** `38 31 33 37 31 38 37 36`  
**Decoded ASCII:** `81371876`

opendbc confirms 0x4E1 = `VIN_Part2` (K20_ECM). For this vehicle the second VIN segment decodes to `81371876` — this may represent a calibration identifier embedded within the VIN broadcast slot, or the VIN positions beyond what 0x514 covers. The full GM 17-digit VIN is split: 0x514 = positions 1–8, 0x4E1 = positions 9–17 (or similar).

**0x52A payload:** `24 24 39 30 39 2E 00 FF`  
**Decoded ASCII:** `$$909.`

ECU calibration identifier suffix. Combined: `81371876 / $$909.`

---

### 0x3C1 / 0x3C9 — BCM/TPMS: Tire Pressure Sensors (10 Hz)

| ID    | Payload sample                   | b[0] | b[1] raw | Decoded |
|-------|----------------------------------|------|----------|---------|
| 0x3C1 | `07 65 E7 00 00 00 00 00`        | 0x07 | 101      | 202 kPa ≈ **29.3 PSI** |
| 0x3C9 | `07 66 00 00 00 00 00 00`        | 0x07 | 102      | 204 kPa ≈ **29.6 PSI** |

Formula: `b[1] * 2 = kPa`. Both values plausible for slightly under-inflated tires  
(factory spec ~240 kPa / 35 PSI). b[0] = 0x07 likely sensor status byte.  
Two additional TPMS IDs (for rear axle) were not seen — may be on a different bus or ID.

```cpp
uint16_t psi_x10 = (uint16_t)data[1] * 2 * 10 / 69;  // kPa → PSI × 10
```

---

### 0x1E5 — PSCM: Steering Wheel Angle + Rate (110 Hz)

**Source:** opendbc `PSCMSteeringAngle` (K43_PSCM)  
**Sample payload:** `82 00 00 E0 00 FF FD 62`

| Signal              | DBC definition                | Formula                                   | Notes                         |
|---------------------|-------------------------------|-------------------------------------------|-------------------------------|
| SteeringWheelAngle  | `int16 @0- bit15, ×0.0625`   | `int16_BE(b[0],b[1]) * 0.0625`           | Range −2047..2047 deg         |
| SteeringWheelRate   | `int12 @0- bit27, ×1.0`      | signed 12-bit from bits 27..16            | Range −2047..2047 deg/s       |

The sample b[0:2] = `0x8200` — if decoded per formula: signed 16-bit = −32256 * 0.0625 = −2016° (out of range). This suggests the 2008 Impala may use a different scale or offset, OR the car was at a non-center position. Verification requires a driving capture with known steering positions. The ID and ECU assignment are confirmed.

```cpp
int16_t raw = ((int16_t)data[0] << 8) | data[1];
float angle_deg = raw * 0.0625f;   // per opendbc; verify scale on 2008 model year
```

---

### 0x1E9 — EBCM: Vehicle Dynamics (56 Hz)

**Source:** opendbc `EBCMVehicleDynamic` (K17_EBCM)  
**Sample payload:** `00 00 00 0C 00 00 04 00`

| Signal              | DBC definition                   | Formula                          | Notes                      |
|---------------------|----------------------------------|----------------------------------|----------------------------|
| BrakePedalPressed   | `1bit @0+ bit6`                  | `(b[0] >> 1) & 0x01`            | 1 = pressed                |
| LateralAcceleration | `int10 @0- bit3, ×0.161`         | see DBC decode                   | Range −2047..2047 m/s²     |
| YawRate             | `int12 @0- bit51, ×0.0625`       | see DBC decode                   | grad/s                     |
| YawRate2            | `int12 @0- bit35, ×0.625`        | secondary yaw channel            | grad/s                     |

This message carries real inertial data from the EBCM — complementary to the BNO085 IMU on the capture device. During stationary idle all bytes are near-zero or constant, which is expected. A driving capture will expose lateral acceleration in corners and yaw rate.

---

### 0x1F1 — BCM: General Platform Status (10 Hz)

**Source:** opendbc `BCMGeneralPlatformStatus` (K9_BCM)  
**Sample payload (idle):** varies slowly

| Signal              | DBC definition             | Formula                  | Notes                              |
|---------------------|----------------------------|--------------------------|------------------------------------|
| SystemPowerMode     | `2bit @0+ bit1`            | `(b[0] >> 6) & 0x03`    | 0=Off, 1=Acc, 2=Run, 3=Crank      |
| SystemBackUpPowerMode | `2bit @0+ bit5`          | `(b[0] >> 2) & 0x03`    | Mirrors PowerMode + preconditioning|
| ParkBrakeSwActive   | `1bit @0+ bit36`           | b[4] specific bit        | 1 = park brake engaged             |

While running, `SystemPowerMode` = 2 (Run). Useful for detecting ignition state transitions.

---

### 0x12A — BCM: Door + Seatbelt Status (10 Hz)

**Source:** opendbc `BCMDoorBeltStatus` (K9_BCM)  
**Sample payload:** `C7 00 03 00 A8 73 3F FF`

| Signal          | DBC bit      | Notes                          |
|-----------------|--------------|--------------------------------|
| FrontLeftDoor   | bit 9        | 1 = open                       |
| FrontRightDoor  | bit 10       | 1 = open                       |
| RearLeftDoor    | bit 8        | 1 = open                       |
| RearRightDoor   | bit 23       | 1 = open                       |
| LeftSeatBelt    | bit 12       | 1 = latched                    |
| RightSeatBelt   | bit 53       | 1 = latched                    |

The slowly varying bytes 4–5 in this message that we observed earlier are likely seatbelt/door flags changing with occupancy. With all doors closed and belts latched during capture, the values reflect that state.

---

### 0x348 / 0x34A — EBCM: Wheel Speed (Front + Rear) (21 Hz)

**Source:** opendbc `EBCMWheelSpdFront` / `EBCMWheelSpdRear` (K17_EBCM)

| ID    | Signal       | DBC definition         | Formula                         |
|-------|--------------|------------------------|---------------------------------|
| 0x348 | FLWheelSpd   | `uint16 @0+ bit7`      | `uint16_BE(b[0],b[1]) * 0.0311` km/h |
| 0x348 | FRWheelSpd   | `uint16 @0+ bit23`     | `uint16_BE(b[2],b[3]) * 0.0311` km/h |
| 0x34A | RLWheelSpd   | `uint16 @0+ bit7`      | `uint16_BE(b[0],b[1]) * 0.0311` km/h |
| 0x34A | RRWheelSpd   | `uint16 @0+ bit23`     | `uint16_BE(b[2],b[3]) * 0.0311` km/h |
| 0x34A | RLWheelDir   | `3bit @0+ bit37`       | 0=Stationary, 1=Forward, 2=Reverse, 3=Unsupported |
| 0x34A | RRWheelDir   | `3bit @0+ bit34`       | same encoding                   |

All-zero during stationary captures. A parking-lot creep capture will confirm scale.

```cpp
float fl_kmh = (((uint16_t)data[0] << 8) | data[1]) * 0.0311f;  // EBCMWheelSpdFront
float fr_kmh = (((uint16_t)data[2] << 8) | data[3]) * 0.0311f;
```

---

### 0x3E9 — ECM: Vehicle Speed (10 Hz)

**Source:** opendbc `ECMVehicleSpeed` (K20_ECM)

```
VehicleSpeed  : 7|16@0+ (0.01,0) [0|0] "mph"
VehicleSpeedLeft : 39|16@0+ (0.01,0) [0|0] "mph"
```

Formula: `uint16_BE(b[0],b[1]) * 0.01 = mph`. The "Left" channel likely reflects left-wheel average (2WD drive tire).

```cpp
float speed_mph = (((uint16_t)data[0] << 8) | data[1]) * 0.01f;
```

---

## Partially Decoded

### 0x19D — Suspension or Chassis Module (40 Hz)

**Sample:** `A0 00 1F FE 00 08 AF FF`

b[0] encodes a 4-state rolling counter in bits 7–5: cycles `0x20 → 0x60 → 0xA0 → 0xE0`.  
b[6] has the most variation across captures (221 unique values in steering sweep),  
but the same wide variation is also present at idle — it is not clearly steering angle.  
b[5] varies slowly in range 7–10 across all captures.  

The "steering sweep" capture did not produce a clean monotonic sweep in any byte,  
suggesting either the wheel was not turned far enough to produce a wide excursion, or  
the steering angle sensor is on a different bus (e.g., LSCAN or serial UART to BCM).

**Status:** best guess is suspension ride height or chassis attitude, but unconfirmed.

---

### 0x0C1 / 0x0C5 — EBCM: Wheel Speed (110 Hz)

**Sample:** `00 01 00 04 00 01 00 04`

These IDs appear to carry paired wheel speed values. Both IDs carry identical payloads,  
consistent with redundant broadcast from EBCM. All captures show near-zero values  
because the vehicle was stationary. A driving capture is required to confirm the decode.

Likely: `uint16_BE(b[0], b[1])` and `uint16_BE(b[4], b[5])` = wheel speeds in some unit.

---

### 0x348 / 0x34A — EBCM: Wheel Speed (Individual) (21 Hz)

**Sample:** `00 00 00 00` (all zero — vehicle stationary)

Per `data-capture-concerns.md`, these IDs carry individual wheel speed values.  
All-zero payloads confirmed across all captures due to stationary vehicle.  
Need driving capture to verify decode formula and per-wheel assignment.

---

### 0x1C7 — PCM: High-Resolution Crank Counter (56 Hz)

**Sample:** `06 62 B9 9C 00 00 3F`

b[0:2] = `06 62` constant; b[2:4] cycles through exactly 4 values spaced ~16,383 apart:  
`0xB99C → 0xF99B → 0x399A → 0x7999`. These are evenly-spaced phases of a 16-bit counter,  
suggesting crankshaft position encoder ticks or a high-resolution ignition timer.

---

### 0x2F9 — BRAKE_RELATED_2: User Brake Pressure (21 Hz)

**Source:** opendbc correction (previously mislabeled "BCM heartbeat")  
**Sample:** `A0 00 00 00 00`

opendbc identifies this as `BRAKE_RELATED_2` with signal `UserBrakePressure2` as a 9-bit value at bit offset 47 (b[5] area). b[0] = free-running counter, not a BCM heartbeat. During our brake-pedal capture this ID should show brake pressure changes — a closer look at the brake capture data with correct bit extraction is needed to confirm the formula.

---

### 0x3E9 — ECM: Vehicle Speed (10 Hz)

**Source:** opendbc `ECMVehicleSpeed` (K20_ECM)  
**Formula confirmed:** See "Decoded via opendbc" section above.  
All zeros in every capture — vehicle was stationary for all recordings.

---

### 0x3F9 — PCM/ECM: Runtime Timer (4 Hz)

b[1:5] encodes a 32-bit value that increments significantly between captures:

| Capture  | b[1:5] value   | Δ from idle   |
|----------|----------------|---------------|
| idle     | 620,205,142    | —             |
| brake    | 643,012,950    | +22,807,808   |
| gear     | 723,822,422    | +80,809,472   |

Increments of ~22.8M and ~80.8M between sequential captures taken minutes apart —  
consistent with a microsecond-resolution runtime timer. Use as session timestamp.

---

### 0x4C9 — PCM: Auxiliary Temperature Channel (2 Hz)

**Sample:** `40 47 00 00 00 00`

b[1] = 0x47 = 71; `71 - 40 = 31°C` — matches the IAT reading from 0x4C1.  
b[0] = 0x40 = possible channel selector or status. May be a secondary IAT or  
transmission fluid temperature channel.

---

### 0x184 — PSCM: Power Steering Status (56 Hz)

**Source:** opendbc `PSCMStatus` (K43_PSCM)  
**Sample:** `00 03 00 00 00 00`

opendbc confirms this is the Power Steering Control Module status message. b[1] is the `RollingCounter` signal cycling 0x00 → 0x01 → 0x02 → 0x03. Remaining bytes carry LKA torque delivered, LKA status, and driver-applied torque — all near-zero at idle with no LKA active.

---

### 0x0F9 — Unknown (80 Hz)

**Sample:** `00 00 40 00 00 00 00 FF`

Constant payload across all captures: `00 00 40 00 00 00 00 FF`.  
Two unique values seen in the longer acc capture. Possibly A/C compressor status  
or HVAC module broadcast. Not stimulus-correlated in our captures.

---

## Turn Signal — Not Found on HS-CAN

The `left-turn-signal` capture was analyzed against `idle-baseline` with per-phase  
byte comparison across all 50 IDs. **No clean bit-flag difference was found.**

**opendbc note:** The opendbc DBC defines `BCMTurnSignals` at **ID 0x140 (320 dec)** on the HS-CAN powertrain bus with signal `TurnSignals: 0=None, 1=Left, 2=Right`. However, **0x140 is not present in our 50 captured IDs** — even though the BCM is clearly active on HS-CAN (we capture `BCMDoorBeltStatus` at 0x12A and `BCMGeneralPlatformStatus` at 0x1F1 from the same module). Two explanations:

1. The 2008 Impala BCM uses a different ID for turn signal broadcast than the 2016+ vehicles opendbc targets
2. `BCMTurnSignals` is event-driven on this vehicle year — only transmitted when a signal is active — and our left-turn capture happened to not contain any 0x140 frames (possible if the message wasn't initiated at capture start)

**Conclusion:** Turn signal state is not accessible at the IDs present in our captures. To investigate further: run a dedicated capture with the left stalk held continuously in the turn position from before capture start, and look for any new ID appearing that was not in the idle baseline. Also watch for 0x140 specifically.

---

## IDs With No Decode Progress

| ID    | Hz  | Sample payload                   | Notes                             |
|-------|-----|----------------------------------|-----------------------------------|
| 0x120 | <1  | `00 DA 77 00 00`                 | Only 3 frames seen, possibly init |
| 0x134 | 10  | `00 00 10`                       | Near-constant; not in opendbc     |
| 0x138 | 1   | `00 00 00 00 0B`                 | Near-constant; not in opendbc     |
| 0x17F | 10  | `00 00 00 00 00 00 00 00`        | All zeros; not in opendbc         |
| 0x1CD | 56  | `00 00 00 00 00`                 | All zeros                         |
| 0x1F3 | 31  | `00 00`                          | All zeros                         |
| 0x1F9 | 33  | `00 00 00 00 80 00 FF 00`        | Near-constant; not in opendbc     |
| 0x2C3 | 20  | `08 55 06 A0 07 4A`              | 3 uint16s, vary slowly            |
| 0x334 | 33  | `00 00`                          | All zeros                         |
| 0x3F1 | 4   | `00 CB 56 18 FF FC`              | Increments b[1] slowly over time  |
| 0x4E9 | 1   | `C1 40`                          | Constant — possibly mode flags    |
| 0x500 | 4   | `00 00 00 00`                    | All zeros                         |
| 0x771 | 1   | `00 40 00 00 00 00 00`           | UDS module present indicator      |
| 0x772 | 1   | `00 00 00 00 00 00 00`           | UDS module present indicator      |
| 0x773 | 1   | `00 28 00 00 00 00 00`           | UDS module present indicator      |
| 0x77F | 1   | `00 00 00 00 00 00 00`           | UDS module present indicator      |

IDs moved to confirmed/partial since initial analysis: `0x12A` (BCMDoorBeltStatus), `0x17D` (ESPStatus), `0x184` (PSCMStatus), `0x1A1` (AcceleratorPedal), `0x1C3` (GasAndAcc), `0x1E1` (ASCMSteeringButton), `0x1E5` (PSCMSteeringAngle), `0x1E9` (EBCMVehicleDynamic), `0x1F1` (BCMGeneralPlatformStatus), `0x2F9` (BRAKE_RELATED_2), `0x3D1` (ECMCruiseControl).

---

## Suggested Next Captures

To make further progress on unresolved IDs and validate opendbc formulas:

1. **Driving capture at known speed** — Verify 0x3E9 (`ECMVehicleSpeed` ×0.01 mph), 0x348/0x34A (`EBCMWheelSpdFront/Rear` ×0.0311 km/h), and 0x0C1/0x0C5 (alternate EBCM wheel speed format). Even a slow 5–10 mph creep in a parking lot confirms decode formulas. Also expected to unlock 0x1A1 (AcceleratorPedal) during acceleration.

2. **Hard acceleration + deceleration** — Higher RPM range to confirm 0x0C9 RPM formula at non-idle values, and to see 0x191 injector pulse width change significantly. Also exercises 0x1E9 (lateral accel, yaw) and 0x1E5 (steering angle rate).

3. **Steering wheel full slow sweep** — To validate 0x1E5 (`PSCMSteeringAngle`) formula. Per opendbc: `int16_BE(b[0],b[1]) * 0.0625 = degrees`. A full lock-to-lock sweep should produce a clean −540..+540° (or similar) ramp.

4. **Turn signal held continuously** — To check if 0x140 (`BCMTurnSignals`) appears in a capture where the stalk is held from before capture start. This distinguishes event-driven vs. absent signal.

5. **Heater or A/C on/off** — May expose 0x0F9 (possible HVAC status), 0x4E9, and some of the constant-appearing BCM messages.

6. **LSCAN access** — OBD-II pin 1 on some 2008 GM vehicles carries the Low Speed GMLAN bus. If accessible, it exposes BCM body functions not visible on HS-CAN.
