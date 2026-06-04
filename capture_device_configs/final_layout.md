# Race Logger OBD2 Dongle — Pin Assignment Reference
**MCU:** Seeed Studio XIAO ESP32-S3 (Sense board, expansion hat removed)  
**Last updated:** 2026-05-31

---

## Overview of Changes from v1
- Sense expansion board removed — SD card moved to discrete bare-passthrough SPI module on carrier board
- Frees GPIO7, GPIO8, GPIO9, GPIO21 from internal expansion board wiring
- UART debug pins (GPIO43/GPIO44) freed — USB-C native USB used for debug instead
- GPIO41/GPIO42 (B2B connector) no longer accessible without expansion board

---

## Pin Assignment Table

| XIAO Pin | GPIO    | Function       | Connected To               | Notes                                            |
|----------|---------|----------------|----------------------------|--------------------------------------------------|
| D4       | GPIO5   | I2C SDA        | BNO085 SDA + M9N SDA       | Shared I2C bus, 4.7kΩ pullup to 3.3V required   |
| D5       | GPIO6   | I2C SCL        | BNO085 SCL + M9N SCL       | Shared I2C bus, 4.7kΩ pullup to 3.3V required   |
| D0       | GPIO1   | TWAI TX        | TJA1051 TXD                | CAN transmit                                     |
| D1       | GPIO2   | TWAI RX        | TJA1051 RXD                | CAN receive                                      |
| D8       | GPIO7   | SD SCK         | SD module CLK              | Discrete SD module                               |
| D9       | GPIO8   | SD MISO        | SD module DO               | Discrete SD module                               |
| D10      | GPIO9   | SD MOSI        | SD module DI               | Discrete SD module                               |
| D6       | GPIO43  | SD CS          | SD module CS               | GPIO43 freed from UART — reassigned here         |
| D3       | GPIO4   | Mode switch    | Race / Trip toggle switch  | Pull to GND when active, internal pullup enabled |
| D7       | GPIO44  | Staging button | Start line button          | Pull to GND when pressed, internal pullup enabled|
| —        | GPIO21  | Status LED 1   | Onboard user LED           | **Active LOW** — lit when GPIO driven low        |
| —        | *TBD*   | Status LED 2   | External LED               | ⚠️ See GPIO shortage note below                  |
| D2       | GPIO3   | **AVOID**      | —                          | Strapping pin — leave floating or tied correctly |
| 5V       | —       | Power in       | Buck converter via 1N5819  | Schottky diode required                          |
| 3V3      | —       | Power out      | BNO085, M9N, SD module VCC | 700mA max from onboard regulator                 |
| GND      | —       | Ground         | Common ground              |                                                  |

---

## ⚠️ GPIO Shortage — Status LED 2

After assigning all peripherals and UI elements, there are no free GPIOs remaining for a second external LED. Options in order of preference:

**Option A — PCF8574 I2C GPIO expander (recommended)**  
A PCF8574 sits on the I2C bus and provides 8 additional GPIOs. ~$0.50/chip, well-supported in Arduino. Permanently solves the GPIO budget with room to spare for future features. Adds one I2C address.

**Option B — Use onboard LED as LED 1, add expander only for LED 2**  
Same as above but confirms the onboard GPIO21 LED covers one status indicator for free.

**Option C — Two LEDs on one GPIO via resistor ladder**  
Different resistor values to ground give different brightness levels per state. Crude, non-independent, not recommended for clear status indication.

**Option D — Tap GPIO41/GPIO42 from B2B connector pads**  
These pads exist on the XIAO PCB even without the expansion board. Requires soldering fine wires directly to B2B SMD pads — fiddly but possible if you want to avoid the expander.

---

## Peripheral Details

### BNO085 (IMU) — I2C
| BNO085 Pin | Connects To              |
|------------|--------------------------|
| SDA        | D4 / GPIO5               |
| SCL        | D5 / GPIO6               |
| VCC        | 3V3                      |
| GND        | GND                      |
| PS0 / PS1  | Set per datasheet for I2C mode |

> **Check breakout board for onboard pullups before adding your own to the carrier board.**

### M9N (GPS) — I2C via Qwiic
| M9N Pin | Connects To               |
|---------|---------------------------|
| SDA     | D4 / GPIO5 (shared bus)   |
| SCL     | D5 / GPIO6 (shared bus)   |
| VCC     | 3V3                       |
| GND     | GND                       |

