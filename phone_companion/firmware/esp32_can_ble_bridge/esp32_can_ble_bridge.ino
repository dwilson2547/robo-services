/*
 * esp32_can_ble_bridge.ino
 * ------------------------
 * ESP32-S3 CAN listener that bridges decoded CAN frames over BLE NUS UART.
 *
 * Hardware target:
 * - Seeed Studio XIAO ESP32-S3
 * - TJA1051 CAN transceiver: TX->D0 (GPIO1), RX<-D1 (GPIO2)
 *
 * BLE:
 * - Device name: CAN-DONGLE
 * - NUS service UUID: 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 * - TX characteristic UUID (notify): 6E400003-B5A3-F393-E0A9-E50E24DCCA9E
 */

#include <Arduino.h>
#include <NimBLEDevice.h>
#include "driver/twai.h"

namespace {

constexpr gpio_num_t kCanTxPin = GPIO_NUM_1;  // D0 (GPIO1) -> TJA1051 TXD
constexpr gpio_num_t kCanRxPin = GPIO_NUM_2;  // D1 (GPIO2) <- TJA1051 RXD
constexpr uint8_t kLedPin = 21;               // Active LOW onboard LED (XIAO ESP32-S3)
constexpr uint32_t kBaud = 500000;
constexpr uint32_t kIdleHeartbeatMs = 1000;

NimBLEServer* gServer = nullptr;
NimBLECharacteristic* gTxCharacteristic = nullptr;
NimBLEAdvertising* gAdvertising = nullptr;
bool gClientConnected = false;
bool gCanReady = false;
uint32_t gLastBlinkMs = 0;
uint32_t gLastAdvCheckMs = 0;

inline void ledOn() { digitalWrite(kLedPin, LOW); }
inline void ledOff() { digitalWrite(kLedPin, HIGH); }

class ServerCallbacks final : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* server, NimBLEConnInfo& connInfo) override {
    (void)server;
    (void)connInfo;
    gClientConnected = true;
    ledOn();
    Serial.println("[BLE] Client connected");
  }

  void onDisconnect(NimBLEServer* server, NimBLEConnInfo& connInfo, int reason) override {
    (void)server;
    (void)connInfo;
    (void)reason;
    gClientConnected = false;
    ledOff();
    Serial.println("[BLE] Client disconnected");
    // advertiseOnDisconnect(true) handles restart; loop also recovers if needed
  }
};

void initBle() {
  Serial.println("[BLE] NimBLEDevice::init...");
  NimBLEDevice::init("CAN-DONGLE");
  NimBLEDevice::setPower(9);
  Serial.println("[BLE] Creating server...");
  gServer = NimBLEDevice::createServer();
  gServer->setCallbacks(new ServerCallbacks());
  gServer->advertiseOnDisconnect(true);

  Serial.println("[BLE] Creating NUS service...");
  NimBLEService* service = gServer->createService("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
  gTxCharacteristic = service->createCharacteristic(
      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
      NIMBLE_PROPERTY::NOTIFY
  );
  service->start();

  Serial.println("[BLE] Starting advertising...");
  gAdvertising = NimBLEDevice::getAdvertising();
  gAdvertising->setName("CAN-DONGLE");
  gAdvertising->addServiceUUID(service->getUUID());
  gAdvertising->enableScanResponse(true);
  gAdvertising->start();
  Serial.println("[BLE] Advertising active as CAN-DONGLE");
}

bool initCan() {
  Serial.printf("[CAN] Init TX=GPIO%d RX=GPIO%d @ %lu baud\n",
                (int)kCanTxPin, (int)kCanRxPin, kBaud);
  twai_general_config_t gCfg =
      TWAI_GENERAL_CONFIG_DEFAULT(kCanTxPin, kCanRxPin, TWAI_MODE_LISTEN_ONLY);
  gCfg.rx_queue_len = 64;

  twai_timing_config_t tCfg = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t fCfg = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  esp_err_t err = twai_driver_install(&gCfg, &tCfg, &fCfg);
  if (err != ESP_OK) {
    Serial.printf("[CAN] Driver install failed: 0x%x\n", err);
    return false;
  }
  err = twai_start();
  if (err != ESP_OK) {
    Serial.printf("[CAN] Start failed: 0x%x\n", err);
    twai_driver_uninstall();
    return false;
  }
  return true;
}

void sendCanFrame(const twai_message_t& msg) {
  if (!gClientConnected || gTxCharacteristic == nullptr) return;

  char line[96];
  int len;
  if (msg.extd) {
    len = snprintf(line, sizeof(line), "EXT,0x%08lX,%u", msg.identifier, msg.data_length_code);
  } else {
    len = snprintf(line, sizeof(line), "STD,0x%03lX,%u", msg.identifier, msg.data_length_code);
  }

  for (uint8_t i = 0; i < msg.data_length_code && len < static_cast<int>(sizeof(line) - 4); i++) {
    len += snprintf(line + len, sizeof(line) - len, ",%02X", msg.data[i]);
  }
  snprintf(line + len, sizeof(line) - len, "\n");

  gTxCharacteristic->setValue(line);
  gTxCharacteristic->notify();
}

}  // namespace

void setup() {
  pinMode(kLedPin, OUTPUT);
  ledOff();
  Serial.begin(115200);
  delay(300);

  initBle();
  gCanReady = initCan();
  if (!gCanReady) {
    Serial.println("[WARN] CAN init failed, BLE still advertising");
  } else {
    Serial.printf("[OK] CAN @ %lu, BLE UART ready\n", kBaud);
  }
}

void loop() {
  if (!gClientConnected && gAdvertising != nullptr && millis() - gLastAdvCheckMs >= 2000) {
    gLastAdvCheckMs = millis();
    // Unconditional restart — isAdvertising() can return stale state
    gAdvertising->start();
  }

  if (gCanReady) {
    twai_message_t msg;
    if (twai_receive(&msg, pdMS_TO_TICKS(10)) == ESP_OK) {
      sendCanFrame(msg);
    }
  }

  // Heartbeat blink when idle/no client.
  uint32_t now = millis();
  if (!gClientConnected && now - gLastBlinkMs >= kIdleHeartbeatMs) {
    gLastBlinkMs = now;
    ledOn();
    delay(30);
    ledOff();
  }
}
