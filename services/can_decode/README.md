# can_decode

DBC decode of kreceiver's raw CAN records: reads Iggy `telemetry.raw.can`,
writes `telemetry.decoded.can`, and (optionally) frames the DBC cannot decode to
`telemetry.unknown.can`.

Input is kreceiver's NormalizedIngressMessage JSON, one per CAN frame, with
`payload = {can_id, data_hex, dlc, dt_us, ...}`. Output keeps every envelope
field (device, session, captured_at) with `source_type: can_decoded` and
`payload = {can_id, data_hex, dt_us, message, signals: {name: value}}`; unknown
frames get `source_type: can_unknown` and no `message`/`signals`.

The parquet archive writer reads `telemetry.raw.can` through the Iggy SDK's one
default consumer with auto-commit, so this service polls by offset with
`auto_commit=False` and keeps its offset in memory. `CAN_DECODE_START=latest`
(default) starts after the current tail; `earliest` from the first retained
message.

DBCs come from `embedded/phone-dbc-repo/dbcs/`, mounted from the
`can-decode-dbc` ConfigMap (see `canDecode` in the helm values). Those DBCs omit
the extended flag on 29-bit ids; `load_dbc` adds it.

Config (env): `IGGY_CONNECTION_STRING`, `IGGY_STREAM`, `DBC_PATH`,
`CAN_DECODE_INPUT_TOPIC`, `CAN_DECODE_OUTPUT_TOPIC`, `CAN_DECODE_UNKNOWN_TOPIC`,
`CAN_DECODE_START`, `CAN_DECODE_IDLE_SLEEP_SECONDS`, `CAN_DECODE_LOG_LEVEL`.

Test: `python -m pytest tests` (from this directory; needs cantools).

Build (from the robo-services repo root):
`docker build -f services/can_decode/Dockerfile -t dwilson2547/robo-services-can-decode:<tag> .`
