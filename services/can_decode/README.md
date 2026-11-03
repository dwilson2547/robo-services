# can_decode

DBC decode of kreceiver's raw CAN records: reads Iggy `telemetry.raw.can`,
writes `telemetry.decoded.can`, and (optionally) frames the DBC cannot decode to
`telemetry.unknown.can`.

Input is kreceiver's NormalizedIngressMessage JSON, one per CAN frame, with
`payload = {can_id, data_hex, dlc, dt_us, ...}`. Output keeps every envelope
field (device, session, captured_at) with `source_type: can_decoded` and
`payload = {can_id, data_hex, dt_us, message, signals: {name: value}}`; unknown
frames get `source_type: can_unknown` and no `message`/`signals`.

Reads as Iggy consumer group `can-decode` (`CAN_DECODE_GROUP`). The server
stores the group's offset separately from the default consumer the parquet
archive writer uses, so a restart resumes where it stopped and neither moves the
other's position. The offset is committed every 100 messages after they are
processed and published, and exactly on SIGTERM: a crash re-decodes at most 100
frames (seen: 36 after `kill -9` mid-stream), never skips any. Publishing retries
until Iggy accepts the message, because `consume_messages()` would otherwise
commit past a frame whose callback failed.

A new group starts at the first retained message.

If the Iggy server restarts, apache-iggy 0.8's consumer silently stops (it
reconnects but never rejoins the group). A watchdog
(`can_pub_sub_probe.consumer_watchdog`) compares the topic's message count with
the consumed offset every 30 s and exits the process when the topic has grown
but the offset has not moved for 2 min, or the topic cannot be read 4 times in a
row; Kubernetes restarts it and it resumes from the committed offset. An idle
topic never trips it.

## Resetting the position

The topic has one partition, held by the group member that joined first, so
the service must be stopped while the offset is moved. Scaling it down does not
stick (Argo self-heals the replica count); disable it in git instead:

1. Set `canDecode.enabled: false` in `helm/robo-services/values.yaml`, push,
   and wait for the pod to go.
2. Run the reset with the service's image and Iggy credentials:
   ```bash
   kubectl -n robo-services run can-decode-reset --rm -i --restart=Never \
     --image=dwilson2547/robo-services-can-decode:<tag> --overrides='{"spec":{"containers":[{
       "name":"can-decode-reset","image":"dwilson2547/robo-services-can-decode:<tag>",
       "command":["python","-m","app.reset","latest"],
       "env":[{"name":"IGGY_CONNECTION_STRING","valueFrom":{"secretKeyRef":{"name":"kreceiver-secret","key":"KRECEIVER_IGGY_CONNECTION_STRING"}}}]}]}}'
   ```
   (`latest` skips what is already on the topic, `earliest` re-decodes everything
   retained, a number resumes after that offset.)
3. Set `canDecode.enabled: true` again and push.

Each session decodes with its vehicle's DBC, looked up in the registry
(`REGISTRY_URL`, `GET /api/sessions/{device}/{session}/dbc`): cached per session
for 5 minutes, each DBC compiled once per content hash. Frames with no session,
sessions whose vehicle has no DBC (or not yet known: asked again after 15 s), a
registry outage and a DBC that does not parse all use the default DBC, mounted from
the `can-decode-dbc` ConfigMap. Records carry `payload.dbc`, the DBC they were
decoded against. DBC files come from `embedded/phone-dbc-repo/dbcs/` and are
uploaded with `POST /api/dbcs/` on the registry. Those DBCs omit
the extended flag on 29-bit ids; `load_dbc` adds it.

Config (env): `IGGY_CONNECTION_STRING`, `IGGY_STREAM`, `DBC_PATH`,
`CAN_DECODE_INPUT_TOPIC`, `CAN_DECODE_OUTPUT_TOPIC`, `CAN_DECODE_UNKNOWN_TOPIC`,
`CAN_DECODE_GROUP`, `CAN_DECODE_LOG_LEVEL`, `REGISTRY_URL`, `CAN_DECODE_DEFAULT_DBC_NAME`.

Test: `python -m pytest tests` (from this directory; needs cantools).

Build (from the robo-services repo root):
`docker build -f services/can_decode/Dockerfile -t dwilson2547/robo-services-can-decode:<tag> .`
