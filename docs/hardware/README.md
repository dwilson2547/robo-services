# Hardware Reference

Ground-truth notes for hardware used across robo-services projects. Each file documents what actually works from testing — pinouts, gotchas, and verified configurations.

| File | Component | Key notes |
|------|-----------|-----------|
| [esp32_thing_plus.md](esp32_thing_plus.md) | SparkFun ESP32 Thing Plus | FQBN `esp32thing_plus`, Qwiic SDA=23 (not 21) |
| [zed_f9p.md](zed_f9p.md) | u-blox ZED-F9P (SparkFun breakout) | I2C 0x42, CFG-I2C-ADDRESS stores 0x84, drain before begin() |
| [tja1051_breakout.md](tja1051_breakout.md) | TJA1051 CAN transceiver | **5V device** — VCC must be 5V, not 3.3V |
