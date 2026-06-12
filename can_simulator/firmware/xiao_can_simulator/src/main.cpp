/*
 * CAN Bus Simulator — XIAO ESP32S3
 *
 * Replays SavvyCAN CSV log files from an SD card over CAN bus.
 * Wiring:
 *   CAN  — D0(GPIO1)→TJA1051T TXD, D1(GPIO2)←TJA1051T RXD
 *        — TJA1051T S pin → GND, VCC → 5V, VIO → 3.3V
 *        — 120Ω across CANH/CANL on this end of the bench bus
 *   SD   — D8(GPIO7)→SCK, D9(GPIO8)←MISO, D10(GPIO9)→MOSI, D3(GPIO4)→CS
 *        — SD module VCC → 3.3V
 *
 * Serial console (115200):
 *   ls              list CSV files on SD
 *   play <file>     start playback
 *   stop            stop playback
 *   loop            toggle loop mode (default: on)
 *   speed <n>       playback speed multiplier (e.g. 0.5 / 1 / 2)
 *   status          show current config
 */

#include <Arduino.h>
#include <SD.h>
#include <SPI.h>
#include "driver/twai.h"
#include "esp_timer.h"

// ── Pins ──────────────────────────────────────────────────────────────────────
static constexpr gpio_num_t kCanTx  = GPIO_NUM_1;
static constexpr gpio_num_t kCanRx  = GPIO_NUM_2;
static constexpr uint8_t    kSdCs   = 4;
static constexpr uint8_t    kSdSck  = 7;
static constexpr uint8_t    kSdMiso = 8;
static constexpr uint8_t    kSdMosi = 9;

// ── State ─────────────────────────────────────────────────────────────────────
static SPIClass sdSpi(FSPI);
static bool     gLooping     = true;
static float    gSpeed       = 1.0f;
static int32_t  gMaxGapUs    = 1000000;  // cap inter-frame gaps at 1 second by default
static bool     gStopRequest = false;
static bool     gPlaying     = false;
static char     gFile[64]    = {};

// non-blocking console line buffer
static char gCmdBuf[128];
static int  gCmdLen = 0;

// ── Forward declarations ──────────────────────────────────────────────────────
void handleConsole();

// ── CAN ───────────────────────────────────────────────────────────────────────
static bool canStart() {
    twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(kCanTx, kCanRx, TWAI_MODE_NO_ACK);
    g.tx_queue_len = 32;  // default 5 is too shallow for burst replays
    twai_timing_config_t  t = TWAI_TIMING_CONFIG_500KBITS();
    twai_filter_config_t  f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
    if (twai_driver_install(&g, &t, &f) != ESP_OK) return false;
    return twai_start() == ESP_OK;
}

// Recover from bus-off if the error counter tripped. Called periodically during playback.
static void canRecoverIfNeeded() {
    twai_status_info_t s;
    if (twai_get_status_info(&s) != ESP_OK) return;
    if (s.state == TWAI_STATE_BUS_OFF) {
        twai_initiate_recovery();
        Serial.println("[CAN] bus-off — recovery initiated");
    }
}

// ── CSV parser ────────────────────────────────────────────────────────────────
// SavvyCAN format: timestamp,0xID,true|false,Rx|Tx,bus,len,d0,..,d7
static bool parseLine(char* buf, twai_message_t& msg, double& ts) {
    char* p = strtok(buf, ",");  if (!p) return false;  ts = atof(p);
    p = strtok(NULL, ",");       if (!p) return false;  msg.identifier = strtoul(p, NULL, 16);
    p = strtok(NULL, ",");       if (!p) return false;  msg.extd = (p[0] == 't') ? 1 : 0;
    strtok(NULL, ",");  // dir  — ignored, replay both Rx and Tx to reproduce full bus traffic
    strtok(NULL, ",");  // bus  — ignored
    p = strtok(NULL, ",");  if (!p) return false;
    msg.data_length_code = (uint8_t)constrain(atoi(p), 0, 8);
    msg.rtr = 0;  msg.ss = 0;  msg.self = 0;  msg.dlc_non_comp = 0;
    memset(msg.data, 0, 8);
    for (int i = 0; i < msg.data_length_code; i++) {
        p = strtok(NULL, ",\r\n");
        if (!p) break;
        msg.data[i] = (uint8_t)strtoul(p, NULL, 16);
    }
    return true;
}

