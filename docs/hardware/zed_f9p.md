# u-blox ZED-F9P

**Breakout:** SparkFun GPS-RTK-SMA (GPS-16481)  
**Interface in this repo:** I2C over Qwiic (not UART)  
**Library:** SparkFun u-blox GNSS v3 (`SFE_UBLOX_GNSS` class)  
**USB port:** `/dev/ttyACM0`

---

## I2C

- **7-bit address:** `0x42` (default)
- **Logic level:** 3.3V
- **Library class:** `SFE_UBLOX_GNSS` — this IS the I2C class in v3; there is no separate `SFE_UBLOX_GNSS_I2C`

```cpp
SFE_UBLOX_GNSS gnss;
Wire.begin();
gnss.begin();  // connects at 0x42
```

### CFG-I2C-ADDRESS encoding — critical gotcha

The register `CFG-I2C-ADDRESS` (key `0x20510001`) stores the **8-bit left-shifted address**, not the 7-bit address:

| Stored value | 7-bit bus address |
|---|---|
| `0x84` (default) | `0x42` |
| `0x42` (wrong!) | `0x21` |

**Never write `0x42` to this register.** The correct value to keep the default address is `0x84`. Writing `0x42` silently moves the device to 7-bit address `0x21` — all standard `gnss.begin()` calls will fail with no useful error.

If the device ends up at 0x21: connect with `gnss.begin(Wire, 0x21)`, then call `gnss.setVal8(0x20510001, 0x84, VAL_LAYER_ALL)`. The library will return `false` (the ACK comes from the new address 0x42 before the library can receive it), but the write succeeds. Reconnect at `0x42` to confirm.

See `rtk_base_station/docs/issues/2026_06_10_f9p_i2c_address_register_encoding.md`.

---

## I2C bus locking

The F9P holds SDA LOW when its ~1KB I2C output buffer fills and no master is reading it. This happens within ~1 second of enabling RAWX output at 1Hz if `checkUblox()` is not called regularly.

**Always drain the F9P buffer before calling `gnss.begin()`** after any restart where RAWX or other high-rate messages may have been running:

```cpp
void drainF9P() {
  for (int iter = 0; iter < 500; iter++) {
    Wire.beginTransmission(0x42);
    Wire.write(0xFD);  // bytes-available register
    if (Wire.endTransmission(false) != 0) break;
    uint8_t n = Wire.requestFrom((uint8_t)0x42, (uint8_t)2);
    if (n < 2) break;
    uint16_t avail = ((uint16_t)Wire.read() << 8) | Wire.read();
    if (avail == 0 || avail == 0xFFFF) break;
    uint8_t chunk = (avail > 32) ? 32 : (uint8_t)avail;
    uint8_t got = Wire.requestFrom((uint8_t)0x42, chunk);
    for (int i = 0; i < got; i++) Wire.read();
    if (avail <= 32) break;
  }
}
```

Also call `gnss.setAutoRXMRAWX(false)` when RAWX is no longer needed — do not leave it running across a restart or between long idle periods.

See `rtk_base_station/docs/issues/2026_06_10_f9p_i2c_bus_lockup_rawx_buffer.md`.

---

## Configuration

All config uses the generation-9 VALSET/VALGET interface (key-value pairs, not legacy CFG-* packets).

### Key config keys

| Key ID | Name | Notes |
|--------|------|-------|
| `0x20510001` | CFG-I2C-ADDRESS | Stores 8-bit shifted address (default `0x84`) |
| `0x10510003` | CFG-I2C-ENABLED | `1` = enabled |
| `0x10710001` | CFG-I2CINPROT-UBX | Must be `1` — required for SparkFun library `begin()` |
| `0x10720001` | CFG-I2COUTPROT-UBX | `1` = UBX output on I2C |
| `0x20110021` | CFG-NAVSPG-DYNMODEL | `2` = Stationary |
| `0x30210001` | CFG-RATE-MEAS | Measurement period in ms (e.g. `1000` = 1Hz) |
| `0x20030001` | CFG-TMODE-MODE | `0` = Disabled, `2` = Fixed Position |

### Layers

| Constant | Value | Persists across |
|----------|-------|-----------------|
| `VAL_LAYER_RAM` | `0x01` | Until power cycle |
| `VAL_LAYER_BBR` | `0x02` | Until factory reset |
| `VAL_LAYER_FLASH` | `0x04` | Permanently |
| `VAL_LAYER_ALL` | `0x07` | All three |

---

## Current hardware state (RTK base station unit)

- **Firmware:** HPG 1.13 (current release is HPG 1.32 — update before Phase 3 install)
- **TMODE3:** Disabled (Phase 1 raw logging mode)
- **Constellations:** GPS L1+L2, GLONASS L1+L2, Galileo E1+E5b, BeiDou B1I+B2I
- **RTCM3 on I2C:** 1005, 1077, 1087, 1097, 1127 @ 1Hz; 1230 @ 5Hz
- **I2C address:** 0x42 (corrected; was inadvertently moved to 0x21)

---

## USB direct access (Python)

To send raw UBX commands over USB without the ESP32:

```python
import serial, struct, time

f = serial.Serial('/dev/ttyACM0', 38400, timeout=1)

def ubx(cls, id, payload=b''):
    msg = bytes([0xB5, 0x62, cls, id, len(payload) & 0xFF, len(payload) >> 8]) + payload
    ck_a = ck_b = 0
    for b in msg[2:]:
        ck_a = (ck_a + b) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return msg + bytes([ck_a, ck_b])

# VALGET example: read CFG-I2C-ADDRESS from RAM
key = 0x20510001
payload = struct.pack('<HBB', 0, 0, 0) + struct.pack('<I', key)
f.write(ubx(0x06, 0x8B, payload))
time.sleep(0.1)
print(f.read(64).hex())
```
