# Serial Monitoring from the CLI

No `screen` or `minicom` dependency — use Python's `pyserial` directly. It's available on the system and gives precise control over timing and resets.

---

## Basic monitor (read-only)

```python
python3 -c "
import serial, time
s = serial.Serial('/dev/ttyUSB0', 115200, timeout=1)
while True:
    line = s.readline()
    if line:
        print(line.decode('utf-8', 'replace').rstrip(), flush=True)
"
```

Ctrl+C to exit.

---

## Read for a fixed duration

```python
python3 -c "
import serial, time
s = serial.Serial('/dev/ttyUSB0', 115200, timeout=1)
deadline = time.time() + 30  # read for 30 seconds
while time.time() < deadline:
    line = s.readline()
    if line:
        print(line.decode('utf-8', 'replace').rstrip(), flush=True)
s.close()
"
```

---

## Trigger an ESP32 reset via DTR, then read

`arduino-cli upload` resets the board via RTS when it finishes. If you need to capture output from the very start of `setup()`, open serial first and toggle DTR to reset the board:

```python
python3 -c "
import serial, time
s = serial.Serial('/dev/ttyUSB0', 115200, timeout=1)
s.setDTR(False); time.sleep(0.1)
s.setDTR(True);  time.sleep(0.1)
s.setDTR(False)
time.sleep(2)  # wait for boot
deadline = time.time() + 20
while time.time() < deadline:
    line = s.readline()
    if line:
        print(line.decode('utf-8', 'replace').rstrip(), flush=True)
s.close()
"
```

**Important:** open the serial port *before* uploading if you need to catch early setup() output. `arduino-cli upload` closes the port when it's done; open it immediately after. The 2-3 second `delay()` in sketch `setup()` gives you a window.

---

## Capturing output that prints in setup() (timing-safe pattern)

The reliable approach when setup() output is short-lived: save results to a `String` in `setup()` and print them repeatedly in `loop()`. Then connect whenever and catch the next cycle.

```cpp
String results;
void setup() {
    // ... do work, save to results string ...
}
void loop() {
    Serial.print(results);
    delay(5000);
}
```

---

## Common baud rates

| Device | Baud rate |
|--------|-----------|
| ESP32 sketches (this repo) | 115200 |
| u-blox ZED-F9P USB direct | 38400 (default) or 115200 |

---

## Notes

- If output is garbled (wrong baud rate), you'll see `???` characters — check that the `Serial.begin()` rate in the sketch matches.
- After an upload, `arduino-cli` resets the board via RTS. The boot messages (ROM bootloader text) appear briefly before the sketch starts — these are normal and look like `ets Jul 29 2019...`.
- On ESP32, the Serial port is available immediately after `Serial.begin()` with no host connection needed — unlike some other platforms where USB CDC requires a host to open the port.
- Don't hold the serial port open during `arduino-cli upload` — the upload will fail with "port in use".
