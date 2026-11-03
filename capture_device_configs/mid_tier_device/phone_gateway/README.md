---
tier: project
domains: [robotics]
---

# phone_gateway

Android app that turns the phone into the telemetry gateway for the race logger:
BLE dongle (XIAO C6, dbc-forge firmware) → one `TelemetryFrame` per second →
public MQTT (TLS 8883, QoS 1) → kreceiver → Iggy → Flink/parquet, exactly like the
planned 4G ESP32 would. Phone GPS feeds `GpsSection` (with per-fix `dt_us`).

- **Auth:** authentik OIDC (PKCE, public client `phone-gateway`); the access token
  is the MQTT password, validated broker-side by mosquitto-go-auth →
  `services/mqtt_auth` (JWKS). Refresh token gives ~weekly sign-in; mid-drive
  expiry is handled by silent refresh + reconnect (MQTT 3.1.1 checks credentials
  at CONNECT only).
- **Local log:** every frame is appended to `frames/frames-yyyyMMdd.bin`
  (length-prefixed serialized frames) BEFORE publish, so outages never create
  holes in replay data. Backfill on WiFi later.
- **CAN:** sent raw; decoding happens cluster-side (see
  `robotics/robo-services` docs/decisions/2026-11-03-phone-gateway-public-mqtt.md).

BLE transport/parsers (NUS UUIDs, `STD/EXT,...` and `IMU,...` line formats,
16-bit timestamp-wrap reconstruction) are copied from
`embedded/dbc-forge/android_app/.../CanBusViewModel.kt`.

## Layout

- `android_app/` — Gradle project (AGP 8.5.2, Kotlin 1.9.24, protobuf-javalite,
  Paho MQTT, AppAuth). `app/src/main/proto/telemetry.proto` is a copy of
  `robotics/robo-services/telemetry.proto` — re-copy when the schema changes.
- `docs/design.md` — architecture notes.
- `scripts/build-install.sh` — build + adb install.

## Build / install

```bash
./scripts/build-install.sh        # needs ANDROID_HOME set
```

⚠ Unverified: no Android SDK on the dev machine at scaffold time — first real
`./gradlew assembleDebug` happens on a machine with the SDK.

## Settings (in-app / SharedPreferences)

`device_id` (default `phone-<android_id>`), `broker_host` (mqtt.danwils.com),
`broker_port` (48213, router dst-nat → 192.168.0.71:8883), `topic`
(telemetry/ingest), `can_allowlist` (empty = all ids).
