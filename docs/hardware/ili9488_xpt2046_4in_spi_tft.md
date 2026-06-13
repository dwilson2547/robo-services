# 4.0" SPI TFT Module (ILI9488 + XPT2046 Touch)

Ground-truth notes from the `capture_device_configs/digital_dash` bring-up on XIAO ESP32-S3.

---

## Module profile

- **Panel:** ILI9488-compatible TFT, 480x320
- **Touch controller:** XPT2046 (resistive touch)
- **SPI topology:** TFT and touch share MOSI/MISO/SCLK, each with its own CS

---

## Verified wiring (XIAO ESP32-S3 dash receiver)

From `dash_receiver.ino`:

| Signal | XIAO pin | Code define |
|---|---|---|
| TFT MOSI | D10 | `PIN_TFT_MOSI 9` |
| TFT MISO | D9 | `PIN_TFT_MISO 8` |
| TFT SCLK | D8 | `PIN_TFT_SCLK 7` |
| TFT CS | D3 | `PIN_TFT_CS 4` |
| TFT DC | D1 | `PIN_TFT_DC 2` |
| TFT RST | D2 | `PIN_TFT_RST 3` |
| Backlight | D0 | `PIN_BL 1` |
| Touch CS | D4 | `PIN_TOUCH_CS 5` |

Touch uses the **same SPI clock/data lines** as TFT; only CS differs.

---

## Display driver behavior that matters

- On this hardware combo, a TFT_eSPI-based path repeatedly crashed during init/draw.
- Stable path is **raw SPI** ILI9488 commands with:
  - `COLMOD = 0x66` (18-bit pixel format)
  - 3-byte RGB writes per pixel (expanded from RGB565)
  - practical SPI clock at 40 MHz (`SPISettings(40000000, ...)`)

Reference sketches:
- `capture_device_configs/digital_dash/minimal_display_test.ino` (first known-stable proof)
- `capture_device_configs/digital_dash/dash_receiver.ino` (production path)

---

## Touch mapping notes

Working transform in receiver:

- `TOUCH_SWAP_XY = 1`
- `TOUCH_INVERT_X = 0`
- `TOUCH_INVERT_Y = 0`
- Raw clamp range near `200..3900` on both axes

If touch appears mirrored/rotated on another panel batch, adjust those flags first before changing UI logic.

---

## Known gotchas

- If display fills only partially or colors look wrong, verify ILI9488 is running in 18-bit mode (`0x66`) and pixel pushes are 3-byte.
- If touch is dead but display works, check touch CS and shared SPI clock wiring first; many issues are wiring/CS conflicts, not rendering code.
