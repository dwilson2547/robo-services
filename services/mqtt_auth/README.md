# mqtt-auth

HTTP backend for mosquitto-go-auth on the pub-sub mosquitto's public 8883
listener (`infra/cluster-config/pub-sub/deployment.yml`), used by the phone
gateway app.

- `POST /user` `{username, password, clientid}`: `password` is an authentik
  access token from the `phone-gateway` provider, checked against the provider
  JWKS (signature, iss, aud, exp); `username` must equal its `sub`.
- `POST /superuser`: always denied.
- `POST /acl` `{username, topic, clientid, acc}`: allows publish (acc 2) on
  `telemetry/ingest` only.

2xx + `{"ok": true}` allows, 403 + `{"ok": false, "error": ...}` denies.

Config (env): `AUTHENTIK_ISSUER`, `AUTHENTIK_JWKS_URL`, `AUTHENTIK_AUDIENCE`,
`MQTT_INGEST_TOPIC` (helm values `mqttAuth`).

Test: `pip install -r requirements.txt httpx pytest && python -m pytest tests`
(from this directory).

Build (from the robo-services repo root):
`docker build -f services/mqtt_auth/Dockerfile -t dwilson2547/robo-services-mqtt-auth:<tag> .`
