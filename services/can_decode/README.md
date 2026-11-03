# can_decode

Cluster-side CAN decode hop. Consumes raw CAN messages from Iggy topic
`telemetry.raw.can` (as published by kreceiver from `CanSection` of the
TelemetryFrame — raw, undecoded by design), decodes each frame with the vehicle
DBC (from `embedded/phone-dbc-repo`, via cantools), and republishes to
`telemetry.decoded.can` with the decoded signals as JSON headers/payload.

This is deliberately a deterministic decoder, not an LLM agent: DBC changes never
require an app rebuild, and decode is cheap. LLM agents belong to the
unknown-signal reverse-engineering side (dbc-forge on the phone).

## Message contract

- Input payload: the inner `NormalizedIngressMessage` JSON (b64 envelope — see
  docs/issues/2026_05_30_iggy_flink_b64_envelope_wrapping.md); CAN frames arrive
  in its `payload` as `{id, dlc, data_hex|data_bytes, timestamp...}`.
- Output: same envelope shape, `x-source-type: can_decoded`, payload =
  `{can_id, dbc_message, signals: {name: value, ...}, raw_hex}`.

## Config (env)

`IGGY_CONNECTION_STRING`, `IGGY_STREAM` (default `can-pub-sub-probe`),
`CAN_DECODE_INPUT_TOPIC` (`telemetry.raw.can`), `CAN_DECODE_OUTPUT_TOPIC`
(`telemetry.decoded.can`), `DBC_PATH` (default `/dbcs/gm_global_a_lowspeed.dbc`,
mounted from phone-dbc-repo), `CAN_DECODE_UNKNOWN_TOPIC` (optional sink for
undecoded ids, feeds dbc-forge-style unknown analysis).

Build (from the robo-services repo root, like archive_writer):
`docker build -f services/can_decode/Dockerfile -t dwilson2547/robo-services-can-decode:latest .`
The image copies the shared `src/` pipeline package; mount phone-dbc-repo's
`dbcs/` at `/dbcs` or bake a default DBC in.
