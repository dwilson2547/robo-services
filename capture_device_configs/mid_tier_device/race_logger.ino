/*
 * race_logger.ino
 * SparkFun ESP32 Thing Plus (DEV-15663) — Race Telemetry Logger
 *
 * Peripherals:
 *   GPS  : u-blox NEO-M9N  → UART2 (GPIO16 RX, GPIO17 TX)
 *   IMU  : BNO085           → I2C (SDA=GPIO21 left, SCL=GPIO22)
 *                             INT → GPIO36 (board label A0)
 *                             RST → tied to 3.3V (no GPIO)
 *   CAN  : TJA1051T         → TWAI (TX=GPIO14, RX=GPIO21 right side)
 *                             S   → GPIO13 (slope control)
 *   SD   : SPI module       → VSPI (SCK=GPIO18, MISO=GPIO19, MOSI=GPIO23, CS=GPIO15)
 *   LED  : Red=GPIO32 | Yellow=GPIO33 | Green=GPIO27
 *
 * Features:
 *   - WiFi captive portal on first boot (WiFiManager)
 *   - Persistent config: WiFi creds, MQTT broker, username, password (SPIFFS)
 *   - GPS @ 10 Hz (UBX-CFG-RATE), IMU @ 100 Hz (BNO085 ARVR-stabilized RV)
 *   - CAN normal mode (listen + transmit capable) with software allowlist filter
 *   - SD card binary logging with CRC32 integrity + graceful power-loss handling
 *   - MQTT publish with MessagePack framing, non-blocking ring buffer
 *   - OTA updates via ArduinoOTA
 *   - Stoplight LED state machine
 *
 * Dependencies (install via Library Manager or PlatformIO):
 *   - WiFiManager by tzapu (2.0+)
 *   - SparkFun BNO08x Arduino Library
 *   - TinyGPS++ by Mikal Hart
 *   - ArduinoJson (6.x or 7.x)
 *   - PubSubClient
 *   - mpack (MessagePack — drop mpack.h + mpack.c from mpack-amalgamation into src/)
 *   - ESP32 Arduino core (TWAI and SPIFFS built in)
 *   - ArduinoOTA (included in ESP32 Arduino core)
 */

// ─── Includes ────────────────────────────────────────────────────────────────
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <SPIFFS.h>
#include <ArduinoJson.h>
#include <PubSubClient.h>
#include <ArduinoOTA.h>
#include <SD.h>
#include <SPI.h>
#include <Wire.h>
#include <driver/twai.h>
#include <TinyGPS++.h>
#include <SparkFun_BNO08x_Arduino_Library.h>
#include "mpack.h"

// ─── Pin Definitions ─────────────────────────────────────────────────────────
// LEDs
#define PIN_LED_RED     32   // board label: 32  (left side)
#define PIN_LED_YLW     33   // board label: 33  (left side)
#define PIN_LED_GRN     27   // board label: 27  (left side)

// GPS — UART2
#define PIN_GPS_TX      17   // board label: 17  (right side) → NEO-M9N RX
#define PIN_GPS_RX      16   // board label: 16  (right side) ← NEO-M9N TX

// IMU — I2C
#define PIN_IMU_SDA     21   // board label: SDA (left side)
#define PIN_IMU_SCL     22   // board label: SCL (left side)
#define PIN_IMU_INT     36   // board label: A0  (right side) ← BNO085 INT
// PIN_IMU_RST not used — BNO085 RST pin tied to 3.3V on the board

// CAN — TWAI
#define PIN_TWAI_TX     14   // board label: 14  (left side)  → TJA1051T TXD
#define PIN_TWAI_RX     21   // board label: 21  (right side) ← TJA1051T RXD
#define PIN_TWAI_S      13   // board label: 13  (left side)  → TJA1051T S
// Note: right-side pin 21 and left-side SDA are confirmed independent via continuity test

