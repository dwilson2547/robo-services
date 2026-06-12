# Hardware Reference

Ground-truth notes for hardware used across robo-services projects. Each file documents what actually works from testing — pinouts, gotchas, and verified configurations.

| File | Component | Key notes |
|------|-----------|-----------|
| [digital_dash_system_status.md](digital_dash_system_status.md) | Digital dash ESP-NOW pair (receiver + publisher) | Current firmware state, packet schema, UI modes, flashing/recovery notes |
| [ili9488_xpt2046_4in_spi_tft.md](ili9488_xpt2046_4in_spi_tft.md) | 4.0" SPI TFT (ILI9488 + XPT2046 touch) | Use raw SPI 18-bit color path; shared SPI + separate touch CS |
| [seeed_xiao_esp32_s3_c6.md](seeed_xiao_esp32_s3_c6.md) | Seeed XIAO ESP32-S3 / ESP32-C6 | Verified S3 dash pin map/FQBN and C6 FQBN baseline |
| [esp32_thing_plus.md](esp32_thing_plus.md) | SparkFun ESP32 Thing Plus | FQBN `esp32thing_plus`, Qwiic SDA=23 (not 21) |
| [zed_f9p.md](zed_f9p.md) | u-blox ZED-F9P (SparkFun breakout) | I2C 0x42, CFG-I2C-ADDRESS stores 0x84, drain before begin() |
| [tja1051_breakout.md](tja1051_breakout.md) | TJA1051 CAN transceiver | **5V device** — VCC must be 5V; `setCANPins(rx,tx)` RX-first gotcha |
| [esp32ret_xiao_s3.md](esp32ret_xiao_s3.md) | ESP32RET GVRET sniffer on XIAO ESP32-S3 | All patches, wiring, NVS wipe, SavvyCAN setup |
