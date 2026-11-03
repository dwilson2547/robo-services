# Changelog

## 2026-11-03

- Rewrote the app. The first scaffold never compiled (four compile errors and a
  manifest-merge failure) and its reconnect, sign-in and buffering logic had
  bugs; it was replaced rather than patched.
- Sign-in: AppAuth (authorization code + PKCE) against the authentik
  `phone-gateway` provider, redirect `com.robo.phonegateway:/oauth2redirect`.
  AuthState is persisted after every change and refreshes are serialized,
  because authentik rotates the refresh token on each use.
- Broker: one Paho client for the service's lifetime, QoS 1 over TLS, a fresh
  access token on every CONNECT. A refused CONNECT gets one forced token
  refresh, then exponential backoff. Paho's disconnected buffer (on disk, 600
  frames) carries short outages; frames published before the first connect
  are delivered once it connects (tested against mosquitto-go-auth + mqtt-auth).
- The broker is tried at `mqtt.danwils.com:48213`, then at `192.168.0.71:8883`
  for the home network (the router does not hairpin the WAN address). The
  certificate is checked against `mqtt.danwils.com` for both.
- Frames: one TelemetryFrame per wall-clock second, closed 1.5 s after it ends so
  late BLE batches still land. IMU on the proto's implicit 50 Hz grid (nearest
  sample per grid point) and 10 Hz quaternion grid; a second without full IMU
  coverage gets no IMU section rather than invented samples. GPS fixes carry
  `dt_us`; CAN is raw with `dt_us`. Built from the repo-root `telemetry.proto`,
  not a copy.
- Every frame is written to the frame log (one file per UTC day) before it is
  handed to the publisher.
- BLE: auto-connects to the remembered dongle (or the first NUS device seen) and
  keeps reconnecting while capture runs. Line parsing and the 16-bit timestamp
  reconstruction follow dbc-forge's, now as a tested pure function.