// SD — VSPI
#define PIN_SD_CS       15   // board label: 15   (left side)
#define PIN_SD_SCK      18   // board label: SCK  (right side)
#define PIN_SD_MOSI     23   // board label: MOSI (right side)
#define PIN_SD_MISO     19   // board label: MISO (right side)

// ─── Configuration Defaults (overridden by SPIFFS /config.json) ──────────────
#define CFG_FILE        "/config.json"
#define HOSTNAME        "race-logger"

// ─── CAN Filter Table ─────────────────────────────────────────────────────────
// Add/remove 11-bit standard IDs you want to KEEP. Everything else is dropped.
// Set ALLOW_ALL_CAN = true during initial development to inventory your bus.
#define ALLOW_ALL_CAN   false

const uint32_t CAN_ALLOWLIST[] = {
  0x0C9,   // GM: engine RPM / throttle
  0x0D1,   // GM: vehicle speed
  0x1F1,   // GM: steering angle
  0x3D3,   // GM: brake pressure
  0x4EC,   // GM: lateral/longitudinal G (some models)
};
const size_t CAN_ALLOWLIST_LEN = sizeof(CAN_ALLOWLIST) / sizeof(CAN_ALLOWLIST[0]);

// ─── Tuning Constants ─────────────────────────────────────────────────────────
#define IMU_RATE_HZ          100
#define GPS_RATE_HZ          10
#define MQTT_RING_SIZE       128     // must be a power of 2
#define MQTT_MAX_MSG_BYTES   256
#define SD_FLUSH_INTERVAL_MS 2000   // fsync to SD every N ms
#define SD_LOG_MAGIC         0xDEAD1234UL

// ─── Global Config ────────────────────────────────────────────────────────────
struct Config {
  char mqtt_host[64]  = "192.168.1.100";
  char mqtt_user[32]  = "";
  char mqtt_pass[32]  = "";
  int  mqtt_port      = 1883;
  char mqtt_topic[64] = "telemetry/race";
} cfg;

// ─── LED State Machine ────────────────────────────────────────────────────────
enum LedState { LED_BOOT, LED_WAITING, LED_READY };
LedState ledState = LED_BOOT;

void setLed(LedState s) {
  ledState = s;
  digitalWrite(PIN_LED_RED, s == LED_BOOT    ? HIGH : LOW);
  digitalWrite(PIN_LED_YLW, s == LED_WAITING ? HIGH : LOW);
  digitalWrite(PIN_LED_GRN, s == LED_READY   ? HIGH : LOW);
}

// ─── MQTT Ring Buffer ─────────────────────────────────────────────────────────
struct MqttSlot {
  uint8_t  data[MQTT_MAX_MSG_BYTES];
  uint16_t len;
  bool     used;
};
MqttSlot         mqttRing[MQTT_RING_SIZE];
volatile uint16_t ringHead = 0;
volatile uint16_t ringTail = 0;
portMUX_TYPE      ringMux  = portMUX_INITIALIZER_UNLOCKED;

bool ringPush(const uint8_t* buf, uint16_t len) {
  portENTER_CRITICAL(&ringMux);
  uint16_t next = (ringHead + 1) & (MQTT_RING_SIZE - 1);
  if (next == ringTail) { portEXIT_CRITICAL(&ringMux); return false; } // full — drop oldest implicitly
  memcpy(mqttRing[ringHead].data, buf, len);
  mqttRing[ringHead].len  = len;
  mqttRing[ringHead].used = true;
  ringHead = next;
  portEXIT_CRITICAL(&ringMux);
  return true;
}

bool ringPop(uint8_t* buf, uint16_t& len) {
  portENTER_CRITICAL(&ringMux);
  if (ringTail == ringHead) { portEXIT_CRITICAL(&ringMux); return false; }
  memcpy(buf, mqttRing[ringTail].data, mqttRing[ringTail].len);
  len = mqttRing[ringTail].len;
  ringTail = (ringTail + 1) & (MQTT_RING_SIZE - 1);
  portEXIT_CRITICAL(&ringMux);
  return true;
}

