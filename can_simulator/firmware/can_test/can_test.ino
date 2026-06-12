#include "driver/twai.h"

#define CAN_TX GPIO_NUM_1  // D0
#define CAN_RX GPIO_NUM_2  // D1

void setup() {
    Serial.begin(115200);
    while (!Serial && millis() < 3000);
    Serial.println("\nCAN hardware test — 500kbps");

    twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT(CAN_TX, CAN_RX, TWAI_MODE_NORMAL);
    twai_timing_config_t  t = TWAI_TIMING_CONFIG_500KBITS();
    twai_filter_config_t  f = TWAI_FILTER_CONFIG_ACCEPT_ALL();

    if (twai_driver_install(&g, &t, &f) != ESP_OK) {
        Serial.println("TWAI install FAILED"); return;
    }
    if (twai_start() != ESP_OK) {
        Serial.println("TWAI start FAILED"); return;
    }
    Serial.println("TWAI ready — waiting for frames...");
}

void loop() {
    twai_message_t msg;
    if (twai_receive(&msg, pdMS_TO_TICKS(10)) == ESP_OK) {
        Serial.printf("ID: 0x%03lX  len: %d  data:", msg.identifier, msg.data_length_code);
        for (int i = 0; i < msg.data_length_code; i++)
            Serial.printf(" %02X", msg.data[i]);
        Serial.println();
    }

    // print bus error counts every 5 seconds so we can see if the transceiver is talking at all
    static uint32_t last = 0;
    if (millis() - last > 5000) {
        last = millis();
        twai_status_info_t s;
        twai_get_status_info(&s);
        Serial.printf("[status] state=%d tx_err=%lu rx_err=%lu rx_missed=%lu\n",
            s.state, s.tx_error_counter, s.rx_error_counter, s.rx_missed_count);
    }
}
