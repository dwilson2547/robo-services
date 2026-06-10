/*
  RTK Base Station — Phase 1 Raw Observation Logger

  Connects to ZED-F9P via I2C (Qwiic), enables RXM-RAWX and RXM-SFRBX,
  then streams raw UBX bytes over WiFi/TCP to the homelab Python receiver.

  Board: SparkFun ESP32 Thing Plus  (esp32:esp32:esp32thing_plus)

  Library: SparkFun u-blox GNSS v3 (install via Library Manager)
*/

#include <Wire.h>
#include <WiFi.h>
#include <SparkFun_u-blox_GNSS_v3.h>

// ---- Configuration — set these before flashing ----
const char* ssid       = "YOUR_WIFI_SSID";
const char* password   = "YOUR_WIFI_PASSWORD";
const char* serverIP   = "192.168.0.55";  // devnode running ubx_receiver.py
const int   serverPort = 5555;
// ---------------------------------------------------

// 32KB ring buffer in ESP32 RAM — large enough to absorb TCP write delays
// without losing data. RAWX messages are ~2KB each at 1Hz; this holds ~16 epochs.
#define FILE_BUFFER_SIZE 32768
#define TCP_CHUNK_SIZE   512

SFE_UBLOX_GNSS myGNSS;
WiFiClient client;

int numSFRBX = 0;
int numRAWX  = 0;
unsigned long totalBytesSent = 0;
unsigned long lastStatus = 0;

void onSFRBX(UBX_RXM_SFRBX_data_t *d) { numSFRBX++; }
void onRAWX(UBX_RXM_RAWX_data_t *d)   { numRAWX++;  }

// ---------------------------------------------------------------------------

void connectWiFi() {
  Serial.print("Connecting to WiFi");
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.printf("\nWiFi connected: %s\n", WiFi.localIP().toString().c_str());
}

bool connectTCP() {
  Serial.printf("Connecting TCP to %s:%d...\n", serverIP, serverPort);
  if (client.connect(serverIP, serverPort)) {
    Serial.println("TCP connected");
    return true;
  }
  Serial.println("TCP connection failed");
  return false;
}

// ---------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n--- RTK Phase 1 Logger ---");

  Wire.begin();

  // Drain any buffered F9P output before begin() — prevents bus lockup on restart.
  {
    for (int iter = 0; iter < 500; iter++) {
      Wire.beginTransmission(0x42); Wire.write(0xFD);
      if (Wire.endTransmission(false) != 0) break;
      uint8_t n = Wire.requestFrom((uint8_t)0x42, (uint8_t)2);
      if (n < 2) break;
      uint16_t avail = ((uint16_t)Wire.read() << 8) | Wire.read();
      if (avail == 0 || avail == 0xFFFF) break;
      uint8_t chunk = (avail > 32) ? 32 : (uint8_t)avail;
      uint8_t got = Wire.requestFrom((uint8_t)0x42, chunk);
      for (int i = 0; i < got; i++) Wire.read();
      if (avail <= 32) break;
    }
  }

  // setFileBufferSize must be called before begin()
  myGNSS.setFileBufferSize(FILE_BUFFER_SIZE);

  if (!myGNSS.begin()) {
    Serial.println("ERROR: F9P not detected on I2C. Check Qwiic wiring. Halting.");
    while (1) delay(1000);
  }
  Serial.println("F9P connected");

  // Print firmware version — confirms chip is alive, helps spot if update needed
  if (myGNSS.getModuleInfo()) {
    Serial.printf("F9P firmware: %s %d.%02d  Protocol: %d.%02d\n",
      myGNSS.getFirmwareType(),
      myGNSS.getFirmwareVersionHigh(),
      myGNSS.getFirmwareVersionLow(),
      myGNSS.getProtocolVersionHigh(),
      myGNSS.getProtocolVersionLow());
  }

  // Survey-in check: if TMODE3 is Disabled, getSurveyInActive() should be false.
  // If it's active, something is wrong — the F9P shouldn't be averaging position
  // during Phase 1 raw logging.
  if (myGNSS.getSurveyStatus(3000)) {
    if (myGNSS.getSurveyInActive()) {
      Serial.println("WARNING: Survey-in is ACTIVE. TMODE3 may not be Disabled.");
      Serial.println("         Reconfigure in u-center before collecting data.");
    } else {
      Serial.println("TMODE3 check: survey-in not active (expected for Phase 1)");
    }
  }

  myGNSS.setI2COutput(COM_TYPE_UBX);  // UBX only, no NMEA on I2C

  // Register callbacks so we get message counts, then enable file-buffer logging.
  // logRXMSFRBX / logRXMRAWX route incoming UBX frames into the 32KB ring buffer
  // instead of discarding them after the callback fires.
  myGNSS.setAutoRXMSFRBXcallbackPtr(&onSFRBX);
  myGNSS.logRXMSFRBX();
  myGNSS.setAutoRXMRAWXcallbackPtr(&onRAWX);
  myGNSS.logRXMRAWX();

  Serial.println("RXM-RAWX enabled");
  Serial.println("RXM-SFRBX enabled");

  connectWiFi();

  while (!connectTCP()) {
    Serial.println("Retrying TCP in 5s...");
    delay(5000);
  }

  lastStatus = millis();
  Serial.println("Streaming...");
}

// ---------------------------------------------------------------------------

void loop() {
  // Reconnect if dropped
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("WiFi lost, reconnecting...");
    connectWiFi();
  }
  if (!client.connected()) {
    Serial.println("TCP lost, reconnecting...");
    delay(2000);
    if (!connectTCP()) return;
  }

  myGNSS.checkUblox();
  myGNSS.checkCallbacks();

  // Drain file buffer to TCP in 512-byte chunks.
  // checkUblox inside the loop keeps the I2C buffer fed while TCP writes.
  static uint8_t txBuf[TCP_CHUNK_SIZE];
  while (myGNSS.fileBufferAvailable() >= TCP_CHUNK_SIZE) {
    myGNSS.extractFileBufferData(txBuf, TCP_CHUNK_SIZE);
    client.write(txBuf, TCP_CHUNK_SIZE);
    totalBytesSent += TCP_CHUNK_SIZE;
    myGNSS.checkUblox();
    myGNSS.checkCallbacks();
  }

  if (millis() - lastStatus > 10000) {
    float mb = totalBytesSent / 1048576.0f;
    Serial.printf("[%s] %.2f MB sent | RAWX: %d  SFRBX: %d | buf: %d B\n",
      formatUptime().c_str(), mb, numRAWX, numSFRBX,
      myGNSS.fileBufferAvailable());

    if (myGNSS.getMaxFileBufferAvail() > (FILE_BUFFER_SIZE * 4 / 5)) {
      Serial.println("WARNING: file buffer >80% full — TCP throughput may be insufficient!");
    }

    lastStatus = millis();
  }
}

// ---------------------------------------------------------------------------

String formatUptime() {
  unsigned long s = millis() / 1000;
  return String(s / 3600) + "h" +
         String((s % 3600) / 60) + "m" +
         String(s % 60) + "s";
}