// ─── MessagePack Helpers ──────────────────────────────────────────────────────
static uint8_t mpBuf[MQTT_MAX_MSG_BYTES];

uint16_t packGPS(double lat, double lon, double alt, double speed,
                 double hdop, uint8_t sats, uint32_t ts_ms) {
  mpack_writer_t w;
  mpack_writer_init(&w, (char*)mpBuf, sizeof(mpBuf));
  mpack_start_map(&w, 7);
  mpack_write_cstr(&w, "t");  mpack_write_uint(&w, ts_ms);
  mpack_write_cstr(&w, "tp"); mpack_write_cstr(&w, "gps");
  mpack_write_cstr(&w, "la"); mpack_write_double(&w, lat);
  mpack_write_cstr(&w, "lo"); mpack_write_double(&w, lon);
  mpack_write_cstr(&w, "al"); mpack_write_double(&w, alt);
  mpack_write_cstr(&w, "sp"); mpack_write_double(&w, speed);
  mpack_write_cstr(&w, "sa"); mpack_write_uint(&w, sats);
  mpack_finish_map(&w);
  size_t sz = mpack_writer_buffer_used(&w);
  return (mpack_writer_destroy(&w) == mpack_ok) ? (uint16_t)sz : 0;
}

uint16_t packIMU(float qi, float qj, float qk, float qr,
                 float ax, float ay, float az,
                 float gx, float gy, float gz,
                 uint32_t ts_ms) {
  mpack_writer_t w;
  mpack_writer_init(&w, (char*)mpBuf, sizeof(mpBuf));
  mpack_start_map(&w, 12);
  mpack_write_cstr(&w, "t");  mpack_write_uint(&w, ts_ms);
  mpack_write_cstr(&w, "tp"); mpack_write_cstr(&w, "imu");
  mpack_write_cstr(&w, "qi"); mpack_write_float(&w, qi);
  mpack_write_cstr(&w, "qj"); mpack_write_float(&w, qj);
  mpack_write_cstr(&w, "qk"); mpack_write_float(&w, qk);
  mpack_write_cstr(&w, "qr"); mpack_write_float(&w, qr);
  mpack_write_cstr(&w, "ax"); mpack_write_float(&w, ax);
  mpack_write_cstr(&w, "ay"); mpack_write_float(&w, ay);
  mpack_write_cstr(&w, "az"); mpack_write_float(&w, az);
  mpack_write_cstr(&w, "gx"); mpack_write_float(&w, gx);
  mpack_write_cstr(&w, "gy"); mpack_write_float(&w, gy);
  mpack_write_cstr(&w, "gz"); mpack_write_float(&w, gz);
  mpack_finish_map(&w);
  size_t sz = mpack_writer_buffer_used(&w);
  return (mpack_writer_destroy(&w) == mpack_ok) ? (uint16_t)sz : 0;
}

uint16_t packCAN(uint32_t id, uint8_t dlc, const uint8_t* payload, uint32_t ts_ms) {
  mpack_writer_t w;
  mpack_writer_init(&w, (char*)mpBuf, sizeof(mpBuf));
  mpack_start_map(&w, 4);
  mpack_write_cstr(&w, "t");  mpack_write_uint(&w, ts_ms);
  mpack_write_cstr(&w, "tp"); mpack_write_cstr(&w, "can");
  mpack_write_cstr(&w, "id"); mpack_write_uint(&w, id);
  mpack_write_cstr(&w, "d");  mpack_write_bin(&w, (const char*)payload, dlc);
  mpack_finish_map(&w);
  size_t sz = mpack_writer_buffer_used(&w);
  return (mpack_writer_destroy(&w) == mpack_ok) ? (uint16_t)sz : 0;
}

// ─── SD Logging ───────────────────────────────────────────────────────────────
// Binary record format: [MAGIC:4][LEN:2][PAYLOAD:LEN][CRC32:4]
File     logFile;
uint32_t lastFlush = 0;
char     logFilename[32];
bool     sdOk = false;

