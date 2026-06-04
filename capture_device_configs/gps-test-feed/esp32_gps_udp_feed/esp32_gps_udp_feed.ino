#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <TinyGPSPlus.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <Wire.h>

namespace {

constexpr char kWifiSsid[] = "Sanchez 2";
constexpr char kWifiPassword[] = "dw31571102";
const IPAddress kReceiverIp(192, 168, 0, 70);
constexpr uint16_t kReceiverPort = 5514;

constexpr uint8_t kGpsRxPin = 16;
constexpr uint8_t kGpsTxPin = 17;
constexpr uint32_t kGpsBaud = 9600;
constexpr uint8_t kI2cSdaPin = 21;
constexpr uint8_t kI2cSclPin = 22;
constexpr uint8_t kMpu6050Address = 0x68;
constexpr uint8_t kWifiLedPin = 32;
constexpr uint8_t kGpsLedPin = 26;

constexpr uint32_t kFixPublishIntervalMs = 1000;
constexpr uint32_t kNoFixPublishIntervalMs = 5000;
constexpr uint32_t kImuPublishIntervalMs = 500;
// Searching: 250ms fast blink. Acquired: 50ms flash every 2s (heartbeat).
constexpr uint32_t kLedSearchOnMs = 250;
constexpr uint32_t kLedSearchOffMs = 250;
constexpr uint32_t kLedHeartbeatOnMs = 50;
constexpr uint32_t kLedHeartbeatOffMs = 1950;
constexpr uint32_t kWifiRetryDelayMs = 500;
constexpr char kGpsSourceName[] = "gps-test-feed";
constexpr char kImuSourceName[] = "imu-test-feed";
constexpr char kSourceSession[] = "esp32-gps-bench";

TinyGPSPlus gps;
TinyGPSCustom gpggaFixQuality(gps, "GPGGA", 6);
TinyGPSCustom gnggaFixQuality(gps, "GNGGA", 6);
Adafruit_MPU6050 mpu;
HardwareSerial gpsSerial(2);
WiFiUDP udp;

uint32_t gpsSequenceNumber = 0;
uint32_t imuSequenceNumber = 0;
uint32_t lastFixPublishMs = 0;
uint32_t lastNoFixPublishMs = 0;
uint32_t lastImuPublishMs = 0;
uint32_t lastWifiLedToggleMs = 0;
uint32_t lastGpsLedToggleMs = 0;
char deviceId[13] = {};
bool imuAvailable = false;
bool wifiLedState = false;
bool gpsLedState = false;

String currentIsoTimestampOrNull() {
  if (!gps.date.isValid() || !gps.time.isValid()) {
    return "null";
  }

  char buffer[32];
  snprintf(
      buffer,
      sizeof(buffer),
      "\"%04d-%02d-%02dT%02d:%02d:%02dZ\"",
      gps.date.year(),
      gps.date.month(),
      gps.date.day(),
      gps.time.hour(),
      gps.time.minute(),
      gps.time.second());
  return String(buffer);
}

double currentHdopOrNaN() {
  if (!gps.hdop.isValid()) {
    return NAN;
  }
  return gps.hdop.hdop();
}

double currentAltitudeOrNaN() {
  if (!gps.altitude.isValid()) {
    return NAN;
  }
  return gps.altitude.meters();
}

double currentSpeedOrNaN() {
  if (!gps.speed.isValid()) {
    return NAN;
  }
  return gps.speed.kmph();
}

double currentCourseOrNaN() {
  if (!gps.course.isValid()) {
    return NAN;
  }
  return gps.course.deg();
}

int currentFixQuality(bool hasFix) {
  if (!hasFix) {
    return 0;
  }
  const char* gnggaValue = gnggaFixQuality.value();
  if (gnggaValue[0] != '\0') {
    return atoi(gnggaValue);
  }
  const char* gpggaValue = gpggaFixQuality.value();
  if (gpggaValue[0] != '\0') {
    return atoi(gpggaValue);
  }
  return 1;
}

String jsonNumberOrNull(double value, uint8_t decimals) {
  if (isnan(value)) {
    return "null";
  }
  return String(value, static_cast<unsigned int>(decimals));
}

String buildGpsPayload(bool hasFix) {
  const int fixQuality = currentFixQuality(hasFix);
  const uint32_t satellites = gps.satellites.isValid() ? gps.satellites.value() : 0;
  const double latitude = hasFix ? gps.location.lat() : NAN;
  const double longitude = hasFix ? gps.location.lng() : NAN;
  const double altitude = hasFix ? currentAltitudeOrNaN() : NAN;
  const double speed = hasFix ? currentSpeedOrNaN() : NAN;
  const double course = hasFix ? currentCourseOrNaN() : NAN;

  String payload;
  payload.reserve(384);
  payload += "{";
  payload += "\"source\":\"";
  payload += kGpsSourceName;
  payload += "\",";
  payload += "\"source_session\":\"";
  payload += kSourceSession;
  payload += "\",";
  payload += "\"device_id\":\"";
  payload += deviceId;
  payload += "\",";
  payload += "\"message_type\":\"telemetry\",";
  payload += "\"sequence\":";
  payload += String(gpsSequenceNumber++);
  payload += ",";
  payload += "\"captured_at\":";
  payload += currentIsoTimestampOrNull();
  payload += ",";
  payload += "\"has_fix\":";
  payload += hasFix ? "true" : "false";
  payload += ",";
  payload += "\"fix_quality\":";
  payload += String(fixQuality);
  payload += ",";
  payload += "\"satellites\":";
  payload += String(satellites);
  payload += ",";
  payload += "\"latitude\":";
  payload += jsonNumberOrNull(latitude, 6);
  payload += ",";
  payload += "\"longitude\":";
  payload += jsonNumberOrNull(longitude, 6);
  payload += ",";
  payload += "\"altitude_m\":";
  payload += jsonNumberOrNull(altitude, 2);
  payload += ",";
  payload += "\"ground_speed_kph\":";
  payload += jsonNumberOrNull(speed, 2);
  payload += ",";
  payload += "\"heading_deg\":";
  payload += jsonNumberOrNull(course, 2);
  payload += ",";
  payload += "\"hdop\":";
  payload += jsonNumberOrNull(currentHdopOrNaN(), 2);
  payload += ",";
  payload += "\"wifi_rssi_dbm\":";
  payload += String(WiFi.RSSI());
  payload += ",";
  payload += "\"uptime_ms\":";
  payload += String(millis());
  payload += "}";
  return payload;
}

String buildImuPayload() {
  sensors_event_t accel;
  sensors_event_t gyro;
  sensors_event_t temp;
  mpu.getEvent(&accel, &gyro, &temp);

  String payload;
  payload.reserve(448);
  payload += "{";
  payload += "\"source\":\"";
  payload += kImuSourceName;
  payload += "\",";
  payload += "\"source_session\":\"";
  payload += kSourceSession;
  payload += "\",";
  payload += "\"device_id\":\"";
  payload += deviceId;
  payload += "\",";
  payload += "\"message_type\":\"imu\",";
  payload += "\"sequence\":";
  payload += String(imuSequenceNumber++);
  payload += ",";
  payload += "\"captured_at\":";
  payload += currentIsoTimestampOrNull();
  payload += ",";
  payload += "\"accel_m_s2\":{";
  payload += "\"x\":";
  payload += jsonNumberOrNull(accel.acceleration.x, 3);
  payload += ",\"y\":";
  payload += jsonNumberOrNull(accel.acceleration.y, 3);
  payload += ",\"z\":";
  payload += jsonNumberOrNull(accel.acceleration.z, 3);
  payload += "},";
  payload += "\"gyro_rad_s\":{";
  payload += "\"x\":";
  payload += jsonNumberOrNull(gyro.gyro.x, 3);
  payload += ",\"y\":";
  payload += jsonNumberOrNull(gyro.gyro.y, 3);
  payload += ",\"z\":";
  payload += jsonNumberOrNull(gyro.gyro.z, 3);
  payload += "},";
  payload += "\"temperature_c\":";
  payload += jsonNumberOrNull(temp.temperature, 2);
  payload += ",";
  payload += "\"wifi_rssi_dbm\":";
  payload += String(WiFi.RSSI());
  payload += ",";
  payload += "\"uptime_ms\":";
  payload += String(millis());
  payload += "}";
  return payload;
}

void publishPacket(const String& payload) {
  udp.beginPacket(kReceiverIp, kReceiverPort);
  udp.write(reinterpret_cast<const uint8_t*>(payload.c_str()), payload.length());
  udp.endPacket();
}

void publishGpsPacket(bool hasFix) {
  publishPacket(buildGpsPayload(hasFix));
}

void publishImuPacket() {
  if (!imuAvailable) {
    return;
  }
  publishPacket(buildImuPayload());
}

void connectWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(kWifiSsid, kWifiPassword);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(kWifiRetryDelayMs);
    Serial.print(".");
  }
  Serial.println();
  Serial.print("WiFi connected, IP=");
  Serial.println(WiFi.localIP());
}

void ensureWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    return;
  }
  Serial.println("WiFi dropped, reconnecting");
  WiFi.disconnect();
  connectWifi();
}

void initImu() {
  imuAvailable = mpu.begin(kMpu6050Address, &Wire);
  if (!imuAvailable) {
    Serial.println("MPU-6050 init failed");
    return;
  }

  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  Serial.println("MPU-6050 online");
}

void updateLeds(bool hasFix) {
  const uint32_t now = millis();

  const bool wifiUp = WiFi.status() == WL_CONNECTED;
  const uint32_t wifiPeriod =
      wifiLedState ? (wifiUp ? kLedHeartbeatOnMs : kLedSearchOnMs)
                   : (wifiUp ? kLedHeartbeatOffMs : kLedSearchOffMs);
  if (now - lastWifiLedToggleMs >= wifiPeriod) {
    wifiLedState = !wifiLedState;
    digitalWrite(kWifiLedPin, wifiLedState ? HIGH : LOW);
    lastWifiLedToggleMs = now;
  }

  const uint32_t gpsPeriod =
      gpsLedState ? (hasFix ? kLedHeartbeatOnMs : kLedSearchOnMs)
                  : (hasFix ? kLedHeartbeatOffMs : kLedSearchOffMs);
  if (now - lastGpsLedToggleMs >= gpsPeriod) {
    gpsLedState = !gpsLedState;
    digitalWrite(kGpsLedPin, gpsLedState ? HIGH : LOW);
    lastGpsLedToggleMs = now;
  }
}

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(kWifiLedPin, OUTPUT);
  pinMode(kGpsLedPin, OUTPUT);
  digitalWrite(kWifiLedPin, LOW);
  digitalWrite(kGpsLedPin, LOW);

  uint64_t mac = ESP.getEfuseMac();
  snprintf(
      deviceId,
      sizeof(deviceId),
      "%04X%08X",
      static_cast<uint16_t>(mac >> 32),
      static_cast<uint32_t>(mac));

  Wire.begin(kI2cSdaPin, kI2cSclPin);
  gpsSerial.begin(kGpsBaud, SERIAL_8N1, kGpsRxPin, kGpsTxPin);
  connectWifi();
  udp.begin(0);
  initImu();

  Serial.print("Device ID: ");
  Serial.println(deviceId);
  Serial.println("Waiting for GPS sentences");
}

void loop() {
  ensureWifi();

  while (gpsSerial.available() > 0) {
    gps.encode(gpsSerial.read());
  }

  const uint32_t now = millis();
  const bool hasFix =
      gps.location.isValid() && gps.location.age() < 2000 && gps.date.isValid() && gps.time.isValid();

  if (imuAvailable && now - lastImuPublishMs >= kImuPublishIntervalMs) {
    publishImuPacket();
    lastImuPublishMs = now;
  }

  if (hasFix && now - lastFixPublishMs >= kFixPublishIntervalMs) {
    publishGpsPacket(true);
    lastFixPublishMs = now;
    lastNoFixPublishMs = now;
  }

  if (!hasFix && now - lastNoFixPublishMs >= kNoFixPublishIntervalMs) {
    publishGpsPacket(false);
    lastNoFixPublishMs = now;
  }

  updateLeds(hasFix);
}
