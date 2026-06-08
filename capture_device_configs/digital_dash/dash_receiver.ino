/**
 * dash_receiver.ino
 *
 * Digital dashboard receiver unit
 * Hardware : Seeed Studio XIAO ESP32-S3
 * Display  : 4.0" ILI9488-compatible 480x320 SPI TFT
 * Protocol : ESP-NOW (Wi-Fi STA mode, no AP required)
 *
 * This build uses a raw SPI ILI9488 path (no TFT_eSPI) to avoid ESP32-S3
 * driver instability observed with some ILI9488 panels/modules.
 */

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>
#include <SPI.h>
#include <Preferences.h>

// ---------------------------------------------------------------------------
// Pin definitions
// ---------------------------------------------------------------------------
#define PIN_TFT_MOSI   9   // D10
#define PIN_TFT_SCLK   7   // D8
#define PIN_TFT_MISO   8   // D9
#define PIN_TFT_CS     4   // D3
#define PIN_TFT_DC     2   // D1
#define PIN_TFT_RST    3   // D2
#define PIN_BL         1   // D0
#define PIN_TOUCH_CS   5   // D4 (XPT2046)
#define PIN_SHIFT_LED  6   // D5 (external shift-light LED)

#define DISP_W         480
#define DISP_H         320
#define SIDEBAR_W      68
#define SIDEBAR_X      (DISP_W - SIDEBAR_W)
#define MAIN_X         10
#define MAIN_W         (SIDEBAR_X - (MAIN_X * 2))

#define STATUS_X       (DISP_W - STATUS_W - 8)
#define STATUS_Y       12
#define STATUS_W       52
#define STATUS_H       34
#define STATUS_BAR_Y   (STATUS_Y + STATUS_H + 6)
#define STATUS_BAR_H   8

#define BTN_X          STATUS_X
#define BTN_W          STATUS_W
#define BTN_Y0         86
#define BTN_H          50
#define BTN_GAP        6

#define SETTINGS_BTN_W 120
#define SETTINGS_BTN_H 72
#define SETTINGS_MINUS_X  44
#define SETTINGS_PLUS_X   (SIDEBAR_X - SETTINGS_BTN_W - 44)
#define SETTINGS_BTN_Y    220
#define SETTINGS_UNIT_Y   188
#define SETTINGS_UNIT_W   96
#define SETTINGS_UNIT_H   24
#define SETTINGS_KPH_X    64
#define SETTINGS_MPH_X    (SIDEBAR_X - SETTINGS_UNIT_W - 64)

// ---------------------------------------------------------------------------
// Colour palette (RGB565)
// ---------------------------------------------------------------------------
#define C_BG        0x0000
#define C_PANEL     0x1082
#define C_ACCENT    0x07FF
#define C_WARN      0xFD20
#define C_GOOD      0x07E0
#define C_BAD       0xF800
#define C_MUTED     0x8410
#define C_WHITE     0xFFFF

// ---------------------------------------------------------------------------
// Touch config (XPT2046)
// ---------------------------------------------------------------------------
// If touch mapping is mirrored/rotated on your panel, tweak these flags/ranges.
#define TOUCH_RAW_X_MIN   200
#define TOUCH_RAW_X_MAX   3900
#define TOUCH_RAW_Y_MIN   200
#define TOUCH_RAW_Y_MAX   3900
#define TOUCH_SWAP_XY     1
#define TOUCH_INVERT_X    0
#define TOUCH_INVERT_Y    0

static const SPISettings SPI_TFT(40000000, MSBFIRST, SPI_MODE0);
static const SPISettings SPI_TOUCH(2500000, MSBFIRST, SPI_MODE0);
static Preferences g_prefs;

// ---------------------------------------------------------------------------
// Telemetry packet — must match publisher exactly
// ---------------------------------------------------------------------------
struct __attribute__((packed)) TelemetryPacket {
    uint32_t seq;
    uint32_t timestamp_ms;

    int32_t  lat_deg7;
    int32_t  lon_deg7;
    int32_t  alt_mm;
    uint16_t speed_kmh10;
    uint16_t heading_deg10;
    uint8_t  gps_fix;
    uint8_t  gps_sats;

    int16_t  accel_x_mg;
    int16_t  accel_y_mg;
    int16_t  accel_z_mg;
    int16_t  roll_deg10;
    int16_t  pitch_deg10;

    uint16_t rpm;
    uint16_t throttle_pct10;
    int16_t  coolant_c10;
    int16_t  oil_temp_c10;
    uint16_t oil_psi10;
    uint16_t batt_mv;
    int16_t  iat_c10;
    uint16_t map_kpa10;
    uint16_t lambda1000;
    int16_t  ign_deg10;
    uint16_t knock_ret_deg10;
    uint16_t fuel_rail_kpa10;
    uint8_t  fan_on;
    int16_t  stft_pct10;
    int16_t  ltft_pct10;
    uint8_t  gear;
    uint8_t  _pad;
};

static_assert(sizeof(TelemetryPacket) <= 250,
              "TelemetryPacket exceeds ESP-NOW 250-byte limit");