// ── SD helpers ────────────────────────────────────────────────────────────────
static void listCsvFiles() {
    File root = SD.open("/");
    if (!root) { Serial.println("SD open failed"); return; }
    bool found = false;
    Serial.println("CSV files on SD:");
    for (File f = root.openNextFile(); f; f = root.openNextFile()) {
        const char* name = f.name();
        size_t n = strlen(name);
        if (!f.isDirectory() && n > 4 && strcasecmp(name + n - 4, ".csv") == 0) {
            Serial.printf("  %-36s  %lu B\n", name, (unsigned long)f.size());
            found = true;
        }
    }
    if (!found) Serial.println("  (none)");
    root.close();
}

static bool firstCsv(char* out, size_t maxLen) {
    File root = SD.open("/");
    if (!root) return false;
    for (File f = root.openNextFile(); f; f = root.openNextFile()) {
        const char* name = f.name();
        size_t n = strlen(name);
        if (!f.isDirectory() && n > 4 && strcasecmp(name + n - 4, ".csv") == 0) {
            snprintf(out, maxLen, "/%s", name);
            root.close();
            return true;
        }
    }
    root.close();
    return false;
}

// ── Playback ──────────────────────────────────────────────────────────────────
static void playFile(const char* path) {
    File f = SD.open(path);
    if (!f) { Serial.printf("Cannot open: %s\n", path); return; }

    Serial.printf("► %s  [%.1fx  loop=%s  'stop' to halt]\n",
                  path, gSpeed, gLooping ? "on" : "off");

    // skip header — any line that doesn't start with a digit
    char buf[256];
    while (f.available()) {
        int len = 0;
        while (f.available() && len < (int)sizeof(buf) - 1) {
            char c = f.read();
            if (c == '\n') break;
            if (c != '\r') buf[len++] = c;
        }
        buf[len] = '\0';
        if (isdigit((unsigned char)buf[0])) {
            // first data line — put it back by seeking, or just process it inline
            // Since SD doesn't support unget, we process it below in the main loop
            // by re-entering through the unified read path. Instead, use a flag.
            break;
        }
    }

    // Reset and replay from the first data line. Reopen to avoid seek complexity.
    f.close();
    f = SD.open(path);
    if (!f) return;

    bool     firstFrame = true;
    double   firstTs    = 0.0;
    double   prevTs     = 0.0;
    int64_t  wallNow    = 0;   // running wall-clock target in µs
    uint32_t count      = 0;

    while (f.available() && !gStopRequest) {
        // Read one line
        int len = 0;
        while (f.available() && len < (int)sizeof(buf) - 1) {
            char c = f.read();
            if (c == '\n') break;
            if (c != '\r') buf[len++] = c;
        }
        buf[len] = '\0';
        if (len == 0) continue;
        if (!isdigit((unsigned char)buf[0])) continue;  // skip header / blank

        twai_message_t msg;
        double ts;
        if (!parseLine(buf, msg, ts)) continue;

        if (firstFrame) {
            firstTs    = ts;
            prevTs     = ts;
            wallNow    = esp_timer_get_time();
            firstFrame = false;
        }

        // Clamp inter-frame gap — handles both large forward jumps (pauses, corrupt
        // timestamps) and negative jumps (timestamp rollback after corrupt frame).
        double gapUs = ts - prevTs;
        if (gapUs > gMaxGapUs || gapUs < 0) {
            if (gapUs > 0)
                Serial.printf("[gap] %.0f ms capped to %lu ms\n",
                              gapUs / 1000.0, (unsigned long)(gMaxGapUs / 1000));
            gapUs = (gapUs < 0) ? 0 : gMaxGapUs;
        }
        wallNow += (int64_t)(gapUs / gSpeed);
        prevTs   = ts;

        int64_t targetUs = wallNow;
        while (!gStopRequest) {
            int64_t remaining = targetUs - esp_timer_get_time();
            if (remaining <= 0) break;
            if (remaining > 5000) {
                handleConsole();
                canRecoverIfNeeded();
                delay(1);
            } else {
                delayMicroseconds((uint32_t)remaining);
                break;
            }
        }

        if (!gStopRequest) {
            twai_transmit(&msg, pdMS_TO_TICKS(2));
            count++;
        }
    }

    f.close();
    if (!gStopRequest) Serial.printf("■ done — %u frames sent\n", count);
}

