# mqtt-auth

HTTP backend for mosquitto-go-auth on the pub-sub mosquitto's public 8883
listener (`infra/cluster-config/pub-sub/deployment.yml`), used by the phone
gateway app.

- `POST /user` `{username, password, clientid}`: `password` is an authentik
  access token from the `phone-gateway` provider, checked against the provider
  JWKS (signature, iss, aud, exp); `username` must equal its `sub`.
- `POST /user` with a phone's own upload token instead (an ES256 JWT whose `iss`
  is `device:<id>`): checked against that device's key from the registry
  (`GET /api/devices/{id}/upload-key`, cached 60 s). The rules are in
  `app/device_tokens.py`, a copy of the registry's. A registry that can't be
  reached means refused.
- `POST /superuser`: always denied.
- `POST /acl` `{username, topic, clientid, acc}`: allows publish (acc 2) on
  `telemetry/ingest/<username>` only (a username containing `/`, `+` or `#` gets
  no topic), plus the shared `telemetry/ingest` while `ALLOW_BARE_INGEST_TOPIC`
  is true (the changeover). kreceiver stamps `user_sub` from the topic.

2xx + `{"ok": true}` allows, 403 + `{"ok": false, "error": ...}` denies.

Config (env): `AUTHENTIK_ISSUER`, `AUTHENTIK_JWKS_URL`, `AUTHENTIK_AUDIENCE`, `ALLOW_BARE_INGEST_TOPIC`, `REGISTRY_URL`,
`MQTT_INGEST_TOPIC` (helm values `mqttAuth`).

Test: `pip install -r requirements.txt httpx pytest && python -m pytest tests`
(from this directory).

Build (from the robo-services repo root):
`docker build -f services/mqtt_auth/Dockerfile -t dwilson2547/robo-services-mqtt-auth:<tag> .`