// ---------------------------------------------------------------------------
// Double-buffer state
// ---------------------------------------------------------------------------
static TelemetryPacket g_buf[2];
static volatile uint8_t g_write_idx = 0;
static volatile bool g_dirty = false;
static uint32_t g_last_rx = 0;
static uint32_t g_drop_count = 0;
static uint32_t g_rx_count = 0;
static uint32_t g_last_seq = UINT32_MAX;
static TelemetryPacket g_display = {};
static int g_selected_dash = 0;
static int g_prev_selected_dash = -1;
static int g_last_non_settings_dash = 1;
static int g_touch_dot_x = -1;
static int g_touch_dot_y = -1;
static bool g_layout_dirty = true;
static int g_dash0_prev_speed = -1;
static int g_dash0_prev_rpm = -1;
static int g_rpm_prev_value = -1;
static int g_rpm_prev_bg = -1;
static int g_rpm_prev_line = -1;
static bool g_health_static_drawn = false;
static int g_health_prev_afr = -1;
static int g_health_prev_load = -1;
static int g_health_prev_clt = -1;
static int g_health_prev_oilt = -1;
static int g_health_prev_oilp = -1;
static int g_health_prev_batt = -1;
static int g_settings_prev_shift_rpm = -1;
static int g_settings_prev_speed_unit = -1;
static bool g_settings_static_drawn = false;
static uint16_t g_shift_rpm = 6800;
static bool g_speed_in_mph = true;

// ---------------------------------------------------------------------------
// Raw ILI9488 SPI helpers
// ---------------------------------------------------------------------------
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

// ILI9488 over SPI expects 18-bit color. Expand RGB565 -> RGB666/888 stream.
void pushColor565Repeat(uint16_t color565, uint32_t count) {
    uint8_t r5 = (color565 >> 11) & 0x1F;
    uint8_t g6 = (color565 >> 5) & 0x3F;
    uint8_t b5 = color565 & 0x1F;
    uint8_t r8 = (r5 * 255) / 31;
    uint8_t g8 = (g6 * 255) / 63;
    uint8_t b8 = (b5 * 255) / 31;

    digitalWrite(PIN_TFT_DC, HIGH);
    tftSelect();
    while (count--) {
        SPI.transfer(r8);
        SPI.transfer(g8);
        SPI.transfer(b8);
    }
    tftDeselect();
}

void fillRectSafe(int x, int y, int w, int h, uint16_t color565) {
    if (w <= 0 || h <= 0) return;
    if (x >= DISP_W || y >= DISP_H) return;
    if (x + w <= 0 || y + h <= 0) return;

    int x0 = max(0, x);
    int y0 = max(0, y);
    int x1 = min(DISP_W - 1, x + w - 1);
    int y1 = min(DISP_H - 1, y + h - 1);

    setAddrWindow((uint16_t)x0, (uint16_t)y0, (uint16_t)x1, (uint16_t)y1);
    uint32_t px = (uint32_t)(x1 - x0 + 1) * (uint32_t)(y1 - y0 + 1);
    pushColor565Repeat(color565, px);
}

void fillScreen(uint16_t color565) {
    fillRectSafe(0, 0, DISP_W, DISP_H, color565);
}

void clearTouchDot() {
    if (g_touch_dot_x >= 0 && g_touch_dot_y >= 0) {
        fillRectSafe(g_touch_dot_x - 3, g_touch_dot_y - 3, 7, 7, C_BG);
    }
    g_touch_dot_x = -1;
    g_touch_dot_y = -1;
}

void drawTouchDot(int x, int y) {
    if (x >= SIDEBAR_X) {
        clearTouchDot();
        return;
    }
    if (g_touch_dot_x >= 0 && g_touch_dot_y >= 0) {
        fillRectSafe(g_touch_dot_x - 3, g_touch_dot_y - 3, 7, 7, C_BG);
    }
    g_touch_dot_x = x;
    g_touch_dot_y = y;
    fillRectSafe(x - 3, y - 3, 7, 7, C_ACCENT);
}

void drawTouchButtons(int selected) {
    // 4 tappable dashboard selectors under status block.
    for (int i = 0; i < 4; i++) {
        int y = BTN_Y0 + i * (BTN_H + BTN_GAP);
        uint16_t border = (i == selected) ? C_ACCENT : C_MUTED;
        uint16_t inner = (i == selected) ? C_PANEL : C_BG;

        // Simple bordered rectangle with a clean inner area for icon/text later.
        fillRectSafe(BTN_X, y, BTN_W, BTN_H, border);
        fillRectSafe(BTN_X + 2, y + 2, BTN_W - 4, BTN_H - 4, inner);

        // Simple visual marker per button (slot 1=standard, slot 2=RPM mode).
        if (i == 0) {
            fillRectSafe(BTN_X + 12, y + 12, BTN_W - 24, BTN_H - 24, C_ACCENT);
        } else if (i == 1) {
            fillRectSafe(BTN_X + 12, y + 12, BTN_W - 24, BTN_H - 24, C_BAD);
        } else if (i == 3) {
            fillRectSafe(BTN_X + 12, y + 12, BTN_W - 24, BTN_H - 24, C_WHITE);
        } else {
            fillRectSafe(BTN_X + 14, y + 14, BTN_W - 28, BTN_H - 28, C_MUTED);
        }
    }
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
    writeData8(0xE8);   // landscape mapping + BGR

    writeCommand(0x29); // Display ON
    delay(20);
}

uint16_t touchRead12(uint8_t cmd) {
    // XPT2046 returns 12-bit value left-aligned in the 16-bit read.
    digitalWrite(PIN_TOUCH_CS, LOW);
    SPI.transfer(cmd);
    uint16_t hi = SPI.transfer(0x00);
    uint16_t lo = SPI.transfer(0x00);
    digitalWrite(PIN_TOUCH_CS, HIGH);
    return ((hi << 8) | lo) >> 3;
}

