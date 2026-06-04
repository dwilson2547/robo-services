/*
 * esp32_can_logger.ino  —  CAN field logger (shippable revision)
 * ===============================================================
 * Passive CAN bus data logger for Seeed Studio XIAO ESP32-S3 + TJA1051
 * + discrete SPI MicroSD module.
 *
 * Changes from Impala test build:
 *   - CAN silence watchdog: 10 s of no frames triggers clean shutdown
 *     (flush → close → halt). Designed for use with a 2200µF cap on the
 *     5V rail + Schottky diode — cap buffers any in-flight SD write on
 *     sudden unplug; watchdog handles the normal ignition-off case.
 *   - File naming: scans for first available CAN_NNN.TXT rather than
 *     counting all files, so other files on the card don't corrupt the
 *     index.
 *   - Shutdown path unified — both watchdog halt and fatal error go
 *     through the same safeShutdown() so the file is always closed cleanly.
 *
 * Power architecture (external):
 *   OBD Pin 16 → 5V/3A buck → 1N5817 Schottky → 2200µF 16V cap → XIAO 5V
 *   The diode prevents reverse drain into the buck on power loss.
 *   The cap holds the rail up long enough to finish any active SD write.
 *
 * On power-up:
 *   1. Initialises the SD card and opens a new uniquely-numbered log file
 *      (CAN_000.TXT … CAN_999.TXT) so previous sessions are never overwritten.
 *   2. Starts TWAI in listen-only mode (no ACKs, no bus disturbance).
 *   3. Logs every raw CAN frame (millis timestamp + hex bytes) to SD.
 *   4. Flushes to SD every 2 s — at most one flush interval of frames can
 *      be lost on sudden power removal.
 *   5. After 10 s of CAN silence, performs a clean flush + close + halt.
 *      On the next ignition cycle, power cycles the device and a new
 *      session file is opened automatically.
 *   6. Signals status via the onboard LED (GPIO21, active-LOW):
 *        · 3 quick blinks at boot   →  SD + CAN ready
 *        · 1 brief blink per second →  logging normally
 *        · SOS pattern (looping)    →  fatal error (SD fail / SD full /
 *                                       no log slot / CAN init fail)
 *        · 5 slow blinks            →  clean watchdog shutdown
 *
 * Set kDebugSerial = true to also mirror frames to Serial (bench/dev use).
 * Leave it false in field deployments — per-frame Serial output at
 * 115200 baud can stall the receive loop on a busy bus.
 *
 * Board:  Seeed Studio XIAO ESP32-C6
 */

#include <Arduino.h>
#include <SPI.h>
#include <SD.h>
#include "driver/twai.h"

