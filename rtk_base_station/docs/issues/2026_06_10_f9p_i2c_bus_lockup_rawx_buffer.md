# F9P holds SDA LOW after restart when RAWX streaming fills the unread I2C output buffer

**Date:** 2026-06-10  
**Component:** `firmware/f9p_verify/f9p_verify.ino` — `setup()`, `runVerification()`; `firmware/phase1_raw_logger/phase1_raw_logger.ino` — `setup()`  
**Severity:** Medium — causes `gnss.begin()` to hang silently after any restart that follows RAWX-enabled operation; requires a drain loop to recover cleanly

---

## Observed symptom

After the first successful run of `f9p_verify`, re-flashing the sketch (which resets the ESP32 but not the F9P) produced no serial output at all — not even the "F9P Verifier starting..." line that appears before `Wire.begin()`. The sketch appeared to hang in `setup()` before printing anything.

Separately, an earlier diagnostic iteration showed `gnss.begin()` returning `false` on every retry despite the F9P being reachable (address confirmed, I2C config correct). Each retry printed "Draining F9P buffer... 0 bytes drained" because the drain function itself was failing.

---

## Root cause

### The F9P holds SDA LOW when its I2C output buffer is full

The ZED-F9P has a ~1 KB internal I2C output buffer. When the master is not polling (`checkUblox()` not being called), the F9P continues to fill this buffer with outgoing UBX messages. Once the buffer is full, the F9P asserts SDA LOW to signal it needs to be read — effectively locking the I2C bus.

If the ESP32 resets (e.g. due to re-flash) while the F9P has pending data, the F9P continues accumulating data into the buffer. By the time the new sketch calls `Wire.begin()`, the bus may already be locked.

### RAWX logging left enabled between restarts

`f9p_verify`'s `runVerification()` called `gnss.setAutoRXMRAWXcallbackPtr()` to enable automatic RAWX output for a 5-second live test, but never disabled it afterward. At 1 Hz with ~2 KB per RAWX message, the F9P's 1 KB output buffer overflows within one epoch when no master is reading. The 50 ms `loop()` polling kept up during normal operation, but the first re-flash left RAWX running with no consumer.

### `Wire.begin()` on ESP32 can hang if SDA is LOW at init time

The ESP32's I2C peripheral driver can enter a locked state if SDA is held LOW when `Wire.begin()` initialises the peripheral. This explains the complete absence of serial output: the sketch hung inside `Wire.begin()` (called after `Serial.begin()` + `delay(3000)` in the updated verifier, but before any `Serial.println()` in the original version which called `Wire.begin()` before printing).

### Drain function failed when SDA was already locked

The manual drain loop used `Wire.endTransmission(false)` to write register `0xFD` (bytes-available pointer), then `Wire.requestFrom()` to read the count. When SDA was locked by the F9P, `endTransmission(false)` returned `err=4` (bus error) and the drain loop broke out immediately, reporting 0 bytes drained — giving a false impression that the buffer was empty.

---

## Troubleshooting steps taken

1. **Observed no serial output after re-flash** — confirmed the hello-world sketch still worked on the same board, ruling out a USB/serial driver issue. The silence was specific to sketches using `Wire.begin()`.

2. **Moved `Serial.println()` before `Wire.begin()`** — confirmed the sketch ran up to that point; hang occurred inside or after `Wire.begin()`.

3. **Checked SDA pin state with `digitalRead(SDA)`** — on the first boot (no prior RAWX activity), SDA read HIGH. After a run with RAWX enabled, SDA read LOW immediately at the start of setup, before any Wire call.

4. **Tested bus recovery (9-clock bit-bang + STOP)** — SDA returned HIGH after recovery, but `gnss.begin()` still failed. Root cause identified as the F9P immediately re-locking once new data arrived (the drain loop wasn't clearing the backlog fast enough or was failing outright).

5. **Added drain loop before `gnss.begin()`** — drain worked when the bus was not initially locked, clearing pending bytes and allowing `begin()` to succeed. Confirmed this was the correct mitigation.

6. **Added `gnss.setAutoRXMRAWX(false)` at end of `runVerification()`** — stopped the F9P from accumulating data between report cycles; confirmed second report cycle ran cleanly with no lockup.

---

## Fix

### `firmware/f9p_verify/f9p_verify.ino` — drain buffer before `gnss.begin()`

Added a raw I2C drain loop in `setup()` before calling `gnss.begin()`. This reads the F9P's bytes-available register (`0xFD`) in a loop and discards the data, flushing any backlogged RAWX or other messages before the library tries to communicate.

```cpp
static void drainF9P() {
  for (int iter = 0; iter < 500; iter++) {
    Wire.beginTransmission(0x42);
    Wire.write(0xFD);
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

### `firmware/f9p_verify/f9p_verify.ino` — disable RAWX after live test

Added `gnss.setAutoRXMRAWX(false)` at the end of `runVerification()` to stop the F9P from streaming RAWX data into the unread buffer during the 60-second gap between report cycles.

```cpp
// After the 5-second RAWX live test:
gnss.setAutoRXMRAWX(false);
```

### `firmware/phase1_raw_logger/phase1_raw_logger.ino` — drain buffer before `gnss.begin()`

Same drain loop added to `setup()` for resilience during field restarts where the F9P has been running without a consumer.

---

## Files changed

- `firmware/f9p_verify/f9p_verify.ino` — `setup()` (drain loop), `runVerification()` (`setAutoRXMRAWX(false)`)
- `firmware/phase1_raw_logger/phase1_raw_logger.ino` — `setup()` (drain loop)