bool readTouchRaw(uint16_t &rawX, uint16_t &rawY, uint16_t &rawZ) {
    tftDeselect();
    SPI.endTransaction();
    SPI.beginTransaction(SPI_TOUCH);
    digitalWrite(PIN_TOUCH_CS, HIGH);

    // Throw-away reads improve stability.
    (void)touchRead12(0xD0);
    (void)touchRead12(0x90);

    uint16_t x1 = touchRead12(0xD0);
    uint16_t y1 = touchRead12(0x90);
    uint16_t x2 = touchRead12(0xD0);
    uint16_t y2 = touchRead12(0x90);
    uint16_t z1 = touchRead12(0xB0);
    uint16_t z2 = touchRead12(0xC0);

    SPI.endTransaction();
    SPI.beginTransaction(SPI_TFT);

    rawX = (x1 + x2) / 2;
    rawY = (y1 + y2) / 2;
    rawZ = (z1 > z2) ? (z1 - z2) : (z2 - z1);

    // Basic touch validity gate.
    return (z1 > 80 && z2 < 4095 && rawX > 50 && rawY > 50);
}

bool mapTouchToScreen(uint16_t rawX, uint16_t rawY, int &sx, int &sy) {
    int tx = (int)rawX;
    int ty = (int)rawY;

#if TOUCH_SWAP_XY
    int t = tx; tx = ty; ty = t;
#endif

    tx = constrain(tx, TOUCH_RAW_X_MIN, TOUCH_RAW_X_MAX);
    ty = constrain(ty, TOUCH_RAW_Y_MIN, TOUCH_RAW_Y_MAX);

    sx = map(tx, TOUCH_RAW_X_MIN, TOUCH_RAW_X_MAX, 0, DISP_W - 1);
    sy = map(ty, TOUCH_RAW_Y_MIN, TOUCH_RAW_Y_MAX, 0, DISP_H - 1);

#if TOUCH_INVERT_X
    sx = (DISP_W - 1) - sx;
#endif
#if TOUCH_INVERT_Y
    sy = (DISP_H - 1) - sy;
#endif
    return true;
}

int touchDashButtonAt(int sx, int sy) {
    if (sx < BTN_X || sx >= (BTN_X + BTN_W)) return -1;
    for (int i = 0; i < 4; i++) {
        int y = BTN_Y0 + i * (BTN_H + BTN_GAP);
        if (sy >= y && sy < (y + BTN_H)) return i;
    }
    return -1;
}

bool touchStatusButtonAt(int sx, int sy) {
    return (sx >= STATUS_X && sx < (STATUS_X + STATUS_W) &&
            sy >= STATUS_Y && sy < (STATUS_Y + STATUS_H));
}

int touchSettingsButtonAt(int sx, int sy) {
    if (sy < SETTINGS_BTN_Y || sy >= (SETTINGS_BTN_Y + SETTINGS_BTN_H)) return 0;
    if (sx >= SETTINGS_MINUS_X && sx < (SETTINGS_MINUS_X + SETTINGS_BTN_W)) return -1;
    if (sx >= SETTINGS_PLUS_X && sx < (SETTINGS_PLUS_X + SETTINGS_BTN_W)) return +1;
    return 0;
}

int touchSettingsUnitAt(int sx, int sy) {
    if (sy < SETTINGS_UNIT_Y || sy >= (SETTINGS_UNIT_Y + SETTINGS_UNIT_H)) return -1;
    if (sx >= SETTINGS_KPH_X && sx < (SETTINGS_KPH_X + SETTINGS_UNIT_W)) return 0; // KPH
    if (sx >= SETTINGS_MPH_X && sx < (SETTINGS_MPH_X + SETTINGS_UNIT_W)) return 1; // MPH
    return -1;
}

uint16_t clampShiftRpm(int rpm) {
    return (uint16_t)constrain(rpm, 3000, 9000);
}

void persistShiftRpm() {
    g_prefs.putUShort("shift_rpm", g_shift_rpm);
}

void persistSpeedUnit() {
    g_prefs.putBool("speed_mph", g_speed_in_mph);
}

// ---------------------------------------------------------------------------
// ESP-NOW callback — fires on WiFi task, keep it lean
// ---------------------------------------------------------------------------
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
void IRAM_ATTR onReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
    (void)info;
#else
void IRAM_ATTR onReceive(const uint8_t *mac, const uint8_t *data, int len) {
    (void)mac;
#endif
    if (len != (int)sizeof(TelemetryPacket)) return;

    uint8_t wi = g_write_idx;
    memcpy(&g_buf[wi], data, sizeof(TelemetryPacket));

    const TelemetryPacket *pkt = &g_buf[wi];
    if (g_last_seq != UINT32_MAX && pkt->seq != g_last_seq + 1) {
        g_drop_count += (pkt->seq - g_last_seq - 1);
    }
    g_last_seq = pkt->seq;
    g_rx_count++;
    g_last_rx = millis();

    g_write_idx = wi ^ 1;
    g_dirty = true;
}

// ---------------------------------------------------------------------------
// Simple dashboard render
// ---------------------------------------------------------------------------
void drawStaticLayout() {
    fillScreen(C_BG);
    fillRectSafe(0, 0, SIDEBAR_X, DISP_H, C_BG);
    fillRectSafe(SIDEBAR_X, 0, SIDEBAR_W, DISP_H, C_PANEL); // status + button rail

    if (g_selected_dash == 0) {
        drawModeLabelTopLeft("SPEED");
    } else if (g_selected_dash == 1) {
        drawModeLabelTopLeft("RPM");
    } else if (g_selected_dash == 2) {
        drawModeLabelTopLeft("ENGINE");
    }
    drawTouchButtons(g_selected_dash);
}

void renderStatusIndicator(const TelemetryPacket &p) {
    (void)p;
    uint32_t age = millis() - g_last_rx;
    bool linked = age < 2000;
    fillRectSafe(STATUS_X, STATUS_Y, STATUS_W, STATUS_H, linked ? C_GOOD : C_BAD);
    fillRectSafe(STATUS_X, STATUS_BAR_Y, STATUS_W, STATUS_BAR_H, C_BG);
    int ageBar = map(constrain((int)age, 0, 4000), 0, 4000, STATUS_W, 0);
    fillRectSafe(STATUS_X, STATUS_BAR_Y, ageBar, STATUS_BAR_H, linked ? C_ACCENT : C_WARN);
}

