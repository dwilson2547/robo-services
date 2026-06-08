/**
 * dash_publisher_stub.ino
 *
 * Standalone publisher stub — sends synthetic telemetry over ESP-NOW
 * so you can develop and tune the display without real sensor hardware.
 *
 * Hardware : any spare XIAO ESP32-S3
 * No peripherals needed — just USB power
 *
 * Usage:
 *   1. Flash dash_receiver to the display unit, note its MAC from Serial / splash
 *   2. Paste that MAC into RECEIVER_MAC below
 *   3. Flash this sketch to the second XIAO
 *   4. Both power up — display should show live synthetic data immediately
 */

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <math.h>

// ---------------------------------------------------------------------------
// *** PASTE RECEIVER MAC HERE ***
// Read it from Serial or the splash screen on the receiver
// ---------------------------------------------------------------------------
static uint8_t RECEIVER_MAC[6] = { 0x1C, 0xDB, 0xD4, 0x45, 0x0C, 0x80 };

// ---------------------------------------------------------------------------
// Shared packet definition — keep identical to receiver
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

static_assert(sizeof(TelemetryPacket) <= 250, "Packet too large");

// ---------------------------------------------------------------------------
// Helpers — smooth synthetic signals
// ---------------------------------------------------------------------------

// Returns a value that ramps up and down on a given period
static float triangle(float period_s, float phase_s = 0) {
    float t = (millis() / 1000.0f + phase_s);
    float x = fmod(t, period_s) / period_s;   // 0..1
    return (x < 0.5f) ? (x * 2.0f) : (2.0f - x * 2.0f);  // 0..1..0
}

static float sine(float period_s, float phase_s = 0) {
    float t = (millis() / 1000.0f + phase_s);
    return sinf(2.0f * M_PI * t / period_s);  // -1..1
}

// Compute gear from speed (simple 6-speed approximation)
static uint8_t speedToGear(float speed_kmh) {
    if (speed_kmh <  10) return 0;
    if (speed_kmh <  30) return 1;
    if (speed_kmh <  60) return 2;
    if (speed_kmh <  90) return 3;
    if (speed_kmh < 130) return 4;
    if (speed_kmh < 175) return 5;
    return 6;
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
static uint32_t g_seq = 0;
static bool     g_peer_added = false;

// ---------------------------------------------------------------------------
// Send callback (optional logging)
// ---------------------------------------------------------------------------
#if defined(ESP_ARDUINO_VERSION_MAJOR) && (ESP_ARDUINO_VERSION_MAJOR >= 3)
void onSent(const wifi_tx_info_t *info, esp_now_send_status_t status) {
    (void)info;
    (void)status;
}
#else
void onSent(const uint8_t *mac, esp_now_send_status_t status) {
    (void)mac;
    (void)status;
}
#endif

// ---------------------------------------------------------------------------
// setup()
// ---------------------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\n=== dash_publisher_stub boot ===");

    WiFi.mode(WIFI_STA);
    WiFi.disconnect();
    if (WiFi.setTxPower(WIFI_POWER_8_5dBm)) {
        Serial.println("WiFi TX power set to 8.5 dBm");
    } else {
        Serial.println("WiFi TX power update failed");
    }

    Serial.print("Publisher MAC: ");
    Serial.println(WiFi.macAddress());

    if (esp_now_init() != ESP_OK) {
        Serial.println("ESP-NOW init failed — halting");
        while (true) delay(1000);
    }

    esp_now_register_send_cb(onSent);

    // Register receiver as peer
    esp_now_peer_info_t peer = {};
    memcpy(peer.peer_addr, RECEIVER_MAC, 6);
    peer.channel = 0;
    peer.encrypt = false;

    if (esp_now_add_peer(&peer) != ESP_OK) {
        Serial.println("Failed to add peer — check MAC address");
    } else {
        g_peer_added = true;
        Serial.println("Peer registered — sending telemetry");
    }
}

