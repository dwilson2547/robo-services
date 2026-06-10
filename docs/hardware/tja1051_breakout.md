# TJA1051 CAN Bus Transceiver Breakout

**IC:** NXP TJA1051T  
**Function:** CAN bus transceiver — converts between single-ended MCU logic (TX/RX) and the differential CAN bus (CANH/CANL)

---

## Power — THIS IS A 5V DEVICE

**VCC must be 5V.** Do not wire VCC to a 3.3V rail. The TJA1051T requires a 4.5V–5.5V supply to operate.

Agents consistently make the mistake of wiring VCC to the ESP32's 3.3V pin. This will not work — the transceiver will either not operate or will produce incorrect bus levels.

Use the **5V (VUSB) pin** on the ESP32 Thing Plus when USB is connected, or a separate regulated 5V supply.

---

## Logic levels

The TX and STB pins on the TJA1051T are 5V logic. When interfacing with a 3.3V MCU (e.g. ESP32):

- **MCU TX → TJA1051 TX:** requires level shifting from 3.3V → 5V, OR use a 3.3V-compatible variant (TJA1051T/3)
- **TJA1051 RX → MCU RX:** the RXD output can be fed directly to a 3.3V MCU input if the MCU pin is 5V-tolerant, or use a level shifter
- **STB pin:** tie LOW (to GND) for normal operation; HIGH puts the transceiver in low-power standby mode

If your breakout is labeled "TJA1051" without a "/3" suffix, assume 5V logic and use level shifters.

---

## Pinout (typical breakout)

| Pin | Description |
|-----|-------------|
| VCC | 5V supply |
| GND | Ground |
| TX | CAN TX from MCU (5V logic) |
| RX | CAN RX to MCU (5V logic out) |
| STB | Standby — tie to GND for normal operation |
| CANH | CAN bus HIGH (differential) |
| CANL | CAN bus LOW (differential) |

---

## CAN bus termination

Each end of a CAN bus segment requires a **120Ω termination resistor** between CANH and CANL. If you have exactly two nodes, both ends need termination. If adding to an existing bus, do not add termination unless you are at a physical end of the bus.

---

## Usage with ESP32

The ESP32 has a built-in CAN/TWAI peripheral on any two configurable GPIOs. Wire as:

```
ESP32 GPIO (TX)  →  [level shifter 3.3V→5V]  →  TJA1051 TX
ESP32 GPIO (RX)  ←  [level shifter 5V→3.3V]  ←  TJA1051 RX
TJA1051 VCC      →  5V (VUSB pin on Thing Plus, not 3V3)
TJA1051 GND      →  GND
TJA1051 STB      →  GND (normal operation)
```

In Arduino/ESP-IDF, use the TWAI driver. Library option: `autowp/autowp-mcp2515` is for SPI-based transceivers; for the native ESP32 TWAI peripheral, use `ESP32 TWAI` or the IDF `driver/twai.h` directly.
