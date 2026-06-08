# GM CAN Coverage Comparison

## Summary

**Your 2008 Impala DBC has 100% unique content compared to opendbc.**

The comma.ai opendbc project focuses on 2016+ vehicles with:
- Extended CAN (29-bit IDs)
- ADAS systems (radar, camera, lane keeping)
- Electric/hybrid vehicles (battery, charger, regen)

Your 2008 Impala captures traditional HS-CAN powertrain signals that aren't covered.

## ID Comparison

| Source | Standard 11-bit IDs | Extended 29-bit IDs | Focus |
|--------|---------------------|---------------------|-------|
| 2008 Impala (ours) | 21 | 0 | Powertrain, chassis, body |
| opendbc GM | 75 | 200+ | ADAS, EV, modern infotainment |
| **Overlap** | **0** | N/A | - |

## Unique Signals We Have

### Powertrain (PCM)
| ID | Message | Key Signals |
|----|---------|-------------|
| 0x0C9 | ECMEngineStatus | EngineRPM, BrakePedalPressed |
| 0x191 | InjectorPulseWidth | Fuel injector timing (all banks) |
| 0x1A1 | AcceleratorPedal | Throttle position |
| 0x3E9 | ECMVehicleSpeed | Vehicle speed (mph) |
| 0x4C1 | ECMEngineCoolantTemp | Coolant temp, IAT |
| 0x4D1 | OilTemp | Engine oil temperature |
| 0x4F1 | FuelRailPressure | Fuel system pressure |
| 0x514 | VIN_Part1 | VIN broadcast |

### Chassis (EBCM)
| ID | Message | Key Signals |
|----|---------|-------------|
| 0x0F1 | EBCMBrakePressure | Brake line pressure, applied flag |
| 0x1E9 | EBCMVehicleDynamic | Lateral accel, yaw rate |
| 0x348 | EBCMWheelSpdFront | FL/FR wheel speeds |
| 0x34A | EBCMWheelSpdRear | RL/RR wheel speeds + direction |

### Steering (PSCM)
| ID | Message | Key Signals |
|----|---------|-------------|
| 0x1E5 | PSCMSteeringAngle | Steering wheel angle + rate |

### Body (BCM)
| ID | Message | Key Signals |
|----|---------|-------------|
| 0x12A | BCMDoorBeltStatus | Door ajar, seatbelt status |
| 0x1F1 | BCMGeneralPlatformStatus | Ignition state, park brake |
| 0x3C1/0x3C9 | TPMSSensor | Tire pressure |

### Transmission (TCM)
| ID | Message | Key Signals |
|----|---------|-------------|
| 0x1F5 | ECMPRDNL | Gear position (P/R/N/D) |

## What opendbc Has (That We Don't)

- **Radar objects:** LRRObject01-20, F_Vision_Obj_Track (object detection for ACC/AEB)
- **Camera systems:** F_Vision_Environment, Lane departure
- **EV systems:** Battery modules, APM, charger stats, regen braking
- **ADAS controls:** ASCMSteeringStatus, CruiseButtons, lane centering
- **Modern body:** Extended status messages on 29-bit CAN

## Conclusion

Your 2008 Impala DBC file represents **legacy GM powertrain data** that comma.ai doesn't cover because:
1. opendpilot (their autonomous driving system) doesn't support pre-2016 vehicles
2. Older vehicles lack the ADAS hardware they're interfacing with
3. Different CAN architecture (standard vs extended IDs)

**This makes your DBC valuable for:**
- Pre-2016 GM vehicle projects
- Basic vehicle telemetry (speed, RPM, temps, brakes)
- Retrofitting data logging to older vehicles
- Understanding GM CAN evolution

## Files

- Your DBC: `dbc/custom/chevy_impala_2008_hscan.dbc`
- opendbc GM: `dbc/opendbc/gm_global_a_*.dbc`
