# NEO-M9N GPS Configuration Guide
## Getting from 1 Hz to 10 Hz for the Race Logger

---

## Why It's Stuck at 1 Hz

The NEO-M9N ships with a default measurement rate of 1000 ms (1 Hz). The sketch sends
UBX-CFG-RATE on every boot to reconfigure this, but for your first bring-up or if you
want to inspect/save the config to flash, use u-center to do it manually and then
write it to the module's BBR (battery-backed RAM) or flash so it survives a reset.

---

## Method A: u-center (Recommended for First Setup)

### 1. Connect

Connect the NEO-M9N's UART1 to a USB-UART adapter (CP2102 / CH340 / FTDI).
Open u-center 2 (free from u-blox.com). Select your COM port, baud **38400**.

> The sketch uses 38400. The module factory default is 38400 on UART1, so this
> should work out of the box. If you get garbage, try 9600 (factory fallback)
> then set it to 38400 via UBX-CFG-PRT before proceeding.

### 2. Set Measurement Rate to 10 Hz

Navigate to:
```
View → Configuration View → Rate (CFG-RATE)
```

Set:
- **Measurement period**: 100 ms
- **Navigation rate**: 1 (solution every measurement)
- **Time reference**: GPS Time

Click **Send**.

### 3. Disable Unnecessary NMEA Sentences

Each NMEA sentence at 10 Hz adds ~80–200 bytes/sec of serial traffic.
Keep only GGA (position + altitude + HDOP) and RMC (speed + heading + time).

Navigate to:
```
View → Configuration View → Messages (CFG-MSG)
```

For each message listed below, set **UART1 rate = 0** (disabled) and click Send:
- F0-01 NMEA GxGLL
- F0-02 NMEA GxGSA
- F0-03 NMEA GxGSV
- F0-05 NMEA GxVTG

For GGA and RMC, set **UART1 rate = 1** and click Send:
- F0-00 NMEA GxGGA  → rate 1
- F0-04 NMEA GxRMC  → rate 1

### 4. Save to Flash

Navigate to:
```
View → Configuration View → CFG (CFG-CFG)
```

Check **Save current configuration** and check all destination checkboxes
(BBR, Flash). Click **Send**.

This makes the 10 Hz rate survive power cycling. The sketch still sends the
UBX-CFG-RATE command on boot as a safety net in case the module loses its config.

---

## Method B: Sketch-Only (No u-center)

The sketch's `gpsSetup10Hz()` function sends all necessary UBX commands over
UART2 on every boot. This works, but the config is NOT written to flash — it's
RAM-only and resets on power loss.

To make it permanent from the sketch, add a UBX-CFG-CFG save command after the
rate commands. The byte sequence to append to `gpsSetup10Hz()`:

```cpp
// UBX-CFG-CFG: save all to BBR + Flash (devices = 0x17)
const uint8_t UBX_SAVE_CFG[] = {
  0xB5, 0x62,              // header
  0x06, 0x09,              // CFG-CFG class/id
  0x0D, 0x00,              // payload 13 bytes
  0x00, 0x00, 0x00, 0x00,  // clearMask  (nothing to clear)
  0xFF, 0xFF, 0x00, 0x00,  // saveMask   (save everything)
  0x00, 0x00, 0x00, 0x00,  // loadMask
  0x17,                    // deviceMask (BBR | Flash | EEPROM)
  0x31, 0xBF               // checksum
};
sendUBX(UBX_SAVE_CFG, sizeof(UBX_SAVE_CFG));
```

Only call this ONCE (add a SPIFFS flag so it doesn't write every boot —
flash has limited write cycles).

---

## Verifying 10 Hz Output

### In u-center
Open the Data View (View → Data View). The "Time diff" column on the
GGA row should show ~100 ms between sentences.

### On the ESP32 Serial Monitor
Add this debug line temporarily in `loop()`:
```cpp
static uint32_t lastDebug = 0;
if (gps.location.isUpdated()) {
  if (millis() - lastDebug > 5000) {
    Serial.printf("[GPS] Rate check: updated at %lu ms\n", millis());
    lastDebug = millis();
  }
}
```
You should see the message every ~100 ms (10 per second). If it's ~1000 ms,
the UBX rate command didn't take — check your TX/RX wiring and baud rate.

---

## UBX Checksum Calculation

If you need to modify any UBX packet, the checksum is 2 bytes (CK_A, CK_B)
computed over the class, id, length, and payload bytes:

```python
def ubx_checksum(payload):  # bytes after sync chars B5 62
    ck_a = ck_b = 0
    for b in payload:
        ck_a = (ck_a + b) & 0xFF
        ck_b = (ck_b + ck_a) & 0xFF
    return ck_a, ck_b
```

Example verification for UBX_RATE_10HZ:
```python
data = [0x06, 0x08, 0x06, 0x00, 0x64, 0x00, 0x01, 0x00, 0x01, 0x00]
# → CK_A = 0x7A, CK_B = 0x12  ✓
```

---

## Antenna Notes

The NEO-M9N supports multi-band (L1 + L2/E5b) with a compatible antenna.
If your magnetic patch antenna is L1-only, the module will still lock — just
using L1 GPS/GLONASS/Galileo/BeiDou constellations. For a racetrack in Utah
with a clear sky view and a decent L1 patch antenna, expect first fix in
15–45 seconds (warm) or 1–3 minutes (cold). HDOP typically settles < 1.5
once you have 8+ satellites.

The sketch considers the GPS "locked" when:
- `gps.location.isValid()` is true
- HDOP < 3.0 (hdop.value() < 300 in TinyGPS++ integer units)

You can tighten the HDOP threshold to 1.5 (150) for race-grade positioning.

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---|---|---|
| No NMEA output | Wrong baud (try 9600) | Check / set baud in CFG-PRT |
| Stuck at 1 Hz after sketch starts | TX wiring wrong (GPS not receiving UBX) | Verify GPIO17 goes to GPS RX |
| GPS lock never goes green | Antenna indoors / obstructed | Move to open sky |
| Garbage output at high rate | UART buffer overflow | Disable extra sentences as above |
| UBX commands accepted but rate reverts | Config not saved to flash | Run the save command once |
