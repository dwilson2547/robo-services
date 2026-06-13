package com.robo.racelogger

import java.util.UUID

object BleUuids {
    val SERVICE    = UUID.fromString("6ba1c200-a5ec-4a7d-9f3e-2b8d1c05e741")
    val STATUS     = UUID.fromString("6ba1c201-a5ec-4a7d-9f3e-2b8d1c05e741")  // READ+NOTIFY  uint8: 0=boot,1=waiting,2=ready
    val MQTT_HOST  = UUID.fromString("6ba1c202-a5ec-4a7d-9f3e-2b8d1c05e741")
    val MQTT_PORT  = UUID.fromString("6ba1c203-a5ec-4a7d-9f3e-2b8d1c05e741")
    val MQTT_TOPIC = UUID.fromString("6ba1c204-a5ec-4a7d-9f3e-2b8d1c05e741")
    val MQTT_USER  = UUID.fromString("6ba1c205-a5ec-4a7d-9f3e-2b8d1c05e741")
    val MQTT_PASS  = UUID.fromString("6ba1c206-a5ec-4a7d-9f3e-2b8d1c05e741")  // WRITE only
    val COMMIT      = UUID.fromString("6ba1c207-a5ec-4a7d-9f3e-2b8d1c05e741")  // WRITE
    val STAGING     = UUID.fromString("6ba1c208-a5ec-4a7d-9f3e-2b8d1c05e741")  // WRITE
    val WIFI_SSID   = UUID.fromString("6ba1c209-a5ec-4a7d-9f3e-2b8d1c05e741")  // READ+WRITE
    val WIFI_PASS   = UUID.fromString("6ba1c20a-a5ec-4a7d-9f3e-2b8d1c05e741")  // WRITE only
    val WIFI_COMMIT = UUID.fromString("6ba1c20b-a5ec-4a7d-9f3e-2b8d1c05e741")  // WRITE → restart
    val CCCD        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
