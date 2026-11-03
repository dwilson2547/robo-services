---
kind: decision
status: accepted
date: 2026-11-03
---

# Decision: phone-as-gateway publishes TelemetryFrame to a public MQTT endpoint; OAuth token is the MQTT credential

## Context

The XIAO C6 dongle (dbc-forge firmware) already streams CAN frames and BNO085 IMU
lines over BLE. The phone app's job is to assemble one `TelemetryFrame`
(`telemetry.proto`) per second and get it into the existing kreceiver → Iggy →
Flink/parquet pipeline. kreceiver already accepts MQTT on `telemetry/ingest`
plus UDP, and splits into `telemetry.raw.{gps,imu,can}`. The open question was
how a phone on cellular reaches the cluster, and how it authenticates.

## Decision

- **Transport: MQTT (QoS 1) to a public Mosquitto endpoint**, not a VPN and not
  direct Iggy. Phone → public broker → kreceiver (LAN) → Iggy (private).
  Iggy credentials never leave the cluster; the receiver's normalization,
  registry heartbeat and diagnostics all stay on the existing path.
- **Endpoint:** `mqtt.danwils.com` (Cloudflare **DNS-only** record, updated by
  ddclient alongside the apex), random high WAN port (48213) dst-nat'd on the
  RouterOS router to MetalLB `192.168.0.71:8883`, Mosquitto TLS listener on
  8883 with a cert-manager `letsencrypt-prod` (Cloudflare DNS-01) cert issued
  into the `robo-services` namespace. Random external port is obscurity only;
  TLS + auth are the real gate.
- **Auth: authentik OIDC access token used as the MQTT password.** New public
  client (PKCE) blueprint for the phone app (`phone-gateway`, 30-min access /
  7-day refresh tokens). Mosquitto runs mosquitto-go-auth (iegomez) with its HTTP
  backend pointed at a small `mqtt-auth` validator service, which checks the JWT
  statelessly against the provider JWKS (signature + iss + aud + exp; authentik
  introspection is provider-scoped and a public client has no secret, so JWKS is
  the clean path). The broker's short revalidation cache means a revoked token
  stops publishes within seconds. CONNECT username = token `sub`. ACL:
  publish-only on `telemetry/ingest`. `allow_anonymous` removed from the public
  listener (kept on the LAN-only 1883 for kreceiver).
- **Token death mid-drive:** MQTT 3.1.1 checks credentials at CONNECT only, so
  an expired access token does not kill a live connection; the risk is at
  reconnect (CONNACK 5). App reads `exp`, refreshes proactively (<5 min margin
  and on every reconnect) with the refresh_token grant, persists the rotated
  refresh token in Keystore-backed storage, connects with
  `clean_session=false` + fixed client id for QoS 1 offline queueing, and on
  CONNACK 5 does one silent refresh + retry before falling back to
  local-only mode with backoff + notification. Every frame is written to a
  local append-only log (length-prefixed serialized frames) **before** publish
  is attempted, so auth/endpoint outages cost the live stream, never the data;
  backfill on WiFi later, idempotent on (device_id, capture timestamp).
- **CAN is sent raw/undecoded** in `CanSection`; a cluster-side decode hop
  consumes `telemetry.raw.can` and DBC-decodes (DBCs live in
  `embedded/phone-dbc-repo`), publishing decoded signals — so DBC changes never
  require an app rebuild. Deterministic decoder, not an LLM agent; LLM agents
  stay on the unknown-signal reverse-engineering side (dbc-forge).
- **Proto change:** add optional `repeated uint32 dt_us` to `GpsSection`
  (per-fix offset from frame base, same pattern as `CanMessage.dt_us`) because
  Android location callbacks are not evenly spaced. Backward compatible —
  proto3 consumers ignore unknown fields, so the current kreceiver image
  tolerates it.
- **App placement:** `capture_device_configs/mid_tier_device/phone_gateway/`,
  separate from dbc-forge (reverse-engineering tool); BLE client/parsers copied
  from dbc-forge's android_app rather than shared (two apps, copy beats a module).

## Alternatives rejected

- **WireGuard/Tailscale into the LAN:** works, but adds a VPN client on every
  phone and a router/cluster component to maintain; user preferred public.
- **Raw TCP or direct Iggy from the phone:** skips kreceiver normalization and
  puts Iggy credentials on the device.
- **Plaintext 1883 public:** credentials and telemetry in cleartext; unacceptable.

## Open verification items (before/while building)

1. kreceiver is an external image — confirm what its MQTT path accepts as
   payload on `telemetry/ingest` (raw proto vs zstd-compressed per the proto
   header comment) and that the topic is exactly `telemetry/ingest` (proto
   header says `veh/{device_id}/telem`; values.yaml + May session say
   `telemetry/ingest` — the latter is authoritative unless kreceiver changes).
2. mosquitto-go-auth exact config surface (HTTP backend URLs, JSON response mode,
   ACL semantics, cache TTL) — verify against the plugin version pinned in the
   chart; the `mqtt-auth` validator must match its expected request/response JSON.
3. Phone GPS fix rate — measure during app bring-up; sizes the `dt_us` change.
4. authentik access-token lifetime on the new provider — drives refresh cadence.
