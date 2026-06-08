#include <Arduino.h>
#include <SPI.h>

static const int PIN_TFT_MOSI = 9;  // D10
static const int PIN_TFT_SCLK = 7;  // D8
static const int PIN_TFT_MISO = 8;  // D9
static const int PIN_TFT_CS   = 4;  // D3
static const int PIN_TFT_DC   = 2;  // D1
static const int PIN_TFT_RST  = 3;  // D2
static const int PIN_TFT_BL   = 1;  // D0

static const uint16_t TFT_W = 480;
static const uint16_t TFT_H = 320;

static inline void tftSelect() { digitalWrite(PIN_TFT_CS, LOW); }
static inline void tftDeselect() { digitalWrite(PIN_TFT_CS, HIGH); }

void writeCommand(uint8_t cmd) {
  digitalWrite(PIN_TFT_DC, LOW);
  tftSelect();
  SPI.transfer(cmd);
  tftDeselect();
}

void writeData8(uint8_t data) {
  digitalWrite(PIN_TFT_DC, HIGH);
  tftSelect();
  SPI.transfer(data);
  tftDeselect();
}

void writeDataN(const uint8_t *data, size_t len) {
  digitalWrite(PIN_TFT_DC, HIGH);
  tftSelect();
  while (len--) SPI.transfer(*data++);
  tftDeselect();
}

void setAddrWindow(uint16_t x0, uint16_t y0, uint16_t x1, uint16_t y1) {
  uint8_t d[4];
  writeCommand(0x2A);
  d[0] = x0 >> 8; d[1] = x0 & 0xFF; d[2] = x1 >> 8; d[3] = x1 & 0xFF;
  writeDataN(d, 4);

  writeCommand(0x2B);
  d[0] = y0 >> 8; d[1] = y0 & 0xFF; d[2] = y1 >> 8; d[3] = y1 & 0xFF;
  writeDataN(d, 4);

  writeCommand(0x2C);
}

void fillScreen(uint16_t color565) {
  // ILI9488 expects 18-bit color over SPI; expand RGB565 -> RGB666 (3 bytes/pixel).
  uint8_t r5 = (color565 >> 11) & 0x1F;
  uint8_t g6 = (color565 >> 5) & 0x3F;
  uint8_t b5 = color565 & 0x1F;
  uint8_t r8 = (r5 * 255) / 31;
  uint8_t g8 = (g6 * 255) / 63;
  uint8_t b8 = (b5 * 255) / 31;
  uint32_t px = (uint32_t)TFT_W * TFT_H;

  setAddrWindow(0, 0, TFT_W - 1, TFT_H - 1);
  digitalWrite(PIN_TFT_DC, HIGH);
  tftSelect();
  while (px--) {
    SPI.transfer(r8);
    SPI.transfer(g8);
    SPI.transfer(b8);
  }
  tftDeselect();
}

void initILI9488() {
  digitalWrite(PIN_TFT_RST, LOW);
  delay(20);
  digitalWrite(PIN_TFT_RST, HIGH);
  delay(150);

  writeCommand(0x01); // SW reset
  delay(150);

  writeCommand(0x11); // Sleep out
  delay(150);

  writeCommand(0x3A); // Pixel format
  writeData8(0x66);   // 18-bit

  writeCommand(0x36); // MADCTL
  writeData8(0xE8);   // Landscape, mirrored + BGR (common 480x320 panel mapping)

  writeCommand(0x29); // Display on
  delay(20);
}

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\n=== minimal_display_test boot (raw SPI) ===");

  pinMode(PIN_TFT_CS, OUTPUT);
  pinMode(PIN_TFT_DC, OUTPUT);
  pinMode(PIN_TFT_RST, OUTPUT);
  pinMode(PIN_TFT_BL, OUTPUT);

  digitalWrite(PIN_TFT_BL, HIGH);
  digitalWrite(PIN_TFT_CS, HIGH);

  SPI.begin(PIN_TFT_SCLK, PIN_TFT_MISO, PIN_TFT_MOSI, PIN_TFT_CS);
  SPI.beginTransaction(SPISettings(27000000, MSBFIRST, SPI_MODE0));

  initILI9488();
  fillScreen(0xFFFF);
  delay(300);
}

void loop() {
  fillScreen(0xF800); // red
  delay(1000);
  fillScreen(0x07E0); // green
  delay(1000);
  fillScreen(0x001F); // blue
  delay(1000);
  fillScreen(0x0000); // black
  delay(1000);
}