void renderTelemetry(const TelemetryPacket &p) {
    int speed_kmh = (int)((p.speed_kmh10 + 5) / 10);
    int speed = g_speed_in_mph
        ? (int)((speed_kmh * 10 + 8) / 16)  // km/h -> mph rounded
        : speed_kmh;
    speed = constrain(speed, 0, 999);
    int rpm = constrain((int)p.rpm, 0, 9999);
    bool full = (g_dash0_prev_speed < 0 || g_dash0_prev_rpm < 0);

    if (full) {
        fillRectSafe(0, 0, SIDEBAR_X, DISP_H, C_BG);
        drawModeLabelTopLeft("SPEED");
        drawSpeedUnitBadge();
        fillRectSafe(MAIN_X, 160, MAIN_W, 2, C_WHITE);
    }

    // SPEED (3 digits)
    int sw = 96, sh = 112, st = 12, sg = 14;
    int sBlockW = sw * 3 + sg * 2;
    int sx0 = (SIDEBAR_X - sBlockW) / 2;
    int sy0 = 40;
    uint8_t s0 = (speed / 100) % 10;
    uint8_t s1 = (speed / 10) % 10;
    uint8_t s2 = speed % 10;
    int ps = (g_dash0_prev_speed < 0) ? -1 : g_dash0_prev_speed;
    uint8_t ps0 = (ps < 0) ? 255 : (uint8_t)((ps / 100) % 10);
    uint8_t ps1 = (ps < 0) ? 255 : (uint8_t)((ps / 10) % 10);
    uint8_t ps2 = (ps < 0) ? 255 : (uint8_t)(ps % 10);
    if (full || s0 != ps0) drawSegDigit(sx0 + (sw + sg) * 0, sy0, sw, sh, st, s0, C_WHITE, C_BG);
    if (full || s1 != ps1) drawSegDigit(sx0 + (sw + sg) * 1, sy0, sw, sh, st, s1, C_WHITE, C_BG);
    if (full || s2 != ps2) drawSegDigit(sx0 + (sw + sg) * 2, sy0, sw, sh, st, s2, C_WHITE, C_BG);

    // RPM (4 digits)
    int rw = 72, rh = 98, rt = 10, rg = 10;
    int rBlockW = rw * 4 + rg * 3;
    int rx0 = (SIDEBAR_X - rBlockW) / 2;
    int ry0 = 188;
    uint8_t r0 = (rpm / 1000) % 10;
    uint8_t r1 = (rpm / 100) % 10;
    uint8_t r2 = (rpm / 10) % 10;
    uint8_t r3 = rpm % 10;
    int pr = (g_dash0_prev_rpm < 0) ? -1 : g_dash0_prev_rpm;
    uint8_t pr0 = (pr < 0) ? 255 : (uint8_t)((pr / 1000) % 10);
    uint8_t pr1 = (pr < 0) ? 255 : (uint8_t)((pr / 100) % 10);
    uint8_t pr2 = (pr < 0) ? 255 : (uint8_t)((pr / 10) % 10);
    uint8_t pr3 = (pr < 0) ? 255 : (uint8_t)(pr % 10);
    if (full || r0 != pr0) drawSegDigit(rx0 + (rw + rg) * 0, ry0, rw, rh, rt, r0, C_WHITE, C_BG);
    if (full || r1 != pr1) drawSegDigit(rx0 + (rw + rg) * 1, ry0, rw, rh, rt, r1, C_WHITE, C_BG);
    if (full || r2 != pr2) drawSegDigit(rx0 + (rw + rg) * 2, ry0, rw, rh, rt, r2, C_WHITE, C_BG);
    if (full || r3 != pr3) drawSegDigit(rx0 + (rw + rg) * 3, ry0, rw, rh, rt, r3, C_WHITE, C_BG);

    g_dash0_prev_speed = speed;
    g_dash0_prev_rpm = rpm;
}

uint8_t segMaskForDigit(uint8_t d) {
    // bits: 0=A,1=B,2=C,3=D,4=E,5=F,6=G
    static const uint8_t map[10] = {
        0b0111111, // 0
        0b0000110, // 1
        0b1011011, // 2
        0b1001111, // 3
        0b1100110, // 4
        0b1101101, // 5
        0b1111101, // 6
        0b0000111, // 7
        0b1111111, // 8
        0b1101111  // 9
    };
    return (d < 10) ? map[d] : 0;
}

void drawSegDigit(int x, int y, int w, int h, int t, uint8_t digit, uint16_t col, uint16_t bg) {
    fillRectSafe(x, y, w, h, bg);
    uint8_t m = segMaskForDigit(digit);

    int midY = y + h / 2 - t / 2;
    int topY = y;
    int botY = y + h - t;
    int leftX = x;
    int rightX = x + w - t;
    int horiX = x + t;
    int horiW = w - 2 * t;
    int vertYTop = y + t;
    int vertHTop = h / 2 - t;
    int vertYBot = y + h / 2;
    int vertHBot = h / 2 - t;

    if (m & (1 << 0)) fillRectSafe(horiX, topY, horiW, t, col);     // A
    if (m & (1 << 1)) fillRectSafe(rightX, vertYTop, t, vertHTop, col); // B
    if (m & (1 << 2)) fillRectSafe(rightX, vertYBot, t, vertHBot, col); // C
    if (m & (1 << 3)) fillRectSafe(horiX, botY, horiW, t, col);     // D
    if (m & (1 << 4)) fillRectSafe(leftX, vertYBot, t, vertHBot, col);  // E
    if (m & (1 << 5)) fillRectSafe(leftX, vertYTop, t, vertHTop, col);  // F
    if (m & (1 << 6)) fillRectSafe(horiX, midY, horiW, t, col);     // G
}

