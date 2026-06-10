# Setting CFG-I2C-ADDRESS to 0x42 moves F9P to 7-bit address 0x21

**Date:** 2026-06-10  
**Component:** `firmware/i2c_scan/i2c_scan.ino` — address correction logic; any code that writes `CFG-I2C-ADDRESS`  
**Severity:** High — silently moves the F9P to a non-default I2C address, causing all standard library `begin()` calls to fail

---

## Observed symptom

After a prior session's VALSET commands (which included `CFG-I2C-ADDRESS = 0x42`), `gnss.begin()` failed at address 0x42. An I2C address scan found a device at **0x21** instead. Connecting to 0x21 with `gnss.begin(Wire, 0x21)` succeeded and returned "HPG 1.13" firmware, confirming the F9P was alive but at the wrong address.

---

## Root cause

### CFG-I2C-ADDRESS stores the 8-bit left-shifted address, not the 7-bit address

The u-blox ZED-F9P stores `CFG-I2C-ADDRESS` (key `0x20510001`) as the **8-bit address value** — that is, the 7-bit bus address left-shifted by one bit. The factory default stored value is `0x84`, which produces a 7-bit bus address of `0x42` (`0x84 >> 1 = 0x42`).

The u-blox documentation states the "default I2C address is 0x42" and describes the allowed range as `0x07–0x78`, which is the valid 7-bit address range. This phrasing implies the register stores the 7-bit value directly. In practice the hardware interprets the register as the 8-bit form.

When we wrote `0x42` to the register:

```python
# VALSET payload — key 0x20510001, value 0x42
payload = struct.pack('<I', 0x20510001) + bytes([0x42])
```

The F9P treated `0x42` as the 8-bit address, making the 7-bit bus address `0x42 >> 1 = 0x21`. All subsequent `gnss.begin()` calls using the default address `0x42` missed the device.

---

## Troubleshooting steps taken

1. **Re-verified F9P power and Qwiic cable** — ruled out hardware fault; PPS LED was active, confirming the F9P was running.

2. **Ran full I2C address scan** (after fixing the pin issue) — found `ACK at 0x21`, not `0x42`. Noted that `0x21 << 1 = 0x42`, which pointed to an address encoding problem.

3. **Attempted `gnss.begin(Wire, 0x21)`** — succeeded, returned HPG 1.13 firmware. Confirmed the F9P was present at 7-bit address 0x21, caused by the prior VALSET.

4. **Attempted `gnss.setVal8(0x20510001, 0x84, VAL_LAYER_ALL)` from address 0x21** — the library returned `false`, but the fix actually succeeded: the F9P changed its address mid-transaction to 0x42, so the ACK arrived at the new address. The library timed out waiting for the ACK at 0x21 and returned false. A subsequent begin() at 0x42 succeeded.

5. **Read back `CFG-I2C-ADDRESS` from RAM and Flash** — both returned `0x84`, confirming the fix was applied to all layers.

---

## Fix

### `firmware/i2c_scan/i2c_scan.ino` — connect at 0x21, write 0x84, reconnect at 0x42

Connected at the shifted address 0x21, then issued a VALSET to write `0x84` to `CFG-I2C-ADDRESS`. The library returned `false` (ACK came from the new address), but the command succeeded. After the write, `gnss.begin()` at the default address `0x42` worked correctly.

```cpp
// Connected at non-default address 0x21
gnss.begin(Wire, 0x21, 3000);

// Write the correct 8-bit-shifted value back to all layers
gnss.setVal8(0x20510001, 0x84, VAL_LAYER_ALL);
// Returns false because F9P changes address before ACKing —
// but the write does succeed. Reconnect at 0x42 to confirm.
```

**Do not write `0x42` to this register.** The correct value to produce a 7-bit bus address of `0x42` is `0x84`. Preferably, do not write this key at all unless changing the address intentionally.

---

## Files changed

- `firmware/i2c_scan/i2c_scan.ino` — address scan + correction logic