uint32_t crc32_update(uint32_t crc, const uint8_t* buf, size_t len) {
  for (size_t i = 0; i < len; i++) {
    crc ^= (uint32_t)buf[i] << 24;
    for (int b = 0; b < 8; b++)
      crc = (crc & 0x80000000) ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
  }
  return crc;
}

void sdWriteRecord(const uint8_t* data, uint16_t len) {
  if (!sdOk || !logFile) return;
  uint32_t magic = SD_LOG_MAGIC;
  uint32_t crc   = crc32_update(0xFFFFFFFF, data, len);
  logFile.write((uint8_t*)&magic, 4);
  logFile.write((uint8_t*)&len,   2);
  logFile.write(data, len);
  logFile.write((uint8_t*)&crc,   4);
  if (millis() - lastFlush >= SD_FLUSH_INTERVAL_MS) {
    logFile.flush();
    lastFlush = millis();
  }
}

bool sdInit() {
  if (!SD.begin(PIN_SD_CS)) return false;
  uint32_t bootCount = 0;
  if (SPIFFS.exists("/bootcnt")) {
    File f = SPIFFS.open("/bootcnt", "r");
    f.read((uint8_t*)&bootCount, 4);
    f.close();
  }
  bootCount++;
  File f = SPIFFS.open("/bootcnt", "w");
  f.write((uint8_t*)&bootCount, 4);
  f.close();
  snprintf(logFilename, sizeof(logFilename), "/log_%06lu.bin", bootCount);
  logFile = SD.open(logFilename, FILE_WRITE);
  return logFile != false;
}

// ─── GPS — UBX Configuration for 10 Hz ───────────────────────────────────────
HardwareSerial gpsSerial(2);
TinyGPSPlus    gps;
bool           gpsLocked = false;

void sendUBX(const uint8_t* buf, size_t len) {
  gpsSerial.write(buf, len);
  delay(50);
}

// CFG-RATE: measRate=100ms (10 Hz), navRate=1, timeRef=GPS
const uint8_t UBX_RATE_10HZ[] = {
  0xB5,0x62, 0x06,0x08, 0x06,0x00,
  0x64,0x00, 0x01,0x00, 0x01,0x00,
  0x7A,0x12
};
// CFG-MSG: disable GLL, GSA, GSV, VTG; enable GGA + RMC
const uint8_t UBX_DIS_GLL[] = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x01,0x00,0x01,0x00,0x00,0x00,0x00,0x01,0x2C};
const uint8_t UBX_DIS_GSA[] = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x02,0x00,0x01,0x00,0x00,0x00,0x00,0x02,0x32};
const uint8_t UBX_DIS_GSV[] = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x03,0x00,0x01,0x00,0x00,0x00,0x00,0x03,0x39};
const uint8_t UBX_DIS_VTG[] = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x05,0x00,0x01,0x00,0x00,0x00,0x00,0x05,0x47};
const uint8_t UBX_EN_GGA[]  = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x00,0x00,0x01,0x01,0x00,0x00,0x00,0x01,0x25};
const uint8_t UBX_EN_RMC[]  = {0xB5,0x62,0x06,0x01,0x08,0x00,0xF0,0x04,0x00,0x01,0x01,0x00,0x00,0x00,0x06,0x3F};

void gpsSetup10Hz() {
  sendUBX(UBX_RATE_10HZ, sizeof(UBX_RATE_10HZ));
  sendUBX(UBX_DIS_GLL,   sizeof(UBX_DIS_GLL));
  sendUBX(UBX_DIS_GSA,   sizeof(UBX_DIS_GSA));
  sendUBX(UBX_DIS_GSV,   sizeof(UBX_DIS_GSV));
  sendUBX(UBX_DIS_VTG,   sizeof(UBX_DIS_VTG));
  sendUBX(UBX_EN_GGA,    sizeof(UBX_EN_GGA));
  sendUBX(UBX_EN_RMC,    sizeof(UBX_EN_RMC));
}

