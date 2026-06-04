/*
 * esp32_can_test.ino  —  CAN field logger
 * =========================================
 * Passive CAN bus data logger for Seeed Studio XIAO ESP32-S3 + TJA1051
 * + discrete SPI MicroSD module.
 *
 * On power-up:
 *   1. Initialises the SD card and opens a new uniquely-numbered log file
 *      (CAN_000.TXT … CAN_999.TXT) so previous captures are never overwritten.
 *   2. Starts TWAI in listen-only mode (no ACKs, no bus disturbance).
 *   3. Logs every raw CAN frame (millis timestamp + hex bytes) to SD.
 *   4. Signals status via the onboard LED (GPIO21, active-LOW):
 *        · 3 quick blinks at boot   →  SD + CAN ready
 *        · 1 brief blink per second →  logging normally
 *        · SOS pattern (looping)    →  fatal error (SD fail / SD full /
 *                                       no log slot / CAN init fail)
 *
 * Pull power to stop; data is flushed to SD every 2 s so at most one
 * flush interval of frames can be lost on sudden power removal.
 *
 * Set kDebugSerial = true to also mirror frames to Serial (bench/dev use).
 * Leave it false in field deployments — per-frame Serial output at
 * 115200 baud can stall the receive loop and drop frames on a busy bus.
 *
 * No external libraries required beyond the SD + SPI libraries bundled
 * with the ESP32 Arduino core.
 *
 * Board:  Seeed Studio XIAO ESP32-S3 Sense
 * Wiring: see ../wiring.txt  and  ../../final_layout.md
 */

#include <Arduino.h>
#include <SPI.h>
#include <SD.h>
#include "driver/twai.h"

