/*
 * esp32_robo_capture.ino
 * ======================
 * Production data capture firmware for Seeed Studio XIAO ESP32-S3 Sense.
 *
 * Sensors:
 *   GPS  — u-blox M9N via UART (SparkFun GNSS v3 library, UBX binary)
 *   IMU  — BNO085 via I2C (Adafruit BNO08x library)
 *   CAN  — TJA1051 via TWAI, listen-only at 500 kbps
 *   SD   — SPI microSD adapter (race mode logging)
 *
 * Modes (hardware switch on D1/GPIO2, LOW = Race):
 *   Trip  — GPS 1 Hz, IMU 10 Hz, CAN events → MQTT publish
 *   Race  — GPS 10 Hz, IMU 100 Hz, CAN events → MQTT batched + SD log
 *
 * First-boot WiFi: launches a captive-portal AP ("RoboCapture-XXXX") where
 * the user supplies SSID, password, MQTT host/port, and device name.
 * Credentials are persisted in NVS via Preferences; WiFiManager handles
 * subsequent auto-reconnect.
 *
 * Architecture: two FreeRTOS tasks.
 *   sensorTask  (Core 0, priority 3) — reads GPS/IMU/CAN, feeds queues.
 *   networkTask (Core 1, priority 1) — drains queues, batches, publishes MQTT
 *                                      and writes SD.  Also runs in loop().
 *
 * OTA: ArduinoOTA is enabled in setup() and serviced in loop().  Full OTA
 * integration (partition scheme, signing) is left as a follow-on task.
 *
 * REQUIRED LIBRARIES (Arduino Library Manager):
 *   SparkFun u-blox GNSS v3          — SparkFun Electronics
 *   Adafruit BNO08x                   — Adafruit
 *   Adafruit Unified Sensor           — Adafruit (dependency)
 *   WiFiManager                       — tzapu / tablatronix
 *   PubSubClient                      — Nick O'Leary
 *   ArduinoJson                       — Benoit Blanchon  (v7)
 *   SD / SPI                          — built-in with esp32 Arduino core
 *
 * Board target: "XIAO_ESP32S3" in the Seeed esp32 board package.
 * Wiring: see ../wiring.txt
 */

#include <Arduino.h>
#include <Wire.h>
#include <SPI.h>
#include <SD.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <SparkFun_u-blox_GNSS_v3.h>
#include <Adafruit_BNO08x.h>
#include <ArduinoOTA.h>
#include <Preferences.h>
#include "driver/twai.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

// =============================================================================
// Pin definitions — XIAO ESP32-S3 Sense
// =============================================================================
namespace pins {
  constexpr uint8_t     kSdCs      = 1;            // D0  — SD chip-select
  constexpr uint8_t     kModeSwitch = 2;            // D1  — LOW=Race, HIGH=Trip
  constexpr gpio_num_t  kCanTx     = GPIO_NUM_3;   // D2  — TWAI TX → TJA1051 TXD
  constexpr gpio_num_t  kCanRx     = GPIO_NUM_4;   // D3  — TWAI RX ← TJA1051 RXD
  constexpr uint8_t     kI2cSda    = 5;            // D4  — BNO085 SDA
  constexpr uint8_t     kI2cScl    = 6;            // D5  — BNO085 SCL
  constexpr uint8_t     kGpsTx     = 43;           // D6  — UART1 TX → M9N RXD
  constexpr uint8_t     kGpsRx     = 44;           // D7  — UART1 RX ← M9N TXD
  constexpr uint8_t     kSpiSck    = 7;            // D8  — SD SCK
  constexpr uint8_t     kSpiMiso   = 8;            // D9  — SD MISO
  constexpr uint8_t     kSpiMosi   = 9;            // D10 — SD MOSI
}

// =============================================================================
// Configuration
// =============================================================================
namespace cfg {
  // Serial
  constexpr uint32_t kSerialBaud      = 115200;