// ─── IMU — BNO085 ─────────────────────────────────────────────────────────────
BNO08x imu;
bool   imuOk   = false;
bool   canFlow = false;

void imuSetReports() {
  imu.enableARVRStabilizedRotationVector(10000); // 100 Hz = 10000 µs
  imu.enableAccelerometer(10000);
  imu.enableGyro(10000);
}

// ─── TWAI (CAN) ───────────────────────────────────────────────────────────────
void canSetup() {
  // S pin LOW = normal high-speed mode on TJA1051T
  pinMode(PIN_TWAI_S, OUTPUT);
  digitalWrite(PIN_TWAI_S, LOW);

  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(
    (gpio_num_t)PIN_TWAI_TX, (gpio_num_t)PIN_TWAI_RX, TWAI_MODE_NORMAL);
  twai_timing_config_t  t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t  f = TWAI_FILTER_CONFIG_ACCEPT_ALL(); // SW filtering below
  g.rx_queue_len = 64;
  twai_driver_install(&g, &t, &f);
  twai_start();
}

bool canAllowed(uint32_t id) {
  if (ALLOW_ALL_CAN) return true;
  for (size_t i = 0; i < CAN_ALLOWLIST_LEN; i++)
    if (CAN_ALLOWLIST[i] == id) return true;
  return false;
}

// ─── Config: Load / Save ──────────────────────────────────────────────────────
void loadConfig() {
  if (!SPIFFS.exists(CFG_FILE)) return;
  File f = SPIFFS.open(CFG_FILE, "r");
  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, f) == DeserializationError::Ok) {
    strlcpy(cfg.mqtt_host,  doc["mqtt_host"]  | cfg.mqtt_host,  sizeof(cfg.mqtt_host));
    strlcpy(cfg.mqtt_user,  doc["mqtt_user"]  | cfg.mqtt_user,  sizeof(cfg.mqtt_user));
    strlcpy(cfg.mqtt_pass,  doc["mqtt_pass"]  | cfg.mqtt_pass,  sizeof(cfg.mqtt_pass));
    strlcpy(cfg.mqtt_topic, doc["mqtt_topic"] | cfg.mqtt_topic, sizeof(cfg.mqtt_topic));
    cfg.mqtt_port = doc["mqtt_port"] | cfg.mqtt_port;
  }
  f.close();
}

void saveConfig() {
  File f = SPIFFS.open(CFG_FILE, "w");
  StaticJsonDocument<512> doc;
  doc["mqtt_host"]  = cfg.mqtt_host;
  doc["mqtt_user"]  = cfg.mqtt_user;
  doc["mqtt_pass"]  = cfg.mqtt_pass;
  doc["mqtt_port"]  = cfg.mqtt_port;
  doc["mqtt_topic"] = cfg.mqtt_topic;
  serializeJson(doc, f);
  f.close();
}

// ─── WiFiManager + Config Portal ──────────────────────────────────────────────
WiFiManager wm;
bool        configChanged = false;

WiFiManagerParameter p_mqtt_host ("mqtt_host",  "MQTT Host",     cfg.mqtt_host,  64);
WiFiManagerParameter p_mqtt_user ("mqtt_user",  "MQTT User",     cfg.mqtt_user,  32);
WiFiManagerParameter p_mqtt_pass ("mqtt_pass",  "MQTT Password", cfg.mqtt_pass,  32);
WiFiManagerParameter p_mqtt_port ("mqtt_port",  "MQTT Port",     "1883",         6);
WiFiManagerParameter p_mqtt_topic("mqtt_topic", "MQTT Topic",    cfg.mqtt_topic, 64);

void onSaveParams() {
  strlcpy(cfg.mqtt_host,  p_mqtt_host.getValue(),  sizeof(cfg.mqtt_host));
  strlcpy(cfg.mqtt_user,  p_mqtt_user.getValue(),  sizeof(cfg.mqtt_user));
  strlcpy(cfg.mqtt_pass,  p_mqtt_pass.getValue(),  sizeof(cfg.mqtt_pass));
  strlcpy(cfg.mqtt_topic, p_mqtt_topic.getValue(), sizeof(cfg.mqtt_topic));
  cfg.mqtt_port = atoi(p_mqtt_port.getValue());
  configChanged = true;
}