> Qwiic = JST-SH 4-pin = I2C. Use Qwiic-to-dupont adapter or add Qwiic footprint to carrier board. Verify I2C address does not conflict with BNO085.

### TJA1051 (CAN Transceiver) — TWAI
| TJA1051 Pin | Connects To   |
|-------------|---------------|
| TXD         | D0 / GPIO1    |
| RXD         | D1 / GPIO2    |
| VCC         | **5V rail**   |
| GND         | GND           |
| CANH        | OBD2 Pin 6    |
| CANL        | OBD2 Pin 14   |
| S (silent)  | GND           |

> **Critical:** TJA1051 requires 5V. Running on 3.3V was the v1 prototype bug.

### MicroSD — Discrete SPI Module (bare passthrough, no level shifting)
| SD Module Pin | Connects To     |
|---------------|-----------------|
| VCC           | 3V3             |
| GND           | GND             |
| CLK           | D8 / GPIO7      |
| MISO (DO)     | D9 / GPIO8      |
| MOSI (DI)     | D10 / GPIO9     |
| CS            | D6 / GPIO43     |

> Use 3.3V-native bare passthrough module only — level shifting modules designed for 5V Arduino systems carry dead circuitry at 3.3V. Power from 3.3V rail.

### User Interface
| Element        | GPIO    | XIAO Pin | Behavior                              |
|----------------|---------|----------|---------------------------------------|
| Mode switch    | GPIO4   | D3       | Race/Trip toggle — active low, internal pullup |
| Staging button | GPIO44  | D7       | Momentary — active low, internal pullup       |
| Status LED 1   | GPIO21  | —        | Onboard LED — **active LOW**, driven directly |
| Status LED 2   | TBD     | —        | Pending GPIO expander decision                |

---

## Power Section

| Net  | Source                                    | Consumers                          |
|------|-------------------------------------------|------------------------------------|
| 12V  | OBD2 Pin 16                               | Buck converter input               |
| 5V   | Potted automotive buck (≤35V in)          | TJA1051 VCC, XIAO 5V pin          |
| 3.3V | XIAO onboard regulator (700mA max)        | BNO085, M9N, SD module, logic      |
| GND  | OBD2 Pins 4 & 5                           | Common ground                      |

**1N5819 Schottky** required in series: anode → buck output, cathode → XIAO 5V pin.  
Prevents backfeed when USB-C also connected during development.

---

## OBD2 Connector Pinout (Relevant Pins)

| OBD2 Pin | Function    | Connects To          |
|----------|-------------|----------------------|
| 4        | Chassis GND | Common GND           |
| 5        | Signal GND  | Common GND           |
| 6        | CAN High    | TJA1051 CANH         |
| 14       | CAN Low     | TJA1051 CANL         |
| 16       | 12V+        | Buck converter input |

---

## I2C Bus Notes

- **Pullups:** 4.7kΩ from SDA and SCL to 3.3V, placed once on carrier board near XIAO
- **Check breakout boards:** Many modules include onboard pullups. Multiple pullups in parallel lower effective resistance and degrade signal integrity. Confirm or remove before assembling
- **Wire timeout:** Add `Wire.setTimeOut(1000)` in firmware as defensive measure against bus lockup in vehicle EMI environment
- **Without pullups:** Bus may limp along using weak internal pullups (~45kΩ) but will lock up under load or noise — this was the root cause of the v1 freeze/lockup behavior

---

## Pins to Avoid

| GPIO   | Reason                                                |
|--------|-------------------------------------------------------|
| GPIO3  | Strapping pin — avoid                                 |
| GPIO0  | Strapping pin — not exposed on headers                |

---

## Recommended Test Points (carrier PCB)

| TP  | Net      | Purpose                           |
|-----|----------|-----------------------------------|
| TP1 | 5V       | Verify buck output post-diode     |
| TP2 | 3.3V     | Verify XIAO regulator output      |
| TP3 | I2C SDA  | Scope I2C traffic                 |
| TP4 | I2C SCL  | Scope I2C clock                   |
| TP5 | TWAI TX  | CAN transmit debug                |
| TP6 | TWAI RX  | CAN receive debug                 |
| TP7 | GND      | Reference                         |