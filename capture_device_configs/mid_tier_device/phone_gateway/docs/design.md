# phone_gateway design notes

Decisions and open items live in
`robotics/robo-services/docs/decisions/2026-11-03-phone-gateway-public-mqtt.md`.
This file covers app-internal detail.

## Pipeline

```
dongle ──BLE NUS──▶ DongleBleClient ──▶ FrameBuilder (1 s windows)
                                              │
phone GPS (FusedLocationProvider, 1–4 Hz) ────┤
                                              ▼
                              FrameLog.append(bytes)   ← always first
                                              │
                              MqttPublisher.publish(bytes)  QoS 1, ssl://mqtt.danwils.com:48213
                                              │
                              kreceiver ─▶ Iggy telemetry.raw.{imu,gps,can} ─▶ parquet + Flink
```

## Frame assembly (FrameBuilder)

- `base_timestamp_us` = phone epoch µs at the start of the wall-clock second.
- IMU: implicit grid, `sample_period_us = 1_000_000 / count`; quaternion decimated
  to every 5th sample (50 Hz → 10 Hz) with `quat_period_us = 5 * sample_period_us`,
  matching the dongle's BNO085 rates.
- GPS: phone fixes are uneven → new optional `dt_us` per fix (proto change
  2026-11-03, backward compatible).
- CAN: raw frames, `dt_us` per message from frame base. `can_allowlist` setting
  trims ids for cellular cost.
- Clock alignment v1: dongle millis → phone wall-clock at BLE arrival (includes
  BLE latency). Fine for pipeline testing.

## Auth / reconnect state machine (MqttPublisher + OidcAuth)

```
fresh token ──connect──▶ CONNECTED ──connectionLost──▶ reconnect (token still valid → works)
     ▲                          │ CONNACK 5 / connect fail
     └── silent refresh ◀───────┘
no refresh token / refresh fails ─▶ AUTH_LOST (local log only, backoff, notify)
```

- MQTT 3.1.1 validates credentials at CONNECT only; an expired token on a live
  connection keeps flowing. Broker-side `auth_opt_auth_cache_seconds=5` re-checks
  within seconds of revocation.
- `cleanSession=false`, fixed clientId = device_id: broker queues QoS 1 frames
  during short outages.
- Refresh tokens rotate on use (authentik); the new one is persisted to
  EncryptedSharedPreferences immediately.

## Local log format

`frames/frames-yyyyMMdd.bin`: repeated `[uint32 BE length][TelemetryFrame bytes]`.
Reader in `FrameLog.readAll`. Backfill tool (publish missed frames on WiFi) is a
later addition — keyed idempotently on (device_id, base_timestamp_us).

## Not yet done

- Real `./gradlew assembleDebug` (no Android SDK on the scaffold machine).
- Measure actual phone GPS fix rate (drives whether dt_us or the grid is typical).
- CAN allowlist UI; backfill tool; cert pinning (currently system CAs — the
  letsencrypt-prod cert is publicly trusted, so this is already proper TLS).