uint8_t glyphRow5x7(char c, uint8_t row) {
    switch (c) {
        case 'A': { static const uint8_t g[7] = {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}; return g[row]; }
        case 'R': { static const uint8_t g[7] = {0x1E,0x11,0x11,0x1E,0x14,0x12,0x11}; return g[row]; }
        case 'B': { static const uint8_t g[7] = {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}; return g[row]; }
        case 'C': { static const uint8_t g[7] = {0x0F,0x10,0x10,0x10,0x10,0x10,0x0F}; return g[row]; }
        case 'F': { static const uint8_t g[7] = {0x1F,0x10,0x10,0x1E,0x10,0x10,0x10}; return g[row]; }
        case 'L': { static const uint8_t g[7] = {0x10,0x10,0x10,0x10,0x10,0x10,0x1F}; return g[row]; }
        case 'O': { static const uint8_t g[7] = {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E}; return g[row]; }
        case 'P': { static const uint8_t g[7] = {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}; return g[row]; }
        case 'M': { static const uint8_t g[7] = {0x11,0x1B,0x15,0x15,0x11,0x11,0x11}; return g[row]; }
        case 'S': { static const uint8_t g[7] = {0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E}; return g[row]; }
        case 'E': { static const uint8_t g[7] = {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}; return g[row]; }
        case 'T': { static const uint8_t g[7] = {0x1F,0x04,0x04,0x04,0x04,0x04,0x04}; return g[row]; }
        case 'I': { static const uint8_t g[7] = {0x1F,0x04,0x04,0x04,0x04,0x04,0x1F}; return g[row]; }
        case 'N': { static const uint8_t g[7] = {0x11,0x19,0x15,0x13,0x11,0x11,0x11}; return g[row]; }
        case 'G': { static const uint8_t g[7] = {0x0E,0x11,0x10,0x17,0x11,0x11,0x0E}; return g[row]; }
        case 'D': { static const uint8_t g[7] = {0x1E,0x11,0x11,0x11,0x11,0x11,0x1E}; return g[row]; }
        case 'K': { static const uint8_t g[7] = {0x11,0x12,0x14,0x18,0x14,0x12,0x11}; return g[row]; }
        case 'H': { static const uint8_t g[7] = {0x11,0x11,0x11,0x1F,0x11,0x11,0x11}; return g[row]; }
        default: return 0x00;
    }
}

void drawText5x7(int x, int y, const char *text, uint16_t color, int scale) {
    int cx = x;
    while (*text) {
        char c = *text++;
        if (c == ' ') {
            cx += 6 * scale;
            continue;
        }
        for (int row = 0; row < 7; row++) {
            uint8_t bits = glyphRow5x7(c, (uint8_t)row);
            for (int col = 0; col < 5; col++) {
                if (bits & (1 << (4 - col))) {
                    fillRectSafe(cx + col * scale, y + row * scale, scale, scale, color);
                }
            }
        }
        cx += 6 * scale;
    }
}

void drawModeLabelTopLeft(const char *label) {
    int scale = 2;
    int char_w = 5 * scale;
    int gap = 1 * scale;
    int text_w = 0;
    for (const char *p = label; *p; ++p) {
        text_w += (*p == ' ') ? (6 * scale) : (char_w + gap);
    }
    if (text_w > 0) text_w -= gap;
    int box_x = MAIN_X;
    int box_y = 16;
    int box_w = text_w + 12;
    int box_h = 7 * scale + 8;
    fillRectSafe(box_x, box_y, box_w, box_h, C_PANEL);
    drawText5x7(box_x + 6, box_y + 4, label, C_WHITE, scale);
}

int pow10i(int p) {
    int out = 1;
    while (p-- > 0) out *= 10;
    return out;
}

void drawSegNumberValue(int x, int y, int digits, int value, int minVal, int maxVal,
                        int w, int h, int t, int gap, uint16_t fg, uint16_t bg,
                        int &prev, bool force) {
    value = constrain(value, minVal, maxVal);
    int old = prev;
    bool full = force || (old < 0);

    for (int i = 0; i < digits; i++) {
        int place = pow10i(digits - 1 - i);
        uint8_t d = (uint8_t)((value / place) % 10);
        uint8_t pd = (old < 0) ? 255 : (uint8_t)((old / place) % 10);
        if (full || d != pd) {
            drawSegDigit(x + i * (w + gap), y, w, h, t, d, fg, bg);
        }
    }

    prev = value;
}

void drawSpeedUnitBadge() {
    const char *unit = g_speed_in_mph ? "MPH" : "KPH";
    int scale = 2;
    int box_w = 44;
    int box_h = 22;
    int box_x = SIDEBAR_X - box_w - 10;
    int box_y = 16;
    fillRectSafe(box_x, box_y, box_w, box_h, C_PANEL);
    drawText5x7(box_x + 5, box_y + 4, unit, C_WHITE, scale);
}