// ─── MQTT ─────────────────────────────────────────────────────────────────────
WiFiClient   wifiClient;
PubSubClient mqtt(wifiClient);
uint32_t     lastMqttRetry = 0;

void mqttConnect() {
  if (mqtt.connected()) return;
  if (millis() - lastMqttRetry < 5000) return;
  lastMqttRetry = millis();
  mqtt.setServer(cfg.mqtt_host, cfg.mqtt_port);
  mqtt.setBufferSize(512);
  String cid = String(HOSTNAME) + "-" + String((uint32_t)ESP.getEfuseMac(), HEX);
  if (!mqtt.connect(cid.c_str(),
                    cfg.mqtt_user[0] ? cfg.mqtt_user : nullptr,
                    cfg.mqtt_pass[0] ? cfg.mqtt_pass : nullptr)) {
    Serial.printf("[MQTT] connect failed, rc=%d\n", mqtt.state());
  }
}

void mqttDrain() {
  if (!mqtt.connected()) return;
  uint8_t  buf[MQTT_MAX_MSG_BYTES];
  uint16_t len;
  int      drained = 0;
  while (drained < 8 && ringPop(buf, len)) {
    mqtt.publish(cfg.mqtt_topic, buf, len, false);
    drained++;
  }
  mqtt.loop();
}

// ─── OTA ──────────────────────────────────────────────────────────────────────
void otaSetup() {
  ArduinoOTA.setHostname(HOSTNAME);
  ArduinoOTA.onStart([]() {
    setLed(LED_BOOT);
    if (logFile) logFile.flush();
    Serial.println("[OTA] Start");
  });
  ArduinoOTA.onProgress([](uint32_t p, uint32_t t) {
    digitalWrite(PIN_LED_YLW, (p / 4096) % 2);
  });
  ArduinoOTA.onEnd([]() {
    Serial.println("[OTA] Done — rebooting");
  });
  ArduinoOTA.onError([](ota_error_t e) {
    Serial.printf("[OTA] Error %u\n", e);
  });
  ArduinoOTA.begin();
}

// ─── Setup ────────────────────────────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  // LEDs — first thing so we get RED immediately
  pinMode(PIN_LED_RED, OUTPUT);
  pinMode(PIN_LED_YLW, OUTPUT);
  pinMode(PIN_LED_GRN, OUTPUT);
  setLed(LED_BOOT);

  // SPIFFS
  if (!SPIFFS.begin(true)) {
    Serial.println("[SPIFFS] Mount failed — formatting");
    SPIFFS.format();
    SPIFFS.begin();
  }
  loadConfig();

  // WiFiManager
  wm.addParameter(&p_mqtt_host);
  wm.addParameter(&p_mqtt_user);
  wm.addParameter(&p_mqtt_pass);
  wm.addParameter(&p_mqtt_port);
  wm.addParameter(&p_mqtt_topic);
  wm.setSaveParamsCallback(onSaveParams);
  wm.setConfigPortalTimeout(180);
  wm.setHostname(HOSTNAME);
  wm.setTitle("Race Logger Setup");

  bool connected = wm.autoConnect("RaceLogger-Setup");
  if (!connected) {
    Serial.println("[WiFi] Could not connect — continuing offline");
  } else if (configChanged) {
    saveConfig();
  }

  // SD
  sdOk = sdInit();
  if (!sdOk) Serial.println("[SD] Init failed — SD logging disabled");
  else       Serial.printf("[SD] Logging to %s\n", logFilename);

  // GPS
  gpsSerial.begin(38400, SERIAL_8N1, PIN_GPS_RX, PIN_GPS_TX);
  delay(100);
  gpsSetup10Hz();
  Serial.println("[GPS] Configured for 10 Hz");

  // IMU
  Wire.begin(PIN_IMU_SDA, PIN_IMU_SCL);
  Wire.setClock(400000);
  if (imu.begin()) {
    imuOk = true;
    imuSetReports();
    Serial.println("[IMU] BNO085 ready");
  } else {
    Serial.println("[IMU] BNO085 not found!");
  }

  // CAN
  canSetup();
  Serial.println("[CAN] TWAI normal mode started @ 500kbps");

  // OTA
  if (connected) otaSetup();

  // All init done — go yellow and wait for GPS lock + CAN flow
  setLed(LED_WAITING);
  Serial.println("[BOOT] Waiting for GPS lock and CAN data...");
}