  // GPS
  constexpr uint32_t kGpsBaud         = 38400;
  constexpr uint32_t kGpsBaudFactory  = 9600;    // M9N default baud
  constexpr uint8_t  kTripGpsHz       = 1;
  constexpr uint8_t  kRaceGpsHz       = 10;

  // IMU
  constexpr uint8_t  kBno085Addr      = 0x4A;
  constexpr uint32_t kI2cFreqHz       = 400000;
  constexpr uint32_t kTripImuHz       = 10;
  constexpr uint32_t kRaceImuHz       = 100;

  // Batching (samples per MQTT message in race mode)
  constexpr size_t   kImuBatchSize    = 10;
  constexpr size_t   kCanBatchSize    = 10;
  constexpr size_t   kImuQueueDepth   = 60;   // ~600 ms headroom at 100 Hz
  constexpr size_t   kCanQueueDepth   = 100;

  // MQTT
  constexpr uint16_t kMqttPortDefault = 1883;
  constexpr uint32_t kMqttBufferBytes = 8192;
  constexpr uint32_t kMqttReconnectMs = 5000;

  // SD
  constexpr char     kSdFilePrefix[]  = "/robo_";

  // Preferences / NVS
  constexpr char     kPrefNs[]        = "robo";

  // OTA
  constexpr char     kOtaHostname[]   = "robo-capture";
}

// =============================================================================
// Types
// =============================================================================
enum class Mode : uint8_t { kTrip, kRace };

struct ImuSample {
  uint32_t uptimeMs;
  float accelX, accelY, accelZ;          // m/s²
  float gyroX,  gyroY,  gyroZ;           // rad/s
  float quatReal, quatI, quatJ, quatK;   // rotation vector (BNO085 field names)
};

struct CanFrame {
  uint32_t uptimeMs;
  uint32_t id;
  bool     extended;
  uint8_t  dlc;
  uint8_t  data[8];
};

// Snapshot of latest GPS PVT — written by GPS callback, read by network task.
struct GpsSnapshot {
  bool     valid;
  uint32_t uptimeMs;
  double   latitude;    // degrees
  double   longitude;   // degrees
  float    altitudeM;
  float    speedMps;
  float    headingDeg;
  float    pdop;
  uint8_t  satellites;
  uint8_t  fixType;
  uint16_t year;
  uint8_t  month, day, hour, minute, second;
};

// =============================================================================
// Globals
// =============================================================================

// --- Sensors ---
SFE_UBLOX_GNSS_SERIAL gnss;
HardwareSerial         gpsSerial(1);   // UART1

Adafruit_BNO08x        bno085(-1);    // -1 = no reset pin
sh2_SensorValue_t      imuEvent;

// --- Config storage ---
Preferences prefs;
char gMqttHost[64]   = "192.168.1.100";
char gMqttPort[6]    = "1883";
char gDeviceName[32] = "robo1";

// --- Device / session identity ---
char gDeviceId[13];
char gSessionId[26];

// --- Runtime state ---
// volatile uint8_t is sufficient for a single-byte read/write on ESP32-S3
// (Xtensa LX7 guarantees atomic aligned byte access).  The mutex protects
// GpsSnapshot, and sensorTask is the sole owner of sensor library calls.
volatile uint8_t gModeRaw = static_cast<uint8_t>(Mode::kTrip);
inline Mode getMode()       { return static_cast<Mode>(gModeRaw); }
inline void setMode(Mode m) { gModeRaw = static_cast<uint8_t>(m); }

// --- FreeRTOS primitives ---
QueueHandle_t     gImuQueue  = nullptr;
QueueHandle_t     gCanQueue  = nullptr;
SemaphoreHandle_t gGpsMutex  = nullptr;

// --- Latest GPS (written under gGpsMutex by GPS callback) ---
GpsSnapshot gGpsLatest = {};

// --- MQTT ---
WiFiClient   wifiClient;
PubSubClient mqtt(wifiClient);

// --- SD ---
File     gSdFile;
bool     gSdReady   = false;
uint32_t gSdFlushMs = 0;

// --- Init flags (set before tasks start) ---
bool gGnssReady = false;
bool gImuReady  = false;
bool gCanReady  = false;