void renderRpmMode(const TelemetryPacket &p) {
    const uint16_t SHIFT_RPM = g_shift_rpm;
    uint16_t bg = C_BG; // Keep RPM dashboard stable: black background, white digits.
    int rpm = constrain((int)p.rpm, 0, 9999);

    uint8_t d0 = (rpm / 1000) % 10;
    uint8_t d1 = (rpm / 100) % 10;
    uint8_t d2 = (rpm / 10) % 10;
    uint8_t d3 = rpm % 10;

    int digitW = 72;
    int digitH = 150;
    int thick = 12;
    int gap = 14;
    int blockW = digitW * 4 + gap * 3;
    int startX = (SIDEBAR_X - blockW) / 2;
    int startY = (DISP_H - digitH) / 2;
    uint16_t fg = C_WHITE;

    bool full = (g_rpm_prev_bg != (int)bg) || (g_rpm_prev_value < 0);
    if (full) {
        fillRectSafe(0, 0, SIDEBAR_X, DISP_H, bg);
        fillRectSafe(0, 0, SIDEBAR_X, 8, C_BG);
        fillRectSafe(0, DISP_H - 8, SIDEBAR_X, 8, C_BG);
        drawModeLabelTopLeft("RPM");
    }

    int prev = (g_rpm_prev_value < 0) ? -1 : g_rpm_prev_value;
    uint8_t p0 = (prev < 0) ? 255 : (uint8_t)((prev / 1000) % 10);
    uint8_t p1 = (prev < 0) ? 255 : (uint8_t)((prev / 100) % 10);
    uint8_t p2 = (prev < 0) ? 255 : (uint8_t)((prev / 10) % 10);
    uint8_t p3 = (prev < 0) ? 255 : (uint8_t)(prev % 10);

    if (full || p0 != d0) drawSegDigit(startX + (digitW + gap) * 0, startY, digitW, digitH, thick, d0, fg, bg);
    if (full || p1 != d1) drawSegDigit(startX + (digitW + gap) * 1, startY, digitW, digitH, thick, d1, fg, bg);
    if (full || p2 != d2) drawSegDigit(startX + (digitW + gap) * 2, startY, digitW, digitH, thick, d2, fg, bg);
    if (full || p3 != d3) drawSegDigit(startX + (digitW + gap) * 3, startY, digitW, digitH, thick, d3, fg, bg);

    // Visual shift threshold marker line.
    int lineY = DISP_H - 26;
    uint16_t lineCol = (p.rpm >= SHIFT_RPM) ? C_BAD : C_BG;
    int lineTag = (int)lineCol;
    if (full || g_rpm_prev_line != lineTag) {
        fillRectSafe(14, lineY, SIDEBAR_X - 28, 8, lineCol);
    }

    g_rpm_prev_value = rpm;
    g_rpm_prev_bg = bg;
    g_rpm_prev_line = lineTag;
}

void renderEngineHealthMode(const TelemetryPacket &p) {
    int clt = constrain((int)((p.coolant_c10 + 5) / 10), 0, 199);
    int oilt = constrain((int)((p.oil_temp_c10 + 5) / 10), 0, 199);
    int oilp = constrain((int)((p.oil_psi10 + 5) / 10), 0, 199);
    int batt = constrain((int)((p.batt_mv + 50) / 100), 0, 199); // decivolts (e.g. 126=12.6V)
    int afr = constrain((int)((p.lambda1000 * 147 + 5000) / 10000), 0, 99); // whole-number AFR
    int map_kpa = (int)((p.map_kpa10 + 5) / 10);
    int load = constrain((int)((map_kpa * 100 + 50) / 101), 0, 199); // MAP-based load %

    bool full = !g_health_static_drawn;
    if (full) {
        fillRectSafe(0, 0, SIDEBAR_X, DISP_H, C_BG);
        drawModeLabelTopLeft("ENGINE");

        fillRectSafe((SIDEBAR_X / 2) - 2, 46, 4, DISP_H - 62, C_PANEL);
        fillRectSafe(14, 126, SIDEBAR_X - 28, 2, C_PANEL);
        fillRectSafe(14, 214, SIDEBAR_X - 28, 2, C_PANEL);

        drawText5x7(24, 48, "OILP", C_WHITE, 2);
        drawText5x7(224, 48, "OILT", C_WHITE, 2);
        drawText5x7(24, 136, "CLT", C_WHITE, 2);
        drawText5x7(224, 136, "BATT", C_WHITE, 2);
        drawText5x7(24, 224, "AFR", C_WHITE, 2);
        drawText5x7(224, 224, "LOAD", C_WHITE, 2);

        g_health_static_drawn = true;
        g_health_prev_afr = -1;
        g_health_prev_load = -1;
        g_health_prev_clt = -1;
        g_health_prev_oilt = -1;
        g_health_prev_oilp = -1;
        g_health_prev_batt = -1;
    }

    drawSegNumberValue(24, 68, 3, oilp, 0, 199, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_oilp, false);
    drawSegNumberValue(224, 68, 3, oilt, 0, 199, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_oilt, false);
    drawSegNumberValue(24, 156, 3, clt, 0, 199, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_clt, false);
    drawSegNumberValue(224, 156, 3, batt, 0, 199, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_batt, false);
    drawSegNumberValue(24, 244, 2, afr, 0, 99, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_afr, false);
    drawSegNumberValue(224, 244, 3, load, 0, 199, 28, 44, 6, 6, C_WHITE, C_BG, g_health_prev_load, false);
}