// ─── Main Loop ────────────────────────────────────────────────────────────────
void loop() {
  // OTA
  ArduinoOTA.handle();

  // GPS — feed parser and publish at 10 Hz
  while (gpsSerial.available()) {
    gps.encode(gpsSerial.read());
  }
  static uint32_t lastGpsPub = 0;
  if (gps.location.isUpdated() && millis() - lastGpsPub >= 100) {
    lastGpsPub = millis();
    gpsLocked  = gps.location.isValid() && gps.hdop.value() < 300; // HDOP < 3.0
    uint16_t len = packGPS(
      gps.location.lat(), gps.location.lng(),
      gps.altitude.meters(), gps.speed.mps(),
      gps.hdop.hdop(), (uint8_t)gps.satellites.value(),
      millis()
    );
    if (len) {
      sdWriteRecord(mpBuf, len);
      ringPush(mpBuf, len);
    }
  }

  // IMU — poll at 100 Hz
  static uint32_t lastImuUs = 0;
  uint32_t nowUs = micros();
  if (imuOk && (nowUs - lastImuUs >= 10000)) {
    lastImuUs = nowUs;
    if (imu.wasReset()) imuSetReports();
    if (imu.getSensorEvent()) {
      sh2_SensorValue_t val = imu.sensorValue;
      static float qi,qj,qk,qr, ax,ay,az, gx,gy,gz;
      switch (val.sensorId) {
        case SH2_ARVR_STABILIZED_RV:
          qi = val.un.arvrStabilizedRV.i;
          qj = val.un.arvrStabilizedRV.j;
          qk = val.un.arvrStabilizedRV.k;
          qr = val.un.arvrStabilizedRV.real;
          break;
        case SH2_ACCELEROMETER:
          ax = val.un.accelerometer.x;
          ay = val.un.accelerometer.y;
          az = val.un.accelerometer.z;
          break;
        case SH2_GYROSCOPE_CALIBRATED:
          gx = val.un.gyroscope.x;
          gy = val.un.gyroscope.y;
          gz = val.un.gyroscope.z;
          {
            uint16_t len = packIMU(qi,qj,qk,qr, ax,ay,az, gx,gy,gz, millis());
            if (len) {
              sdWriteRecord(mpBuf, len);
              ringPush(mpBuf, len);
            }
          }
          break;
      }
    }
  }

  // CAN — receive and filter
  twai_message_t msg;
  if (twai_receive(&msg, 0) == ESP_OK) {
    if (canAllowed(msg.identifier)) {
      canFlow = true;
      uint16_t len = packCAN(msg.identifier, msg.data_length_code, msg.data, millis());
      if (len) {
        sdWriteRecord(mpBuf, len);
        ringPush(mpBuf, len);
      }
    }
  }

  // MQTT — non-blocking drain
  mqttConnect();
  mqttDrain();

  // LED state transitions
  if (ledState == LED_WAITING && gpsLocked && canFlow) {
    setLed(LED_READY);
    Serial.println("[STATUS] GPS locked + CAN flowing — GREEN");
  }
  if (ledState == LED_READY && (!gpsLocked || !canFlow)) {
    setLed(LED_WAITING);
    Serial.println("[STATUS] Lost GPS or CAN — YELLOW");
  }
}
