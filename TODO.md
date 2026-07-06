# TODO

Backlog imported from the retired todo store, 2026-07-06.

## High

- [ ] **Integrate WiFiManager into ESP32 datalogger** — Replace hardcoded WiFi credentials with WiFiManager captive portal. Add custom parameters for receiver IP and port so the device is fully self-configuring without re-flashing. Save custom params to Preferences flash alongside WiFi credentials.
- [ ] **Add reset button to ESP32 datalogger** — Wire GPIO 0 (built-in BOOT button on most DevKits) to wipe WiFiManager credentials on 3-second hold at boot. Calls wm.resetSettings() and relaunches the config portal. No extra hardware needed on most boards.
- [ ] **Replace JSON with MsgPack in ESP32 datalogger** — Swap ArduinoJson serializeJson for serializeMsgPack to reduce packet size ~50-60%. Field name strings disappear, floats become native binary. Update TCP receiver and Flink pipeline to deserialize MsgPack. Important before CAN bus raises message volume.
- [ ] **Replace UDP transport with MQTT in ESP32 datalogger** — Swap UDP publishPacket for MQTT publish using PubSubClient or AsyncMQTT. Deploy Mosquitto broker to robo-services Helm chart as the ingestion point. Mosquitto forwards to Iggy replacing the current UDP receiver. Use QoS 0 for low-overhead fire-and-forget (matches current UDP behavior) with option to bump to QoS 1 for reliability over internet. Fixes NAT/firewall issues when publishing from phone hotspot at track. Do after MsgPack (#50) since both touch the publish path.

## Medium

- [ ] **Try Adafruit GPS-RTK-SMA on the datalogger** — Swap in the ZED-F9P based Adafruit GPS-RTK-SMA breakout as the GPS source. Evaluate accuracy improvement and assess fit for the personal high-end logger build. Compare with current GPS at same location.
- [ ] **Add lap marker button to ESP32 datalogger** — Add a button (dedicated GPIO) that publishes a special message_type: lap_marker to the telemetry feed. User presses at start/finish line. Flink pipeline listens for marker messages to delimit lap windows for timing and analytics. This is the core of the open MyChron alternative.
- [ ] **Select GPS/IMU combo for field datalogger units** — Evaluate a mid-tier GPS + 6DOF IMU (with gyroscope) combo suitable for units sent to other users. Gyro needed for IMU feed filtering/fusion. Budget-conscious — not RTK. Should support same firmware as personal unit. Consider u-blox M8 series + ICM-42688-P or similar.

## Low

- [ ] **Research Ducati CAN bus connector and PIDs for 2013 Monster 696** — Identify the diagnostic connector type and pinout for the 2013 Monster 696 (Magneti Marelli ECU). Find community-documented CAN PIDs for lean angle, TC events, RPM, speed, gear position. Source or fabricate adapter cable to interface with ESP32/TJA1051 hardware. 2004 S4R uses pre-CAN serial protocol - out of scope.
- [ ] **Research KTM CAN bus connector and PIDs** — Identify diagnostic connector type and CAN PIDs for relevant KTM models (friend in Seattle). Determine which model years are supported. Same ESP32/TJA1051 hardware, different cable and DBC file.
- [ ] **Add SD card write buffer to ESP32 datalogger for WiFi dropout resilience** — Add SPI SD card module to buffer telemetry messages locally when WiFi is unavailable. Replay buffered messages to receiver when connection restores. Important for track use where hotspot coverage may be intermittent.