void renderSettingsMode() {
    int shift = (int)g_shift_rpm;
    int speedUnit = g_speed_in_mph ? 1 : 0;

    auto drawSettingsUnitSelector = [&]() {
        if (g_settings_prev_speed_unit == speedUnit) return;
        g_settings_prev_speed_unit = speedUnit;

        uint16_t kphBorder = g_speed_in_mph ? C_MUTED : C_ACCENT;
        uint16_t mphBorder = g_speed_in_mph ? C_ACCENT : C_MUTED;

        fillRectSafe(SETTINGS_KPH_X, SETTINGS_UNIT_Y, SETTINGS_UNIT_W, SETTINGS_UNIT_H, kphBorder);
        fillRectSafe(SETTINGS_KPH_X + 2, SETTINGS_UNIT_Y + 2, SETTINGS_UNIT_W - 4, SETTINGS_UNIT_H - 4, C_BG);
        drawText5x7(SETTINGS_KPH_X + 18, SETTINGS_UNIT_Y + 5, "KPH", C_WHITE, 2);

        fillRectSafe(SETTINGS_MPH_X, SETTINGS_UNIT_Y, SETTINGS_UNIT_W, SETTINGS_UNIT_H, mphBorder);
        fillRectSafe(SETTINGS_MPH_X + 2, SETTINGS_UNIT_Y + 2, SETTINGS_UNIT_W - 4, SETTINGS_UNIT_H - 4, C_BG);
        drawText5x7(SETTINGS_MPH_X + 18, SETTINGS_UNIT_Y + 5, "MPH", C_WHITE, 2);
    };

    if (!g_settings_static_drawn) {
        fillRectSafe(0, 0, SIDEBAR_X, DISP_H, C_BG);
        fillRectSafe(0, 0, SIDEBAR_X, 8, C_BG);
        fillRectSafe(0, DISP_H - 8, SIDEBAR_X, 8, C_BG);
        drawModeLabelTopLeft("SETTINGS");

        // - button
        fillRectSafe(SETTINGS_MINUS_X, SETTINGS_BTN_Y, SETTINGS_BTN_W, SETTINGS_BTN_H, C_MUTED);
        fillRectSafe(SETTINGS_MINUS_X + 3, SETTINGS_BTN_Y + 3, SETTINGS_BTN_W - 6, SETTINGS_BTN_H - 6, C_BG);
        fillRectSafe(SETTINGS_MINUS_X + 24, SETTINGS_BTN_Y + (SETTINGS_BTN_H / 2) - 5, SETTINGS_BTN_W - 48, 10, C_WHITE);

        // + button
        fillRectSafe(SETTINGS_PLUS_X, SETTINGS_BTN_Y, SETTINGS_BTN_W, SETTINGS_BTN_H, C_MUTED);
        fillRectSafe(SETTINGS_PLUS_X + 3, SETTINGS_BTN_Y + 3, SETTINGS_BTN_W - 6, SETTINGS_BTN_H - 6, C_BG);
        fillRectSafe(SETTINGS_PLUS_X + 24, SETTINGS_BTN_Y + (SETTINGS_BTN_H / 2) - 5, SETTINGS_BTN_W - 48, 10, C_WHITE);
        fillRectSafe(SETTINGS_PLUS_X + (SETTINGS_BTN_W / 2) - 5, SETTINGS_BTN_Y + 18, 10, SETTINGS_BTN_H - 36, C_WHITE);

        g_settings_static_drawn = true;
        g_settings_prev_shift_rpm = -1;
        g_settings_prev_speed_unit = -1;
    }

    drawSettingsUnitSelector();

    if (g_settings_prev_shift_rpm == shift) return;
    g_settings_prev_shift_rpm = shift;

    // Redraw only the numeric value.
    int digitW = 64;
    int digitH = 130;
    int thick = 10;
    int gap = 12;
    int blockW = digitW * 4 + gap * 3;
    int startX = (SIDEBAR_X - blockW) / 2;
    int startY = 52;
    uint8_t d0 = (shift / 1000) % 10;
    uint8_t d1 = (shift / 100) % 10;
    uint8_t d2 = (shift / 10) % 10;
    uint8_t d3 = shift % 10;
    drawSegDigit(startX + (digitW + gap) * 0, startY, digitW, digitH, thick, d0, C_WHITE, C_BG);
    drawSegDigit(startX + (digitW + gap) * 1, startY, digitW, digitH, thick, d1, C_WHITE, C_BG);
    drawSegDigit(startX + (digitW + gap) * 2, startY, digitW, digitH, thick, d2, C_WHITE, C_BG);
    drawSegDigit(startX + (digitW + gap) * 3, startY, digitW, digitH, thick, d3, C_WHITE, C_BG);
}

// ---------------------------------------------------------------------------
// setup()
// ---------------------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\n=== dash_receiver boot (ILI9488 raw SPI) ===");

    g_prefs.begin("dashcfg", false);
    g_shift_rpm = clampShiftRpm((int)g_prefs.getUShort("shift_rpm", 6800));
    g_speed_in_mph = g_prefs.getBool("speed_mph", true);

    pinMode(PIN_TFT_CS, OUTPUT);
    pinMode(PIN_TFT_DC, OUTPUT);
    pinMode(PIN_TFT_RST, OUTPUT);
    pinMode(PIN_BL, OUTPUT);
    pinMode(PIN_TOUCH_CS, OUTPUT);
    pinMode(PIN_SHIFT_LED, OUTPUT);
    digitalWrite(PIN_BL, HIGH);
    digitalWrite(PIN_TFT_CS, HIGH);
    digitalWrite(PIN_TOUCH_CS, HIGH);
    digitalWrite(PIN_SHIFT_LED, LOW);

    SPI.begin(PIN_TFT_SCLK, PIN_TFT_MISO, PIN_TFT_MOSI, PIN_TFT_CS);
    SPI.beginTransaction(SPI_TFT);

    initILI9488();
    drawStaticLayout();

    WiFi.mode(WIFI_STA);
    WiFi.disconnect();

    uint8_t mac[6];
    esp_wifi_get_mac(WIFI_IF_STA, mac);
    char mac_str[24];
    snprintf(mac_str, sizeof(mac_str), "%02X:%02X:%02X:%02X:%02X:%02X",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    Serial.print("Receiver MAC: ");
    Serial.println(mac_str);

    if (esp_now_init() != ESP_OK) {
        Serial.println("ESP-NOW init failed");
        while (true) delay(1000);
    }
    esp_now_register_recv_cb(onReceive);
    Serial.println("ESP-NOW ready, waiting for packets...");

    g_last_rx = millis();
}

