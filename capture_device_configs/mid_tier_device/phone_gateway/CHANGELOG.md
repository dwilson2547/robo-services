# Changelog

## 2026-11-03

- Initial scaffold: BLE client copied from dbc-forge (NUS transport, CAN/IMU line
  parsers, timestamp-wrap fix), TelemetryFrame builder (1 Hz, IMU implicit grid +
  quat_period_us, GPS with per-fix dt_us, raw CAN with dt_us), local
  length-prefixed frame log written before publish, Paho MQTT publisher (QoS 1,
  TLS, cleanSession=false, token-as-password with silent-refresh reconnect),
  AppAuth OIDC against authentik `phone-gateway`, foreground GatewayService.
- Why: phone-as-gateway per
  `robotics/robo-services/docs/decisions/2026-11-03-phone-gateway-public-mqtt.md`.
