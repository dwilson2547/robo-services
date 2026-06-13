# SIMCom SIM7600NA-H LTE Module (Waveshare breakout)

**IC:** SIMCom SIM7600NA-H  
**Variant:** North America (NA) — do not substitute with SIM7600G-H or SIM7600E-H, which have different band sets  
**Category:** LTE Cat-4  
**Purchased from:** Waveshare (~$75)  
**Intended use:** race_logger — replacing phone-hotspot WiFi dependency with independent LTE for MQTT telemetry  
**Status (2026-06-12):** Ordered × 2, not yet integrated. Sections marked *[TBD — verify on hardware]* need filling in once wired.

---

## Why this variant

T-Mobile and Google Fi (which roams on T-Mobile) require **Band 71 (600 MHz)** for reliable coverage outside dense urban areas. The SIM7600NA-H is one of the few mid-price Cat-4 modules that explicitly includes B71 in its North America band set.

Band list confirmed from SIMCom datasheet:

| Radio | Bands |
|-------|-------|
| LTE-FDD | B2, B4, B5, B12, B13, B14, B25, B26, B66, **B71** |
| LTE-TDD | B38, B40, B41 |
| GNSS | GPS, GLONASS, BeiDou, Galileo |

Cat-4 (150 Mbps / 50 Mbps) is overkill for MQTT telemetry but has no downside. The module also has an onboard voltage regulator — the Waveshare board does not draw directly from the host MCU's 3.3V rail.

---

## Power

The Waveshare board includes its own regulator. Supply **5V to the board's VIN** (not 3.3V).

LTE modules draw large current bursts during transmission (up to ~2A peak). Do not power through the ESP32 Thing Plus's onboard regulator. Use a dedicated 5V rail with adequate current capacity.

*[TBD — measure actual idle and TX peak current in circuit and document here]*

---

## UART interface

The SIM7600NA-H communicates via standard SIMCom AT commands over UART. Default baud rate is **115200**.

**TinyGSM** (`vshymanskyy/TinyGSM`) supports SIM7600 series — use `TinyGsmClientSIM7600` modem type.

Wiring to ESP32 Thing Plus *[TBD — confirm GPIO assignments and document here once tested]*:

```
SIM7600NA-H TX  →  ESP32 RX (e.g. Serial2 RX)
SIM7600NA-H RX  →  ESP32 TX (e.g. Serial2 TX)
SIM7600NA-H VIN →  5V rail
SIM7600NA-H GND →  GND
```

Logic levels: SIMCom UART pins are 2.8V logic on the bare IC. Verify whether the Waveshare board level-shifts to 3.3V for the header pins. *[TBD — measure with multimeter and document here. If 2.8V, add a level shifter or check ESP32 pin tolerances.]*

---

## AT command basics

```
AT              → OK                   (module alive)
AT+CPIN?        → +CPIN: READY         (SIM inserted and unlocked)
AT+CREG?        → +CREG: 0,1           (registered, home network)
AT+COPS?        → +COPS: 0,0,"T-Mobile" (carrier)
AT+CPSI?        → full network info including band in use
```

To check which LTE band is active:
```
AT+CPSI?
→ LTE,Online,310-260,0x...,<band>,...
```

---

## TinyGSM integration sketch outline

```cpp
#include <TinyGsmClient.h>

#define SerialAT Serial2
#define TINY_GSM_MODEM_SIM7600

TinyGsm modem(SerialAT);
TinyGsmClient client(modem);

void setup() {
    SerialAT.begin(115200, SERIAL_8N1, RX_PIN, TX_PIN);
    modem.restart();
    modem.gprsConnect(APN, "", "");  // T-Mobile APN: "fast.t-mobile.com"
}
```

T-Mobile APN: `fast.t-mobile.com` (no username or password required).

*[TBD — fill in verified RX_PIN / TX_PIN, confirm APN, document any init quirks]*

---

## Known gotchas

*None yet — to be filled in from first integration.*

---

## Integration roadmap

1. Wire up and confirm UART comms and band registration on T-Mobile
2. Integrate TinyGSM into race_logger firmware alongside existing WiFi/BLE stack
3. Once LTE is stable, remove phone-hotspot WiFi dependency for MQTT
4. With WiFi freed up: evaluate race_logger as an AP for local dashboard ESP-NOW / WiFi-UDP link (see digital dash notes)
