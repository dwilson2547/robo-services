#include <TinyGPSPlus.h>
#include <WiFi.h>
#include <WiFiUdp.h>

namespace {

constexpr char kWifiSsid[] = "Sanchez 2";
constexpr char kWifiPassword[] = "dw31571102";
const IPAddress kReceiverIp(192, 168, 0, 179);
constexpr uint16_t kReceiverPort = 5514;

constexpr uint8_t kGpsRxPin = 16;
constexpr uint8_t kGpsTxPin = 17;
constexpr uint32_t kGpsBaud = 9600;

constexpr uint32_t kFixPublishIntervalMs = 1000;
constexpr uint32_t kNoFixPublishIntervalMs = 5000;
constexpr uint32_t kWifiRetryDelayMs = 500;
constexpr char kSourceName[] = "gps-test-feed";
constexpr char kSourceSession[] = "esp32-gps-bench";

TinyGPSPlus gps;
TinyGPSCustom gpggaFixQuality(gps, "GPGGA", 6);
TinyGPSCustom gnggaFixQuality(gps, "GNGGA", 6);
HardwareSerial gpsSerial(2);
WiFiUDP udp;

uint32_t sequenceNumber = 0;
uint32_t lastFixPublishMs = 0;
uint32_t lastNoFixPublishMs = 0;
char deviceId[13] = {};

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

String buildPayload(bool hasFix) {
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
  payload += kSourceName;
  payload += "\",";
  payload += "\"source_session\":\"";
  payload += kSourceSession;
  payload += "\",";
  payload += "\"device_id\":\"";
  payload += deviceId;
  payload += "\",";
  payload += "\"sequence\":";
  payload += String(sequenceNumber++);
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

void publishPacket(bool hasFix) {
  const String payload = buildPayload(hasFix);
  udp.beginPacket(kReceiverIp, kReceiverPort);
  udp.write(reinterpret_cast<const uint8_t*>(payload.c_str()), payload.length());
  udp.endPacket();
  Serial.println(payload);
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

}  // namespace

void setup() {
  Serial.begin(115200);
  delay(500);

  uint64_t mac = ESP.getEfuseMac();
  snprintf(
      deviceId,
      sizeof(deviceId),
      "%04X%08X",
      static_cast<uint16_t>(mac >> 32),
      static_cast<uint32_t>(mac));

  gpsSerial.begin(kGpsBaud, SERIAL_8N1, kGpsRxPin, kGpsTxPin);
  connectWifi();
  udp.begin(0);

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

  if (hasFix && now - lastFixPublishMs >= kFixPublishIntervalMs) {
    publishPacket(true);
    lastFixPublishMs = now;
    lastNoFixPublishMs = now;
    return;
  }

  if (!hasFix && now - lastNoFixPublishMs >= kNoFixPublishIntervalMs) {
    publishPacket(false);
    lastNoFixPublishMs = now;
  }
}
