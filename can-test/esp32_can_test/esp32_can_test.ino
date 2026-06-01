/*
 * esp32_can_test.ino
 * ==================
 * CAN bus verification sketch for Seeed Studio XIAO ESP32-S3 Sense + TJA1051.
 *
 * Listens on the HS-CAN bus in receive-only mode (no ACKs, no error frames
 * transmitted) and prints every received frame to Serial for analysis.
 * Also decodes a set of known GM Global A HS-CAN signals so you can
 * spot-check live data against the signal map without a DBC tool.
 *
 * No external libraries required — uses the ESP-IDF TWAI driver that ships
 * with the ESP32 Arduino core.
 *
 * Board:  Seeed Studio XIAO ESP32-S3 Sense
 * Wiring: see ../wiring.txt
 */

#include <Arduino.h>
#include "driver/twai.h"

namespace {

// ---------------------------------------------------------------------------
// Pin config
// ---------------------------------------------------------------------------
constexpr gpio_num_t kCanTxPin = GPIO_NUM_1;   // D0 — → TJA1051 TXD (pin 1)
constexpr gpio_num_t kCanRxPin = GPIO_NUM_2;   // D1 — ← TJA1051 RXD (pin 4)

// If /STB is wired to a GPIO instead of tied to GND, set kHaveStbPin = true
// and provide the pin number. LOW enables the transceiver (/STB is active-low).
constexpr bool      kHaveStbPin = false;
constexpr gpio_num_t kStbPin    = GPIO_NUM_4;  // D3 — optional /STB control

// ---------------------------------------------------------------------------
// Baud rate — change macro to TWAI_TIMING_CONFIG_250KBITS() for 250 kbps.
// 500 kbps is standard for GM GlobalA HS-CAN.
// ---------------------------------------------------------------------------
#define CAN_TIMING TWAI_TIMING_CONFIG_500KBITS()

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
bool     gCanReady   = false;
uint32_t gFrameCount = 0;
uint32_t gErrCount   = 0;

// ---------------------------------------------------------------------------
// GM Global A HS-CAN signal decoder
// Signals sourced from opendbc gm_global_a_highspeed.dbc and community docs.
// Treat output as a starting hypothesis; verify against a live capture.
// ---------------------------------------------------------------------------
void decodeGmGlobalA(uint32_t id, const uint8_t* d, uint8_t dlc) {
  switch (id) {
    case 0x0C9:  // PPEI General Engine Status 1 (DLC=8)
      if (dlc < 6) break;
      Serial.printf(
          "  [GM 0x0C9] RPM=%.0f  Throttle=%.1f%%  Brake=%s\n",
          ((uint16_t)d[0] << 8 | d[1]) * 0.25f,
          d[3] / 2.55f,
          d[4] ? "ON" : "off");
      break;

    case 0x3E9:  // Vehicle speed
      if (dlc < 2) break;
      {
        float mph = ((uint16_t)d[0] << 8 | d[1]) * 0.01f;
        Serial.printf("  [GM 0x3E9] Speed=%.2f mph (%.2f kph)\n",
                      mph, mph * 1.60934f);
      }
      break;

    case 0x135:  // Transmission gear
      if (dlc < 1) break;
      Serial.printf("  [GM 0x135] Gear=%d\n", d[0]);
      break;

    case 0x4C1:  // Coolant / IAT / ambient temps
      if (dlc < 5) break;
      Serial.printf("  [GM 0x4C1] Coolant=%dC  IAT=%dC  Ambient=%dC\n",
                    d[2] - 40, d[3] - 40, (int)(d[4] / 2) - 40);
      break;

    case 0x4D1:  // Engine oil temp
      if (dlc < 3) break;
      Serial.printf("  [GM 0x4D1] OilTemp=%dC\n", d[2] - 40);
      break;

    case 0x1EF:  // MAF
      if (dlc < 4) break;
      Serial.printf("  [GM 0x1EF] MAF=%.2f g/s\n",
                    ((uint16_t)d[2] << 8 | d[3]) / 100.0f);
      break;

    case 0x348:  // Rear wheel speeds (verify — some sources swap 0x348/0x34A)
    case 0x34A:  // Front wheel speeds
      if (dlc < 4) break;
      Serial.printf("  [GM 0x%03lX] WheelSpeeds=%.2f / %.2f mph\n", id,
                    ((uint16_t)d[0] << 8 | d[1]) * 0.01f,
                    ((uint16_t)d[2] << 8 | d[3]) * 0.01f);
      break;

    default:
      break;
  }
}

// ---------------------------------------------------------------------------
// TWAI init
// ---------------------------------------------------------------------------
bool initCan() {
  if (kHaveStbPin) {
    pinMode(kStbPin, OUTPUT);
    digitalWrite(kStbPin, LOW);  // enable transceiver
  }

  twai_general_config_t gCfg =
      TWAI_GENERAL_CONFIG_DEFAULT(kCanTxPin, kCanRxPin, TWAI_MODE_LISTEN_ONLY);
  twai_timing_config_t tCfg = CAN_TIMING;
  twai_filter_config_t fCfg = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  if (twai_driver_install(&gCfg, &tCfg, &fCfg) != ESP_OK) {
    Serial.println("[ERR] TWAI driver install failed");
    return false;
  }
  if (twai_start() != ESP_OK) {
    Serial.println("[ERR] TWAI start failed");
    twai_driver_uninstall();
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Frame printer
// ---------------------------------------------------------------------------
void printFrame(const twai_message_t& msg) {
  ++gFrameCount;
  if (msg.extd) {
    Serial.printf("[%8lu ms] EXT 0x%08lX  DLC=%u  DATA:",
                  millis(), msg.identifier, msg.data_length_code);
  } else {
    Serial.printf("[%8lu ms] STD 0x%03lX       DLC=%u  DATA:",
                  millis(), msg.identifier, msg.data_length_code);
  }
  for (uint8_t i = 0; i < msg.data_length_code; i++) {
    Serial.printf(" %02X", msg.data[i]);
  }
  Serial.println();

  if (!msg.extd && !msg.rtr) {
    decodeGmGlobalA(msg.identifier, msg.data, msg.data_length_code);
  }
}

// ---------------------------------------------------------------------------
// Periodic status summary
// ---------------------------------------------------------------------------
void printStats() {
  twai_status_info_t st;
  if (twai_get_status_info(&st) != ESP_OK) return;
  Serial.printf(
      "\n[STATS @%lu ms]  frames=%lu  errors=%lu  rxPending=%u  bus=%s\n\n",
      millis(), gFrameCount, gErrCount, st.msgs_to_rx,
      st.state == TWAI_STATE_RUNNING  ? "OK"      :
      st.state == TWAI_STATE_BUS_OFF  ? "BUS_OFF" : "ERR");
}

}  // namespace

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println("\n============================================");
  Serial.println(" ESP32 CAN Test  |  TJA1051 + TWAI");
  Serial.println(" Seeed Studio XIAO ESP32-S3 Sense");
  Serial.printf(" TX=GPIO%d (D0)  RX=GPIO%d (D1)  |  500 kbps  |  Listen-only\n",
                kCanTxPin, kCanRxPin);
  Serial.println("============================================");
  Serial.println(" Connect TJA1051 CANH→OBD pin 6, CANL→OBD pin 14");
  Serial.println(" GM GlobalA known signals will be decoded inline.");
  Serial.println("============================================\n");

  gCanReady = initCan();
  if (!gCanReady) {
    Serial.println("[FATAL] CAN init failed — check wiring and baud rate.");
  } else {
    Serial.println("Waiting for frames...\n");
  }
}

void loop() {
  if (!gCanReady) {
    delay(1000);
    return;
  }

  twai_message_t msg;
  const esp_err_t rc = twai_receive(&msg, pdMS_TO_TICKS(10));

  if (rc == ESP_OK) {
    printFrame(msg);
  } else if (rc == ESP_ERR_INVALID_STATE) {
    Serial.println("[WARN] TWAI not running — restarting");
    ++gErrCount;
    twai_stop();
    delay(100);
    twai_start();
  } else if (rc != ESP_ERR_TIMEOUT) {
    ++gErrCount;
  }

  static uint32_t lastStatsMs = 0;
  if (millis() - lastStatsMs >= 10000) {
    printStats();
    lastStatsMs = millis();
  }
}