// --- Sequence counters (network task only) ---
uint32_t gGpsSeq = 0;
uint32_t gImuSeq = 0;
uint32_t gCanSeq = 0;

// =============================================================================
// Helpers
// =============================================================================

void initDeviceId() {
  uint64_t mac = ESP.getEfuseMac();
  snprintf(gDeviceId, sizeof(gDeviceId), "%04X%08X",
           (uint16_t)(mac >> 32), (uint32_t)mac);
}

void initSessionId() {
  // Refined to GPS UTC time once a fix is obtained; see networkTask.
  snprintf(gSessionId, sizeof(gSessionId), "%s_%08lX", gDeviceId, millis());
}

const char* modeName() {
  return (getMode() == Mode::kRace) ? "race" : "trip";
}

Mode readModePin() {
  return (digitalRead(pins::kModeSwitch) == LOW) ? Mode::kRace : Mode::kTrip;
}

// =============================================================================
// WiFi + config portal
// =============================================================================

void loadPrefs() {
  prefs.begin(cfg::kPrefNs, true);
  prefs.getString("mqtt_host", gMqttHost, sizeof(gMqttHost));
  prefs.getString("mqtt_port", gMqttPort, sizeof(gMqttPort));
  prefs.getString("dev_name",  gDeviceName, sizeof(gDeviceName));
  prefs.end();
}

void savePrefs(const char* host, const char* port, const char* name) {
  prefs.begin(cfg::kPrefNs, false);
  prefs.putString("mqtt_host", host);
  prefs.putString("mqtt_port", port);
  prefs.putString("dev_name",  name);
  prefs.end();
}

void setupWifi() {
  WiFiManagerParameter pMqttHost("mqtt_host", "MQTT Host",       gMqttHost,   sizeof(gMqttHost));
  WiFiManagerParameter pMqttPort("mqtt_port", "MQTT Port",       gMqttPort,   sizeof(gMqttPort));
  WiFiManagerParameter pDevName ("dev_name",  "Device Name",     gDeviceName, sizeof(gDeviceName));

  WiFiManager wm;
  wm.addParameter(&pMqttHost);
  wm.addParameter(&pMqttPort);
  wm.addParameter(&pDevName);
  wm.setConfigPortalTimeout(180);  // give up after 3 min and continue offline

  char apName[24];
  snprintf(apName, sizeof(apName), "RoboCapture-%s", gDeviceId + 8);

  if (!wm.autoConnect(apName)) {
    Serial.println("[WiFi] Portal timed out — running offline");
    return;
  }

  Serial.printf("[WiFi] Connected  IP=%s\n", WiFi.localIP().toString().c_str());

  // Persist any values the user entered in the portal.
  // Ensure null termination even if source fills the buffer.
  strncpy(gMqttHost,   pMqttHost.getValue(), sizeof(gMqttHost)   - 1); gMqttHost  [sizeof(gMqttHost)   - 1] = '\0';
  strncpy(gMqttPort,   pMqttPort.getValue(), sizeof(gMqttPort)   - 1); gMqttPort  [sizeof(gMqttPort)   - 1] = '\0';
  strncpy(gDeviceName, pDevName.getValue(),  sizeof(gDeviceName) - 1); gDeviceName[sizeof(gDeviceName) - 1] = '\0';
  savePrefs(gMqttHost, gMqttPort, gDeviceName);
}

// =============================================================================
// MQTT
// =============================================================================

void setupMqtt() {
  int port = atoi(gMqttPort);
  if (port <= 0 || port > 65535) port = cfg::kMqttPortDefault;
  mqtt.setServer(gMqttHost, (uint16_t)port);
  mqtt.setBufferSize(cfg::kMqttBufferBytes);
}

bool reconnectMqtt() {
  if (mqtt.connected()) return true;
  if (WiFi.status() != WL_CONNECTED) return false;

  static uint32_t lastAttemptMs = 0;
  if (millis() - lastAttemptMs < cfg::kMqttReconnectMs) return false;
  lastAttemptMs = millis();

  if (mqtt.connect(gDeviceId)) {
    Serial.printf("[MQTT] Connected → %s\n", gMqttHost);
    return true;
  }
  return false;
}

