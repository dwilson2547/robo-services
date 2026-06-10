/*
  RTK Base Station — F9P Configuration Verifier

  Reads back every setting that was applied in u-center and prints a
  pass/fail report to Serial. No WiFi, no SD card — just I2C (Qwiic).

  Connect ESP32 to F9P via Qwiic cable, open Serial Monitor at 115200.

  Board: SparkFun ESP32 Thing Plus  (esp32:esp32:esp32thing_plus)
  Library: SparkFun u-blox GNSS v3
*/

#include <Wire.h>
#include <SparkFun_u-blox_GNSS_v3.h>

SFE_UBLOX_GNSS gnss;

int rawxCount = 0;
void onRAWX(UBX_RXM_RAWX_data_t *d) { rawxCount++; }

// Drain the F9P's I2C output buffer before begin() to prevent bus lockups.
// The F9P holds SDA LOW when its output buffer is full and no master reads it.
static void drainF9P() {
  for (int iter = 0; iter < 500; iter++) {
    Wire.beginTransmission(0x42);
    Wire.write(0xFD);
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

bool check8(const char *name, uint32_t key, uint8_t expected) {
  uint8_t val = 0;
  bool ok = gnss.getVal8(key, &val, VAL_LAYER_RAM);
  if (!ok) {
    Serial.printf("  %-44s  READ FAILED\n", name);
    return false;
  }
  bool pass = (val == expected);
  Serial.printf("  %-44s  %d  %s\n", name, val, pass ? "PASS" : "FAIL <-- check u-center");
  return pass;
}

bool check16(const char *name, uint32_t key, uint16_t expected) {
  uint16_t val = 0;
  bool ok = gnss.getVal16(key, &val, VAL_LAYER_RAM);
  if (!ok) {
    Serial.printf("  %-44s  READ FAILED\n", name);
    return false;
  }
  bool pass = (val == expected);
  Serial.printf("  %-44s  %d  %s\n", name, val, pass ? "PASS" : "FAIL <-- check u-center");
  return pass;
}

bool gnssReady = false;

void runVerification();

void setup() {
  Serial.begin(115200);
  delay(3000);
  Serial.println("F9P Verifier starting...");

  Wire.begin();

  drainF9P();

  if (!gnss.begin()) {
    while (true) {
      Serial.println("FATAL: F9P not detected on I2C. Check Qwiic cable and power.");
      delay(3000);
    }
  }
  gnssReady = true;
  Serial.println("F9P connected.");
}

void loop() {
  static unsigned long lastRun = 0;
  if (gnssReady && (lastRun == 0 || millis() - lastRun > 60000)) {
    runVerification();
    lastRun = millis();
  }
  gnss.checkUblox();
  gnss.checkCallbacks();
  delay(50);
}

void runVerification() {
  rawxCount = 0;

  Serial.println();
  Serial.println("========================================");
  Serial.println("  F9P Configuration Verifier");
  Serial.println("========================================");

  // Firmware
  Serial.println("\n[ Firmware ]");
  if (gnss.getModuleInfo()) {
    Serial.printf("  Type: %s  Version: %d.%02d  Protocol: %d.%02d\n",
      gnss.getFirmwareType(),
      gnss.getFirmwareVersionHigh(), gnss.getFirmwareVersionLow(),
      gnss.getProtocolVersionHigh(), gnss.getProtocolVersionLow());
    if (gnss.getFirmwareVersionHigh() == 1 && gnss.getFirmwareVersionLow() < 32)
      Serial.println("  NOTE: firmware < HPG 1.32 — update recommended");
  }

  // Navigation model (expected 2 = Stationary)
  Serial.println("\n[ Navigation Model ]  expected: 2 (Stationary)");
  check8("CFG-NAVSPG-DYNMODEL", UBLOX_CFG_NAVSPG_DYNMODEL, 2);

  // Measurement rate (expected 1000 ms)
  Serial.println("\n[ Measurement Rate ]  expected: 1000 ms");
  check16("CFG-RATE-MEAS (ms)", UBLOX_CFG_RATE_MEAS, 1000);

  // TMODE3 (expected 0 = Disabled for Phase 1)
  Serial.println("\n[ Time Mode TMODE3 ]  expected: 0 (Disabled)");
  check8("CFG-TMODE-MODE", UBLOX_CFG_TMODE_MODE, 0);

  // GNSS signals
  Serial.println("\n[ GNSS Signals ]  expected: GPS L1+L2, GLO L1+L2, GAL E1+E5b, BDS B1I+B2I");
  check8("GPS enable",     UBLOX_CFG_SIGNAL_GPS_ENA,     1);
  check8("GPS L1C/A",      UBLOX_CFG_SIGNAL_GPS_L1CA_ENA, 1);
  check8("GPS L2C",        UBLOX_CFG_SIGNAL_GPS_L2C_ENA,  1);
  check8("GLONASS enable", UBLOX_CFG_SIGNAL_GLO_ENA,      1);
  check8("GLONASS L1",     UBLOX_CFG_SIGNAL_GLO_L1_ENA,   1);
  check8("GLONASS L2",     UBLOX_CFG_SIGNAL_GLO_L2_ENA,   1);
  check8("Galileo enable", UBLOX_CFG_SIGNAL_GAL_ENA,      1);
  check8("Galileo E1",     UBLOX_CFG_SIGNAL_GAL_E1_ENA,   1);
  check8("Galileo E5b",    UBLOX_CFG_SIGNAL_GAL_E5B_ENA,  1);
  check8("BeiDou enable",  UBLOX_CFG_SIGNAL_BDS_ENA,      1);
  check8("BeiDou B1I",     UBLOX_CFG_SIGNAL_BDS_B1_ENA,   1);
  check8("BeiDou B2I",     UBLOX_CFG_SIGNAL_BDS_B2_ENA,   1);

  // I2C output protocols
  Serial.println("\n[ I2C Output Protocols ]  expected: UBX=1, NMEA=0");
  check8("I2COUTPROT UBX",  UBLOX_CFG_I2COUTPROT_UBX,  1);
  check8("I2COUTPROT NMEA", UBLOX_CFG_I2COUTPROT_NMEA, 0);

  // RTCM3 message rates on I2C
  Serial.println("\n[ RTCM3 Rates on I2C ]  expected: 1005=1, 1077=1, 1087=1, 1097=1, 1127=1, 1230=5");
  check8("RTCM 1005 (stationary ARP)", UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1005_I2C, 1);
  check8("RTCM 1077 (GPS MSM7)",       UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1077_I2C, 1);
  check8("RTCM 1087 (GLONASS MSM7)",   UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1087_I2C, 1);
  check8("RTCM 1097 (Galileo MSM7)",   UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1097_I2C, 1);
  check8("RTCM 1127 (BeiDou MSM7)",    UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1127_I2C, 1);
  check8("RTCM 1230 (GLONASS biases)", UBLOX_CFG_MSGOUT_RTCM_3X_TYPE1230_I2C, 5);

  // Live RAWX test — 5 seconds
  Serial.println("\n[ RXM-RAWX Live Test — 5 second wait ]");
  gnss.setI2COutput(COM_TYPE_UBX);
  gnss.setAutoRXMRAWXcallbackPtr(&onRAWX);
  unsigned long t = millis();
  while (millis() - t < 5000) {
    gnss.checkUblox();
    gnss.checkCallbacks();
    delay(10);
  }
  if (rawxCount > 0)
    Serial.printf("  PASS — %d RAWX message(s) received\n", rawxCount);
  else
    Serial.println("  0 messages (normal indoors — OK if no sky view)");

  // Disable automatic RAWX output so the F9P doesn't flood the I2C buffer
  // during the 60s gap between reports and cause a bus lockup.
  gnss.setAutoRXMRAWX(false);

  Serial.println("\n========================================");
  Serial.println("  Verification complete. Reprinting in 60s.");
  Serial.println("  FAIL lines need reconfiguration in u-center.");
  Serial.println("========================================");
  Serial.flush();
}