// ---------------------------------------------------------------------------
// loop()
// ---------------------------------------------------------------------------
void loop() {
    if (!g_peer_added) { delay(100); return; }

    TelemetryPacket pkt = {};
    pkt.seq          = g_seq++;
    pkt.timestamp_ms = millis();

    // GPS — slow orbit around a fixed point (Salt Lake City area for Utah test)
    float orbit_r = 0.002f;   // ~220 m radius
    float orbit_t = millis() / 60000.0f * 2.0f * M_PI;  // full lap per minute
    pkt.lat_deg7   = (int32_t)((40.7608f + orbit_r * sinf(orbit_t)) * 1e7f);
    pkt.lon_deg7   = (int32_t)((-111.8910f + orbit_r * cosf(orbit_t)) * 1e7f);
    pkt.alt_mm     = (int32_t)(1320000 + sine(30.0f) * 5000);  // ~1320 m, ±5 m
    pkt.gps_fix    = 2;   // 3D fix
    pkt.gps_sats   = 12 + (uint8_t)(sine(17.0f) * 3);

    // Speed — slower 0-170 km/h sweep for dashboard/shift-light testing
    float speed_kmh  = triangle(90.0f) * 170.0f;
    pkt.speed_kmh10  = (uint16_t)(speed_kmh * 10.0f);

    // Heading — slow rotation
    float hdg        = fmod(millis() / 100.0f, 360.0f);
    pkt.heading_deg10 = (uint16_t)(hdg * 10.0f);

    // IMU — lateral g oscillates like a sweeping corner
    float lat_g = sine(8.0f, 0.0f) * 1.2f;     // ±1.2g lateral
    float lon_g = sine(6.0f, 1.5f) * 0.8f;     // ±0.8g longitudinal
    pkt.accel_x_mg   = (int16_t)(lat_g * 1000.0f);
    pkt.accel_y_mg   = (int16_t)(lon_g * 1000.0f);
    pkt.accel_z_mg   = (int16_t)(1000 + sine(3.0f) * 50);   // ~1g vertical + small bounce
    pkt.roll_deg10   = (int16_t)(lat_g * -25.0f * 10.0f);   // roll proportional to lateral g
    pkt.pitch_deg10  = (int16_t)(lon_g * -10.0f * 10.0f);   // pitch proportional to longitudinal g

    // CAN — separate slower RPM sweep so threshold behavior is easy to observe.
    float rpm_sweep  = 1200.0f + triangle(60.0f, 3.0f) * 7000.0f; // 1200..8200
    pkt.rpm          = (uint16_t)constrain(rpm_sweep, 800.0f, 9000.0f);
    float throttle = triangle(90.0f);
    pkt.throttle_pct10 = (uint16_t)(throttle * 1000.0f);

    // Engine-health signals (synthetic but realistic ranges).
    float coolant_c = 72.0f + triangle(220.0f, 9.0f) * 34.0f;      // 72..106 C
    float oil_temp_c = 78.0f + triangle(180.0f, 17.0f) * 42.0f;    // 78..120 C
    float oil_psi = 18.0f + (pkt.rpm / 9000.0f) * 72.0f + sine(11.0f) * 2.5f;
    float batt_v = 12.4f + (1.0f - throttle) * 1.4f + sine(7.0f, 0.4f) * 0.08f;

    pkt.coolant_c10 = (int16_t)(coolant_c * 10.0f);
    pkt.oil_temp_c10 = (int16_t)(oil_temp_c * 10.0f);
    pkt.oil_psi10 = (uint16_t)constrain((int)(oil_psi * 10.0f), 0, 2000);
    pkt.batt_mv = (uint16_t)constrain((int)(batt_v * 1000.0f), 11000, 15000);

    float iat_c = 24.0f + throttle * 18.0f + sine(13.0f, 0.7f) * 2.0f; // ~22..44C
    float map_kpa = 28.0f + throttle * 130.0f + sine(4.0f, 0.2f) * 6.0f; // ~22..164kPa
    float lambda = 1.03f - throttle * 0.17f + sine(9.0f, 1.1f) * 0.015f; // ~0.86..1.05
    float ign_deg = 9.0f + (1.0f - throttle) * 24.0f + sine(7.5f, 2.3f) * 2.0f; // ~7..35 deg
    float knock_deg = max(0.0f, 1.0f + sine(5.5f, 1.9f) * 1.2f + (throttle > 0.8f ? 1.4f : 0.0f));
    float fuel_rail_kpa = 300.0f + throttle * 180.0f + sine(6.8f, 0.5f) * 14.0f; // ~286..494 kPa
    float stft = sine(10.0f, 0.3f) * 8.0f + (throttle - 0.5f) * 2.0f; // ~-10..+10%
    float ltft = sine(90.0f, 0.8f) * 3.5f; // slow drift ~-3.5..+3.5%

    pkt.iat_c10 = (int16_t)(iat_c * 10.0f);
    pkt.map_kpa10 = (uint16_t)constrain((int)(map_kpa * 10.0f), 0, 3000);
    pkt.lambda1000 = (uint16_t)constrain((int)(lambda * 1000.0f), 650, 1400);
    pkt.ign_deg10 = (int16_t)(ign_deg * 10.0f);
    pkt.knock_ret_deg10 = (uint16_t)constrain((int)(knock_deg * 10.0f), 0, 120);
    pkt.fuel_rail_kpa10 = (uint16_t)constrain((int)(fuel_rail_kpa * 10.0f), 0, 8000);
    pkt.fan_on = (coolant_c >= 96.0f) ? 1 : 0;
    pkt.stft_pct10 = (int16_t)constrain((int)(stft * 10.0f), -250, 250);
    pkt.ltft_pct10 = (int16_t)constrain((int)(ltft * 10.0f), -200, 200);
    pkt.gear         = speedToGear(speed_kmh);

    // Send
    esp_err_t result = esp_now_send(RECEIVER_MAC, (uint8_t *)&pkt, sizeof(pkt));
    if (result != ESP_OK) {
        Serial.println("Send error");
    }

    // ~10 Hz publish rate
    delay(100);
}