// =============================================================================
// GPS — M9N via SparkFun GNSS v3 (UBX binary, UART1)
// =============================================================================

void onGpsPvt(UBX_NAV_PVT_data_t* pvt) {
  if (!pvt) return;

  GpsSnapshot snap;
  snap.valid      = pvt->flags.bits.gnssFixOK;
  snap.uptimeMs   = millis();
  snap.latitude   = pvt->lat  * 1e-7;
  snap.longitude  = pvt->lon  * 1e-7;
  snap.altitudeM  = pvt->hMSL * 1e-3f;
  snap.speedMps   = pvt->gSpeed * 1e-3f;
  snap.headingDeg = pvt->headMot * 1e-5f;
  snap.satellites = pvt->numSV;
  snap.fixType    = pvt->fixType;
  snap.pdop       = pvt->pDOP * 0.01f;
  snap.year       = pvt->year;
  snap.month      = pvt->month;
  snap.day        = pvt->day;
  snap.hour       = pvt->hour;
  snap.minute     = pvt->min;
  snap.second     = pvt->sec;

  if (xSemaphoreTake(gGpsMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
    gGpsLatest = snap;
    xSemaphoreGive(gGpsMutex);
  }
}

bool initGnss() {
  gpsSerial.begin(cfg::kGpsBaud, SERIAL_8N1, pins::kGpsRx, pins::kGpsTx);
  if (!gnss.begin(gpsSerial)) {
    // M9N might be at factory default baud; upgrade it then reconnect.
    Serial.println("[GPS] Not found at 38400 — trying factory 9600");
    gpsSerial.end();
    gpsSerial.begin(cfg::kGpsBaudFactory, SERIAL_8N1, pins::kGpsRx, pins::kGpsTx);
    if (!gnss.begin(gpsSerial)) {
      Serial.println("[GPS] M9N not found — check wiring");
      return false;
    }
    gnss.setSerialRate(cfg::kGpsBaud);
    gpsSerial.end();
    delay(100);
    gpsSerial.begin(cfg::kGpsBaud, SERIAL_8N1, pins::kGpsRx, pins::kGpsTx);
    if (!gnss.begin(gpsSerial)) {
      Serial.println("[GPS] M9N baud change failed");
      return false;
    }
  }

  gnss.setUART1Output(COM_TYPE_UBX);          // UBX binary only, suppress NMEA
  gnss.setAutoPVTcallbackPtr(&onGpsPvt);
  gnss.setNavigationFrequency(
      (getMode() == Mode::kRace) ? cfg::kRaceGpsHz : cfg::kTripGpsHz);
  gnss.saveConfigSelective(VAL_CFG_SUBSEC_IOPORT);

  Serial.printf("[GPS] M9N online @ %d Hz\n",
                (getMode() == Mode::kRace) ? cfg::kRaceGpsHz : cfg::kTripGpsHz);
  return true;
}

void gnssSetRate(uint8_t hz) {
  // Called only from sensorTask.
  if (!gGnssReady) return;
  gnss.setNavigationFrequency(hz);
}

// =============================================================================
// IMU — BNO085 via I2C (Adafruit BNO08x)
// =============================================================================

bool initImu() {
  if (!bno085.begin_I2C(cfg::kBno085Addr, &Wire)) {
    Serial.println("[IMU] BNO085 not found — check wiring");
    return false;
  }

  uint32_t intervalUs = (getMode() == Mode::kRace)
                        ? 1000000u / cfg::kRaceImuHz
                        : 1000000u / cfg::kTripImuHz;

  bno085.enableReport(SH2_ACCELEROMETER,        intervalUs);
  bno085.enableReport(SH2_GYROSCOPE_CALIBRATED, intervalUs);
  bno085.enableReport(SH2_ROTATION_VECTOR,      intervalUs);

  Serial.printf("[IMU] BNO085 online @ %lu Hz\n",
                (getMode() == Mode::kRace) ? cfg::kRaceImuHz : cfg::kTripImuHz);
  return true;
}

void imuSetRate(uint32_t hz) {
  // Called only from sensorTask.
  if (!gImuReady) return;
  uint32_t intervalUs = 1000000u / hz;
  bno085.enableReport(SH2_ACCELEROMETER,        intervalUs);
  bno085.enableReport(SH2_GYROSCOPE_CALIBRATED, intervalUs);
  bno085.enableReport(SH2_ROTATION_VECTOR,      intervalUs);
}

// =============================================================================
// CAN — TJA1051 via TWAI (listen-only)
// =============================================================================

bool initCan() {
  twai_general_config_t gCfg =
      TWAI_GENERAL_CONFIG_DEFAULT(pins::kCanTx, pins::kCanRx, TWAI_MODE_LISTEN_ONLY);
  twai_timing_config_t  tCfg = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t  fCfg = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  if (twai_driver_install(&gCfg, &tCfg, &fCfg) != ESP_OK) return false;
  if (twai_start() != ESP_OK) { twai_driver_uninstall(); return false; }

  Serial.println("[CAN] TWAI online @ 500 kbps listen-only");
  return true;
}

// =============================================================================
// SD card
// =============================================================================

bool initSd() {
  SPI.begin(pins::kSpiSck, pins::kSpiMiso, pins::kSpiMosi, pins::kSdCs);
  if (!SD.begin(pins::kSdCs)) {
    Serial.println("[SD] Init failed — check adapter and CS pin");
    return false;
  }

  char filename[34];
  snprintf(filename, sizeof(filename), "%s%08lX.ndjson",
           cfg::kSdFilePrefix, millis());
  gSdFile = SD.open(filename, FILE_WRITE);
  if (!gSdFile) {
    Serial.printf("[SD] Could not open %s\n", filename);
    return false;
  }
  Serial.printf("[SD] Logging to %s\n", filename);
  return true;
}

// Write one NDJSON line; caller must be the network task only.
void sdWriteLine(const char* json) {
  if (!gSdReady || !gSdFile) return;
  gSdFile.println(json);
}

// =============================================================================
// MQTT publish helpers — called exclusively from networkTask
// =============================================================================

void publishGps(const GpsSnapshot& snap) {
  char topic[52];
  snprintf(topic, sizeof(topic), "robo/%s/gps", gDeviceId);

  JsonDocument doc;
  doc["device_id"] = gDeviceId;
  doc["session"]   = gSessionId;
  doc["mode"]      = modeName();
  doc["seq"]       = gGpsSeq++;
  doc["uptime_ms"] = snap.uptimeMs;
  doc["fix_type"]  = snap.fixType;
  doc["siv"]       = snap.satellites;

  if (snap.valid) {
    // Serialize lat/lon as doubles; ArduinoJson v7 preserves full precision.
    doc["lat"]       = snap.latitude;
    doc["lon"]       = snap.longitude;
    doc["alt_m"]     = snap.altitudeM;
    doc["speed_mps"] = snap.speedMps;
    doc["heading"]   = snap.headingDeg;
    doc["pdop"]      = snap.pdop;

    char ts[22];
    snprintf(ts, sizeof(ts), "%04d-%02d-%02dT%02d:%02d:%02dZ",
             snap.year, snap.month, snap.day,
             snap.hour, snap.minute, snap.second);
    doc["gps_time"]  = ts;
  }

  String payload;
  serializeJson(doc, payload);

  if (reconnectMqtt()) {
    mqtt.publish(topic, payload.c_str());
  }
  if (gSdReady) sdWriteLine(payload.c_str());
}

void publishImuBatch(const ImuSample* batch, size_t count) {
  char topic[52];
  snprintf(topic, sizeof(topic), "robo/%s/imu", gDeviceId);

  JsonDocument doc;
  doc["device_id"] = gDeviceId;
  doc["session"]   = gSessionId;
  doc["mode"]      = modeName();
  doc["seq_start"] = gImuSeq;
  gImuSeq += count;

  JsonArray arr = doc["batch"].to<JsonArray>();
  for (size_t i = 0; i < count; i++) {
    const ImuSample& s = batch[i];
    JsonObject o = arr.add<JsonObject>();
    o["t"]  = s.uptimeMs;
    o["ax"] = s.accelX;  o["ay"] = s.accelY;  o["az"] = s.accelZ;
    o["gx"] = s.gyroX;   o["gy"] = s.gyroY;   o["gz"] = s.gyroZ;
    o["qw"] = s.quatReal;
    o["qi"] = s.quatI;   o["qj"] = s.quatJ;   o["qk"] = s.quatK;
  }

  String payload;
  serializeJson(doc, payload);

  if (reconnectMqtt()) {
    mqtt.publish(topic, payload.c_str());
  }
  if (gSdReady) sdWriteLine(payload.c_str());
}

void publishCanBatch(const CanFrame* frames, size_t count) {
  char topic[52];
  snprintf(topic, sizeof(topic), "robo/%s/can", gDeviceId);

  JsonDocument doc;
  doc["device_id"] = gDeviceId;
  doc["session"]   = gSessionId;
  doc["mode"]      = modeName();
  doc["seq_start"] = gCanSeq;
  gCanSeq += count;

  JsonArray arr = doc["batch"].to<JsonArray>();
  for (size_t i = 0; i < count; i++) {
    const CanFrame& f = frames[i];
    JsonObject o = arr.add<JsonObject>();
    o["t"]  = f.uptimeMs;

    char idStr[12];
    snprintf(idStr, sizeof(idStr), f.extended ? "0x%08lX" : "0x%03lX", f.id);
    o["id"]  = idStr;
    o["dlc"] = f.dlc;

    char hex[17] = {};
    for (uint8_t b = 0; b < f.dlc && b < 8; b++) {
      snprintf(hex + b * 2, 3, "%02X", f.data[b]);
    }
    o["data"] = hex;
  }

  String payload;
  serializeJson(doc, payload);

  if (reconnectMqtt()) {
    mqtt.publish(topic, payload.c_str());
  }
  if (gSdReady) sdWriteLine(payload.c_str());
}

// =============================================================================
// FreeRTOS sensor task — Core 0, priority 3
// Reads GPS/IMU/CAN and feeds the inter-task queues.
// =============================================================================
void sensorTask(void* /*param*/) {
  // IMU report accumulator: BNO085 streams accel/gyro/rotation as separate
  // events.  Accumulate all three then emit one combined sample.
  ImuSample pending = {};
  bool hasAccel = false, hasGyro = false, hasQuat = false;

  // sensorTask is the sole owner of gnss and bno085 library calls.
  // When loop() changes gModeRaw, sensorTask detects it here and applies
  // rate updates to the sensor objects — no cross-core library access.
  Mode lastTaskMode = getMode();

  for (;;) {
    // --- Mode change detection (apply rate changes on this core only) ---
    Mode currentMode = getMode();
    if (currentMode != lastTaskMode) {
      lastTaskMode = currentMode;
      gnssSetRate(currentMode == Mode::kRace ? cfg::kRaceGpsHz : cfg::kTripGpsHz);
      imuSetRate( currentMode == Mode::kRace ? cfg::kRaceImuHz : cfg::kTripImuHz);
    }
    // --- GPS ---
    if (gGnssReady) {
      gnss.checkUblox();
      gnss.checkCallbacks();
    }

    // --- IMU ---
    if (gImuReady) {
      if (bno085.wasReset()) {
        // Re-enable reports after a sensor reset.
        uint32_t intervalUs = (getMode() == Mode::kRace)
                              ? 1000000u / cfg::kRaceImuHz
                              : 1000000u / cfg::kTripImuHz;
        bno085.enableReport(SH2_ACCELEROMETER,        intervalUs);
        bno085.enableReport(SH2_GYROSCOPE_CALIBRATED, intervalUs);
        bno085.enableReport(SH2_ROTATION_VECTOR,      intervalUs);
      }

      // Drain all queued BNO085 events.
      while (bno085.getSensorEvent(&imuEvent)) {
        switch (imuEvent.sensorId) {
          case SH2_ACCELEROMETER:
            pending.uptimeMs = millis();
            pending.accelX   = imuEvent.un.accelerometer.x;
            pending.accelY   = imuEvent.un.accelerometer.y;
            pending.accelZ   = imuEvent.un.accelerometer.z;
            hasAccel = true;
            break;
          case SH2_GYROSCOPE_CALIBRATED:
            pending.gyroX = imuEvent.un.gyroscope.x;
            pending.gyroY = imuEvent.un.gyroscope.y;
            pending.gyroZ = imuEvent.un.gyroscope.z;
            hasGyro = true;
            break;
          case SH2_ROTATION_VECTOR:
            pending.quatReal = imuEvent.un.rotationVector.real;
            pending.quatI    = imuEvent.un.rotationVector.i;
            pending.quatJ    = imuEvent.un.rotationVector.j;
            pending.quatK    = imuEvent.un.rotationVector.k;
            hasQuat = true;
            break;
          default:
            break;
        }

        if (hasAccel && hasGyro && hasQuat) {
          // Drop oldest sample if queue is full rather than blocking.
          if (xQueueSend(gImuQueue, &pending, 0) != pdTRUE) {
            ImuSample dropped;
            xQueueReceive(gImuQueue, &dropped, 0);
            xQueueSend(gImuQueue, &pending, 0);
          }
          hasAccel = hasGyro = hasQuat = false;
        }
      }
    }

    // --- CAN ---
    if (gCanReady) {
      twai_message_t twaiMsg;
      while (twai_receive(&twaiMsg, 0) == ESP_OK) {
        CanFrame frame;
        frame.uptimeMs = millis();
        frame.id       = twaiMsg.identifier;
        frame.extended = twaiMsg.extd;
        frame.dlc      = twaiMsg.data_length_code;
        memcpy(frame.data, twaiMsg.data, frame.dlc);
        xQueueSend(gCanQueue, &frame, 0);  // drop if full
      }
    }

    vTaskDelay(1);  // yield one RTOS tick (1 ms)
  }
}

// =============================================================================
// FreeRTOS network task — Core 1, priority 1
// Drains sensor queues, batches, publishes MQTT, writes SD, flushes SD.
// =============================================================================
void networkTask(void* /*param*/) {
  ImuSample imuBatch[cfg::kImuBatchSize];
  CanFrame  canBatch[cfg::kCanBatchSize];
  size_t    imuCount = 0;
  size_t    canCount = 0;

  uint32_t lastGpsPublishMs  = 0;
  uint32_t lastBatchFlushMs  = 0;
  uint32_t lastSdFlushMs     = 0;

  for (;;) {
    // Keep MQTT connection alive.
    if (WiFi.status() == WL_CONNECTED) {
      reconnectMqtt();
      mqtt.loop();
    }

    // --- GPS publish at configured rate ---
    const uint32_t gpsIntervalMs =
        (getMode() == Mode::kRace) ? (1000u / cfg::kRaceGpsHz)
                                   : (1000u / cfg::kTripGpsHz);

    if (millis() - lastGpsPublishMs >= gpsIntervalMs) {
      GpsSnapshot snap = {};
      bool gotSnap = false;
      if (xSemaphoreTake(gGpsMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
        snap = gGpsLatest;
        gotSnap = true;
        xSemaphoreGive(gGpsMutex);
      }
      if (gotSnap && (snap.valid || snap.uptimeMs > 0)) {
        publishGps(snap);
      }
      lastGpsPublishMs = millis();
    }

    // --- Drain IMU queue into batch ---
    ImuSample s;
    while (xQueueReceive(gImuQueue, &s, 0) == pdTRUE) {
      imuBatch[imuCount++] = s;
      if (imuCount >= cfg::kImuBatchSize) {
        publishImuBatch(imuBatch, imuCount);
        imuCount = 0;
      }
    }

    // --- Drain CAN queue into batch ---
    CanFrame f;
    while (xQueueReceive(gCanQueue, &f, 0) == pdTRUE) {
      canBatch[canCount++] = f;
      if (canCount >= cfg::kCanBatchSize) {
        publishCanBatch(canBatch, canCount);
        canCount = 0;
      }
    }

    // Flush partial batches every 500 ms so data doesn't sit in the buffer.
    if (millis() - lastBatchFlushMs >= 500) {
      if (imuCount > 0) { publishImuBatch(imuBatch, imuCount); imuCount = 0; }
      if (canCount > 0) { publishCanBatch(canBatch, canCount); canCount = 0; }
      lastBatchFlushMs = millis();
    }

    // Flush SD file every 5 s to bound data loss on power failure.
    if (gSdReady && millis() - lastSdFlushMs >= 5000) {
      gSdFile.flush();
      lastSdFlushMs = millis();
    }

    vTaskDelay(5);
  }
}

// =============================================================================
// setup()
// =============================================================================
void setup() {
  Serial.begin(cfg::kSerialBaud);
  delay(1000);
  Serial.println("\n=== Robo Capture  |  XIAO ESP32-S3 Sense ===");

  initDeviceId();
  initSessionId();
  Serial.printf("Device: %s  Session: %s\n", gDeviceId, gSessionId);

  pinMode(pins::kModeSwitch, INPUT_PULLUP);
  gModeRaw = static_cast<uint8_t>(readModePin());
  Serial.printf("Mode:   %s\n", modeName());

  Wire.begin(pins::kI2cSda, pins::kI2cScl);
  Wire.setClock(cfg::kI2cFreqHz);

  loadPrefs();
  setupWifi();
  setupMqtt();

  ArduinoOTA.setHostname(cfg::kOtaHostname);
  ArduinoOTA.begin();

  // FreeRTOS primitives (safe to create here — scheduler is already running).
  gGpsMutex = xSemaphoreCreateMutex();
  gImuQueue = xQueueCreate(cfg::kImuQueueDepth, sizeof(ImuSample));
  gCanQueue = xQueueCreate(cfg::kCanQueueDepth, sizeof(CanFrame));

  // Initialise sensors; flags are read by tasks immediately after creation.
  gGnssReady = initGnss();
  gImuReady  = initImu();
  gCanReady  = initCan();
  gSdReady   = initSd();

  // Sensor task on Core 0 at high priority so sensor reads are never starved.
  // Network task on Core 1 at lower priority alongside the Arduino loop task.
  xTaskCreatePinnedToCore(sensorTask,  "sensor",  8192, nullptr, 3, nullptr, 0);
  xTaskCreatePinnedToCore(networkTask, "network", 8192, nullptr, 1, nullptr, 1);

  Serial.printf("\nSensors — GPS:%s  IMU:%s  CAN:%s  SD:%s\n",
                gGnssReady ? "OK" : "FAIL",
                gImuReady  ? "OK" : "FAIL",
                gCanReady  ? "OK" : "FAIL",
                gSdReady   ? "OK" : "FAIL");
  Serial.println("Running.\n");
}

// =============================================================================
// loop() — OTA + mode-switch detection only.
// Sensor rate changes are handled inside sensorTask when it detects a mode
// transition, so no sensor library calls are made from this core.
// =============================================================================
void loop() {
  ArduinoOTA.handle();

  // Debounce + detect mode switch transitions.
  static Mode     lastMode   = getMode();
  static uint32_t debounceMs = 0;
  Mode currentMode = readModePin();
  if (currentMode != lastMode) {
    if (millis() - debounceMs > 200) {
      lastMode   = currentMode;
      setMode(currentMode);      // sensorTask will pick this up on its next tick
      debounceMs = millis();
      Serial.printf("[MODE] → %s\n", modeName());
    }
  } else {
    debounceMs = millis();
  }

  delay(100);
}
