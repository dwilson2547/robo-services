#include <Wire.h>
#include <SparkFun_u-blox_GNSS_v3.h>

SFE_UBLOX_GNSS gnss;
String results;

void setup() {
  Serial.begin(115200);
  Wire.begin();
  Wire.setClock(100000);

  results = "";

  // Try both possible addresses
  for (uint8_t addr : {(uint8_t)0x42, (uint8_t)0x21}) {
    if (gnss.begin(Wire, addr, 2000)) {
      results += "Connected at 0x" + String(addr, HEX) + "!\n";
      if (gnss.getModuleInfo()) {
        results += "  FW: ";
        results += gnss.getFirmwareType();
        results += " ";
        results += gnss.getFirmwareVersionHigh();
        results += ".";
        char lo[4]; sprintf(lo, "%02d", gnss.getFirmwareVersionLow());
        results += lo;
        results += "\n";
      }
      uint8_t addrVal = 0;
      if (gnss.getVal8(0x20510001, &addrVal, VAL_LAYER_RAM))
        results += "  CFG-I2C-ADDRESS (RAM) = 0x" + String(addrVal, HEX) + "\n";
      if (gnss.getVal8(0x20510001, &addrVal, VAL_LAYER_FLASH))
        results += "  CFG-I2C-ADDRESS (Flash) = 0x" + String(addrVal, HEX) + "\n";
      return;
    }
  }
  results = "No F9P found at 0x42 or 0x21\n";
}

void loop() {
  Serial.print(results);
  gnss.checkUblox();
  gnss.checkCallbacks();
  delay(5000);
}