// ---------------------------------------------------------------------------
// loop()
// ---------------------------------------------------------------------------
void loop() {
    static uint32_t last_render_ms = 0;
    static uint32_t last_touch_ms = 0;
    static bool has_packet = false;
    static bool prev_touch_down = false;

    if (g_dirty) {
        noInterrupts();
        uint8_t read_idx = g_write_idx ^ 1;
        memcpy(&g_display, &g_buf[read_idx], sizeof(TelemetryPacket));
        g_dirty = false;
        interrupts();
        has_packet = true;
    }

    uint32_t now = millis();
    if (now - last_touch_ms >= 35) {
        last_touch_ms = now;
        uint16_t rawX = 0, rawY = 0, rawZ = 0;
        bool touch_down = readTouchRaw(rawX, rawY, rawZ);
        bool just_pressed = touch_down && !prev_touch_down;
        if (touch_down) {
            int sx = 0, sy = 0;
            if (mapTouchToScreen(rawX, rawY, sx, sy)) {
                drawTouchDot(sx, sy);
                int btn = touchDashButtonAt(sx, sy);

                if (just_pressed) {
                    if (touchStatusButtonAt(sx, sy)) {
                        if (g_selected_dash == 3) {
                            g_selected_dash = g_last_non_settings_dash;
                        } else {
                            g_last_non_settings_dash = g_selected_dash;
                            g_selected_dash = 3;
                        }
                        g_layout_dirty = true;
                        g_dash0_prev_speed = -1;
                        g_dash0_prev_rpm = -1;
                        g_rpm_prev_value = -1;
                        g_rpm_prev_bg = -1;
                        g_rpm_prev_line = -1;
                        g_health_static_drawn = false;
                        g_settings_prev_shift_rpm = -1;
                    } else if (g_selected_dash == 3) {
                        int delta = touchSettingsButtonAt(sx, sy);
                        if (delta != 0) {
                            g_shift_rpm = clampShiftRpm((int)g_shift_rpm + delta * 100);
                            persistShiftRpm();
                            g_settings_prev_shift_rpm = -1;
                            g_dash0_prev_speed = -1;
                            g_dash0_prev_rpm = -1;
                            g_rpm_prev_value = -1;
                            g_rpm_prev_bg = -1;
                            g_rpm_prev_line = -1;
                            g_health_static_drawn = false;
                            Serial.printf("shift rpm set to %u\n", g_shift_rpm);
                        } else {
                            int unitSel = touchSettingsUnitAt(sx, sy);
                            if (unitSel >= 0) {
                                bool newMph = (unitSel == 1);
                                if (newMph != g_speed_in_mph) {
                                    g_speed_in_mph = newMph;
                                    persistSpeedUnit();
                                    g_settings_prev_speed_unit = -1;
                                    g_dash0_prev_speed = -1;
                                    g_dash0_prev_rpm = -1;
                                    Serial.printf("speed unit set to %s\n", g_speed_in_mph ? "MPH" : "KPH");
                                }
                            }
                        }
                        if (btn >= 0 && btn != g_selected_dash) {
                            if (btn != 3) g_last_non_settings_dash = btn;
                            g_selected_dash = btn;
                            g_layout_dirty = true;
                            g_dash0_prev_speed = -1;
                            g_dash0_prev_rpm = -1;
                            g_health_static_drawn = false;
                        }
                    } else if (btn >= 0 && btn != g_selected_dash) {
                        if (btn != 3) g_last_non_settings_dash = btn;
                        g_selected_dash = btn;
                        g_layout_dirty = true;
                        g_dash0_prev_speed = -1;
                        g_dash0_prev_rpm = -1;
                        g_rpm_prev_value = -1;
                        g_rpm_prev_bg = -1;
                        g_rpm_prev_line = -1;
                        g_health_static_drawn = false;
                    }
                }

                if (just_pressed) {
                    Serial.printf("touch down raw(%u,%u) z=%u screen(%d,%d) btn=%d\n",
                                  rawX, rawY, rawZ, sx, sy, btn);
                }
            }
        } else if (prev_touch_down) {
            clearTouchDot();
            Serial.println("touch up");
        }
        prev_touch_down = touch_down;
    }

    if (g_selected_dash != g_prev_selected_dash) {
        g_prev_selected_dash = g_selected_dash;
        g_layout_dirty = true;
        g_dash0_prev_speed = -1;
        g_dash0_prev_rpm = -1;
        g_health_static_drawn = false;
        g_settings_static_drawn = false;
        g_settings_prev_shift_rpm = -1;
    }
    if (g_layout_dirty) {
        drawStaticLayout();
        g_layout_dirty = false;
    }

    uint32_t target_render_ms = (g_selected_dash == 3) ? 100 : 40;
    if (has_packet && (now - last_render_ms >= target_render_ms)) {
        last_render_ms = now;
        if (g_selected_dash == 1) {
            renderRpmMode(g_display);
        } else if (g_selected_dash == 2) {
            renderEngineHealthMode(g_display);
        } else if (g_selected_dash == 3) {
            renderSettingsMode();
        } else {
            renderTelemetry(g_display);
        }
        renderStatusIndicator(g_display);

        if (g_selected_dash == 1 && g_display.rpm >= g_shift_rpm) {
            bool led_flash = (((now / 120) & 1) != 0);
            digitalWrite(PIN_SHIFT_LED, led_flash ? HIGH : LOW);
        } else {
            digitalWrite(PIN_SHIFT_LED, LOW);
        }
    } else if (!has_packet && (now - last_render_ms >= 500)) {
        // Idle heartbeat on status panel while no packets received.
        last_render_ms = now;
        bool blink = ((now / 500) & 1) != 0;
        fillRectSafe(STATUS_X, STATUS_Y, STATUS_W, STATUS_H, blink ? C_WARN : C_PANEL);
        digitalWrite(PIN_SHIFT_LED, LOW);
    }

    delay(1);
}