// ── Console ───────────────────────────────────────────────────────────────────
static void processCommand(const char* cmd) {
    if (strcmp(cmd, "ls") == 0) {
        listCsvFiles();

    } else if (strncmp(cmd, "play ", 5) == 0) {
        const char* name = cmd + 5;
        snprintf(gFile, sizeof(gFile), "%s%s", name[0] == '/' ? "" : "/", name);
        gStopRequest = false;
        gPlaying     = true;

    } else if (strcmp(cmd, "stop") == 0 || strcmp(cmd, "q") == 0) {
        gStopRequest = true;
        gPlaying     = false;

    } else if (strcmp(cmd, "loop") == 0) {
        gLooping = !gLooping;
        Serial.printf("Loop: %s\n", gLooping ? "on" : "off");

    } else if (strncmp(cmd, "speed ", 6) == 0) {
        float s = atof(cmd + 6);
        gSpeed = (s > 0.0f) ? s : 1.0f;
        Serial.printf("Speed: %.2fx\n", gSpeed);

    } else if (strncmp(cmd, "maxgap ", 7) == 0) {
        int ms = atoi(cmd + 7);
        gMaxGapUs = (ms > 0) ? (int32_t)(ms * 1000) : 1000000;
        Serial.printf("Max gap: %d ms\n", (int)(gMaxGapUs / 1000));

    } else if (strcmp(cmd, "status") == 0) {
        Serial.printf("file=%s  loop=%s  speed=%.2f  playing=%s\n",
                      gFile[0] ? gFile : "(none)",
                      gLooping ? "on" : "off",
                      gSpeed,
                      gPlaying ? "yes" : "no");
        twai_status_info_t s;
        if (twai_get_status_info(&s) == ESP_OK) {
            const char* states[] = {"STOPPED","RUNNING","BUS_OFF","RECOVERING"};
            Serial.printf("CAN state=%s  tx_err=%lu  rx_err=%lu  tx_failed=%lu  rx_missed=%lu\n",
                          s.state < 4 ? states[s.state] : "?",
                          s.tx_error_counter, s.rx_error_counter,
                          s.tx_failed_count, s.rx_missed_count);
        }

    } else if (strcmp(cmd, "help") == 0) {
        Serial.println("  ls              list CSV files on SD");
        Serial.println("  play <file>     start playback");
        Serial.println("  stop            stop playback");
        Serial.println("  loop            toggle loop mode");
        Serial.println("  speed <n>       playback speed multiplier");
        Serial.println("  maxgap <ms>     cap inter-frame gaps (default 1000)");
        Serial.println("  status          show current config");

    } else if (strlen(cmd) > 0) {
        Serial.printf("Unknown: '%s'  (type 'help')\n", cmd);
    }
}

void handleConsole() {
    while (Serial.available()) {
        char c = (char)Serial.read();
        if (c == '\r') continue;
        if (c == '\n') {
            gCmdBuf[gCmdLen] = '\0';
            if (gCmdLen > 0) processCommand(gCmdBuf);
            gCmdLen = 0;
        } else if (gCmdLen < (int)sizeof(gCmdBuf) - 1) {
            gCmdBuf[gCmdLen++] = c;
        }
    }
}

// ── Arduino ───────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    while (!Serial && millis() < 3000);

    Serial.println("\nCAN Simulator — XIAO ESP32S3");
    Serial.println("─────────────────────────────");

    sdSpi.begin(kSdSck, kSdMiso, kSdMosi);
    if (!SD.begin(kSdCs, sdSpi)) {
        Serial.println("SD: init failed — check wiring and CS pin");
    } else {
        Serial.printf("SD: ready  (%llu MB)\n",
                      (unsigned long long)SD.totalBytes() >> 20);
        listCsvFiles();
    }

    if (!canStart()) {
        Serial.println("CAN: init failed");
    } else {
        Serial.println("CAN: ready @ 500 kbps");
    }

    Serial.println();

    if (firstCsv(gFile, sizeof(gFile))) {
        Serial.printf("Auto-play: %s\n", gFile);
        gPlaying = true;
    } else {
        Serial.println("No CSV found. Put a SavvyCAN log on the SD and type 'play <file>'.");
    }
}

void loop() {
    handleConsole();

    if (gPlaying && gFile[0]) {
        gStopRequest = false;
        playFile(gFile);
        if (gStopRequest || !gLooping) {
            gPlaying = false;
            if (!gStopRequest) Serial.println("Loop off — playback finished.");
        }
    } else {
        delay(10);
    }
}