namespace {

// ---------------------------------------------------------------------------
// Pin config — Seeed Studio XIAO ESP32-C6
// ---------------------------------------------------------------------------
constexpr gpio_num_t kCanTxPin  = GPIO_NUM_0;   // D0  → TJA1051 TXD
constexpr gpio_num_t kCanRxPin  = GPIO_NUM_1;   // D1  ← TJA1051 RXD

constexpr uint8_t kSdCsPin   = 16;  // D6 / GPIO16
constexpr uint8_t kSdSckPin  = 19;  // D8 / GPIO19
constexpr uint8_t kSdMisoPin = 20;  // D9 / GPIO20
constexpr uint8_t kSdMosiPin = 18;  // D10 / GPIO18

constexpr uint8_t kLedPin = 15;     // Onboard LED — active LOW

// ---------------------------------------------------------------------------
// Tuning constants
// ---------------------------------------------------------------------------
constexpr bool     kDebugSerial      = false;
constexpr uint32_t kTwaiRxQueueLen   = 64;
constexpr uint32_t kFlushIntervalMs  = 2000;    // Flush SD every 2 s
constexpr uint32_t kSilenceTimeoutMs = 10000;   // 10 s no frames → shutdown
constexpr uint64_t kMinFreeBytes     = 50ULL * 1024 * 1024;  // 50 MB

// ---------------------------------------------------------------------------
// Runtime state
// ---------------------------------------------------------------------------
bool     gCanReady   = false;
bool     gSdReady    = false;
bool     gFatal      = false;
bool     gShutdown   = false;   // Set when watchdog fires — halts the loop
File     gLogFile;
uint32_t gFrameCount       = 0;
uint32_t gLastFrameMs      = 0;  // Tracks last received frame time for watchdog

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

void blinkSos() {
  blinkN(3, 150, 150);  delay(300);
  blinkN(3, 450, 150);  delay(300);
  blinkN(3, 150, 150);  delay(700);
}

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
// Unified shutdown — called by both the watchdog and fatal error paths.
// Flushes and closes the log file, stops TWAI, then halts.
// The cap holds the rail long enough for this to complete on sudden unplug.
// ---------------------------------------------------------------------------
void safeShutdown(bool fatal) {
  if (gLogFile) {
    gLogFile.flush();
    gLogFile.close();
  }
  if (gCanReady) {
    twai_stop();
    twai_driver_uninstall();
    gCanReady = false;
  }

  if (fatal) {
    gFatal = true;
    // SOS loop — stays here forever, device must be power cycled
    while (true) blinkSos();
  } else {
    // Clean watchdog shutdown — 5 slow blinks then halt
    gShutdown = true;
    blinkN(5, 400, 300);
    ledOff();
    while (true) delay(1000);  // Halt — power cycle starts a new session
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

// Scan for first available CAN_NNN.TXT slot.
// Checks by name existence rather than counting all files — immune to
// other files or filesystem artifacts on the card corrupting the index.
bool openLogFile() {
  char name[16];
  for (uint16_t i = 0; i <= 999; i++) {
    snprintf(name, sizeof(name), "/CAN_%03u.TXT", i);
    if (!SD.exists(name)) {
      gLogFile = SD.open(name, FILE_WRITE);
      if (gLogFile) {
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
// Frame logging
// ---------------------------------------------------------------------------
void logFrame(const twai_message_t& msg) {
  ++gFrameCount;

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
      safeShutdown(true);
    } else if (!openLogFile()) {
      safeShutdown(true);
    }
  } else {
    safeShutdown(true);
  }

  gCanReady = initCan();
  if (!gCanReady) safeShutdown(true);

  gLastFrameMs = millis();  // Arm the watchdog from boot
  blinkN(3);
  Serial.println("[OK] CAN logger running");
}

void loop() {
  // Both fatal and clean shutdown paths halt inside safeShutdown(),
  // so these should never be reached — defensive guard only.
  if (gFatal || gShutdown) return;

  twai_message_t msg;
  const esp_err_t rc = twai_receive(&msg, pdMS_TO_TICKS(10));

  if (rc == ESP_OK) {
    gLastFrameMs = millis();  // Reset watchdog on every received frame
    logFrame(msg);
  } else if (rc == ESP_ERR_INVALID_STATE) {
    twai_stop();
    delay(100);
    if (twai_start() != ESP_OK) {
      Serial.println("[ERR] TWAI restart failed");
      safeShutdown(true);
      return;
    }
  }

  // Silence watchdog — clean shutdown after 10 s of no CAN traffic
  if (millis() - gLastFrameMs > kSilenceTimeoutMs) {
    Serial.printf("[OK] CAN silent for %u s — shutting down (%lu frames logged)\n",
                  kSilenceTimeoutMs / 1000, gFrameCount);
    safeShutdown(false);
    return;
  }

  // Periodic flush + free space check
  static uint32_t lastFlushMs = 0;
  if (millis() - lastFlushMs >= kFlushIntervalMs) {
    lastFlushMs = millis();
    if (gLogFile) gLogFile.flush();

    if (sdFreeBytes() < kMinFreeBytes) {
      Serial.printf("[WARN] SD nearly full — stopping log (%llu MB free)\n",
                    sdFreeBytes() / (1024 * 1024));
      safeShutdown(true);
      return;
    }
  }

  updateHeartbeat();
}
