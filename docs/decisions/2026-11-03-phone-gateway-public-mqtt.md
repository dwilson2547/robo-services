---
kind: decision
status: accepted
date: 2026-11-03
---

# Decision: the phone gateway publishes TelemetryFrames to the pub-sub mosquitto over a public TLS listener, with an authentik access token as the MQTT password

## Context

The XIAO C6 dongle (dbc-forge firmware) streams CAN frames and BNO085 IMU lines
over BLE. The phone app assembles one `TelemetryFrame` (`telemetry.proto`) per
second and has to get it into the existing kreceiver → Iggy → Flink/parquet
pipeline from a phone on cellular. kreceiver and mosquitto run in the `pub-sub`
namespace (`infra/cluster-config/pub-sub`); kreceiver subscribes to
`telemetry/ingest` on mosquitto's 1883 listener and accepts a raw (uncompressed)
serialized `TelemetryFrame` as the payload
(`ingest/kreceiver/kreceiver_proto/receiver_app.py`,
`normalize_packets`).

## Decision

- **Broker: the existing pub-sub mosquitto gains a second listener**, not a new
  broker. pub-sub keeps owning the messaging infrastructure. 1883 stays as it
  is (LAN/in-cluster, anonymous, kreceiver's path). 8883 is TLS with a
  cert-manager `letsencrypt-prod` certificate for `mqtt.danwils.com`. The two
  listeners need `per_listener_settings true`: without it the auth plugin and
  `allow_anonymous false` apply to 1883 as well and kreceiver is refused.
- **Endpoint:** `mqtt.danwils.com`, a Cloudflare DNS-only record kept current by
  ddclient (Cloudflare does not proxy raw TCP on the free plan). The router
  forwards WAN 48213/tcp to `192.168.0.71:8883` (`infra/router-config`).
- **Auth: the authentik access token is the MQTT password, its `sub` the
  username.** authentik provider `phone-gateway` (public client + PKCE, 30-minute
  access tokens, 7-day refresh tokens, bound to group `phone-gateway-users`).
  mosquitto runs the mosquitto-go-auth plugin
  (`iegomez/mosquitto-go-auth:3.0.0-mosquitto_2.0.18`) with its HTTP backend
  pointed at `mqtt-auth` (`services/mqtt_auth`, deployed by this repo's chart),
  which checks the JWT against the provider JWKS (signature, iss, aud, exp,
  username = sub). The phone app is a public client with no secret, so it cannot
  use authentik's introspection endpoint. ACL: publish on `telemetry/ingest` only.
- **Token expiry mid-drive:** mosquitto checks credentials at CONNECT only, so
  expiry matters only at reconnect. The app refreshes before expiry, refreshes
  once more on CONNACK 5, and otherwise keeps capturing to its local frame log
  and retries with backoff.
- **Every frame is written to a local append-only log before publishing**, so
  connectivity and auth outages cost the live stream, never the data.
- **CAN is sent raw** in `CanSection`. `services/can_decode` reads
  `telemetry.raw.can` and DBC-decodes it (DBCs from `embedded/phone-dbc-repo`)
  to `telemetry.decoded.can`, so DBC changes never need an app rebuild. It is a
  deterministic decoder; LLM agents stay on the unknown-signal side (dbc-forge).
  It tracks its own Iggy offset and never commits: the archive writer reads the
  same topic through the SDK's single default consumer.
- **Proto change:** optional `repeated uint32 dt_us` on `GpsSection` (per-fix
  offset from the frame base, like `CanMessage.dt_us`), because Android location
  callbacks are not evenly spaced. kreceiver images built before it ignore the
  field.
- **App placement:** `capture_device_configs/mid_tier_device/phone_gateway/`,
  separate from dbc-forge (the reverse-engineering tool).

## Alternatives rejected

- **WireGuard/Tailscale into the LAN:** a VPN client on every phone plus a
  router/cluster component to maintain.
- **Raw TCP or direct Iggy from the phone:** skips kreceiver's normalization and
  puts Iggy credentials on the device.
- **Plaintext 1883 on the WAN:** credentials and telemetry in cleartext.
- **A second mosquitto in the robo-services chart:** would compete with the
  pub-sub broker for 192.168.0.71 and split the messaging infrastructure.