namespace {

// ---------------------------------------------------------------------------
// Pin config — matches final_layout.md
// ---------------------------------------------------------------------------
constexpr gpio_num_t kCanTxPin  = GPIO_NUM_1;  // D0  → TJA1051 TXD
constexpr gpio_num_t kCanRxPin  = GPIO_NUM_2;  // D1  ← TJA1051 RXD

constexpr uint8_t kSdCsPin   = 43;  // D6 / GPIO43
constexpr uint8_t kSdSckPin  = 7;   // D8 / GPIO7
constexpr uint8_t kSdMisoPin = 8;   // D9 / GPIO8
constexpr uint8_t kSdMosiPin = 9;   // D10 / GPIO9

constexpr uint8_t kLedPin = 21;     // Onboard LED — active LOW

// ---------------------------------------------------------------------------
// Tuning constants
// ---------------------------------------------------------------------------

// Mirror frames to Serial (useful on the bench; disable for field use).
constexpr bool kDebugSerial = false;

// TWAI receive queue depth — increased from the default 5 to absorb bursts
// while SD writes and flushes complete.
constexpr uint32_t kTwaiRxQueueLen = 64;

// Flush SD file to disk this often (ms). Bounds data loss on power removal.
constexpr uint32_t kFlushIntervalMs = 2000;

// Minimum free space required to start or continue logging (bytes).
// Checked once at boot and again every flush cycle.
constexpr uint64_t kMinFreeBytes = 50ULL * 1024 * 1024;  // 50 MB

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
bool     gCanReady = false;
bool     gSdReady  = false;
bool     gFatal    = false;
File     gLogFile;
uint32_t gFrameCount = 0;

// ---------------------------------------------------------------------------
// LED helpers (active-LOW)
// ---------------------------------------------------------------------------
inline void ledOn()  { digitalWrite(kLedPin, LOW);  }
inline void ledOff() { digitalWrite(kLedPin, HIGH); }

void blinkN(uint8_t n, uint32_t onMs = 80, uint32_t offMs = 120) {
  for (uint8_t i = 0; i < n; i++) {
    ledOn();  delay(onMs);
    ledOff(); delay(offMs);
  }
}

// Blocking SOS — used only after declaring fatal (loop never returns to work).
void blinkSos() {
  blinkN(3, 150, 150);  delay(300);   // · · ·
  blinkN(3, 450, 150);  delay(300);   // — — —
  blinkN(3, 150, 150);  delay(700);   // · · ·
}

// Non-blocking 1 Hz heartbeat: 50 ms on / 950 ms off.
void updateHeartbeat() {
  static uint32_t lastMs = 0;
  static bool     phase  = false;
  uint32_t now = millis();
  uint32_t interval = phase ? 50 : 950;
  if (now - lastMs >= interval) {
    lastMs = now;
    phase  = !phase;
    phase ? ledOn() : ledOff();
  }
}

// ---------------------------------------------------------------------------
// SD helpers
// ---------------------------------------------------------------------------
bool initSd() {
  SPI.begin(kSdSckPin, kSdMisoPin, kSdMosiPin, kSdCsPin);
  if (!SD.begin(kSdCsPin)) {
    Serial.println("[ERR] SD init failed");
    return false;
  }
  Serial.printf("[SD] Card: %llu MB total, %llu MB used\n",
                SD.totalBytes() / (1024 * 1024),
                SD.usedBytes()  / (1024 * 1024));
  return true;
}

uint64_t sdFreeBytes() {
  return SD.totalBytes() - SD.usedBytes();
}

// Open the first available CAN_NNN.TXT (000–999).
bool openLogFile() {
  char name[16];
  for (uint16_t i = 0; i <= 999; i++) {
    snprintf(name, sizeof(name), "/CAN_%03u.TXT", i);
    if (!SD.exists(name)) {
      gLogFile = SD.open(name, FILE_WRITE);
      if (gLogFile) {
        // Write a minimal header so the file is self-describing.
        gLogFile.printf("# CAN capture  file=%s\n", name);
        gLogFile.println("# Format: [millis_ms] STD/EXT 0xID DLC=N DATA: B0 B1 ...");
        gLogFile.flush();
        Serial.printf("[SD] Logging to %s\n", name);
        return true;
      }
    }
  }
  Serial.println("[ERR] All log slots used (CAN_000–CAN_999). Clear the card.");
  return false;
}

// ---------------------------------------------------------------------------
// TWAI init
// ---------------------------------------------------------------------------
bool initCan() {
  twai_general_config_t gCfg =
      TWAI_GENERAL_CONFIG_DEFAULT(kCanTxPin, kCanRxPin, TWAI_MODE_LISTEN_ONLY);
  gCfg.rx_queue_len = kTwaiRxQueueLen;

  twai_timing_config_t tCfg = TWAI_TIMING_CONFIG_500KBITS();
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
// Shut everything down cleanly before entering the fatal-error LED loop.
// ---------------------------------------------------------------------------
void enterFatal() {
  gFatal = true;
  if (gLogFile) {
    gLogFile.flush();
    gLogFile.close();
  }
  twai_stop();
  twai_driver_uninstall();
}

// ---------------------------------------------------------------------------
// Frame logging
// ---------------------------------------------------------------------------
void logFrame(const twai_message_t& msg) {
  ++gFrameCount;

  // Build the line in a local buffer — avoids multiple small SD writes.
  char line[80];
  int  len;
  if (msg.extd) {
    len = snprintf(line, sizeof(line), "[%8lu] EXT 0x%08lX DLC=%u DATA:",
                   millis(), msg.identifier, msg.data_length_code);
  } else {
    len = snprintf(line, sizeof(line), "[%8lu] STD 0x%03lX    DLC=%u DATA:",
                   millis(), msg.identifier, msg.data_length_code);
  }
  for (uint8_t i = 0; i < msg.data_length_code && len < 76; i++) {
    len += snprintf(line + len, sizeof(line) - len, " %02X", msg.data[i]);
  }

  if (kDebugSerial) Serial.println(line);
  if (gSdReady && gLogFile) gLogFile.println(line);
}

}  // namespace

// ---------------------------------------------------------------------------
void setup() {
  pinMode(kLedPin, OUTPUT);
  ledOff();

  Serial.begin(115200);
  delay(500);

  gSdReady = initSd();
  if (gSdReady) {
    if (sdFreeBytes() < kMinFreeBytes) {
      Serial.printf("[ERR] SD too full (free=%llu MB, need 50 MB)\n",
                    sdFreeBytes() / (1024 * 1024));
      gSdReady = false;
      enterFatal();
    } else if (!openLogFile()) {
      enterFatal();
    }
  } else {
    enterFatal();
  }

  if (!gFatal) {
    gCanReady = initCan();
    if (!gCanReady) enterFatal();
  }

  if (!gFatal) {
    blinkN(3);  // 3 quick blinks = ready
    Serial.println("[OK] CAN logger running");
  }
}

void loop() {
  if (gFatal) {
    blinkSos();
    return;
  }

  twai_message_t msg;
  const esp_err_t rc = twai_receive(&msg, pdMS_TO_TICKS(10));

  if (rc == ESP_OK) {
    logFrame(msg);
  } else if (rc == ESP_ERR_INVALID_STATE) {
    // Bus error — attempt recovery
    twai_stop();
    delay(100);
    if (twai_start() != ESP_OK) {
      Serial.println("[ERR] TWAI restart failed");
      enterFatal();
      return;
    }
  }

  static uint32_t lastFlushMs = 0;
  if (millis() - lastFlushMs >= kFlushIntervalMs) {
    lastFlushMs = millis();
    if (gLogFile) gLogFile.flush();

    // Check remaining space every flush cycle.
    if (sdFreeBytes() < kMinFreeBytes) {
      Serial.printf("[WARN] SD nearly full — stopping log (%llu MB free)\n",
                    sdFreeBytes() / (1024 * 1024));
      enterFatal();
      return;
    }
  }

  updateHeartbeat();
}
