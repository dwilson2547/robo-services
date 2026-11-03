# mqtt-auth

Auth backend for the public Mosquitto listener (8883) used by the phone gateway.

mosquitto-go-auth (iegomez) calls this service over its HTTP backend:

- `POST /user` `{username, password, clientid}` — `password` is the authentik OIDC
  access token (JWT) from the phone app; validated statelessly against the
  provider JWKS (signature + iss + aud + exp). `username` must equal the token
  `sub`. Response: 2xx `{"ok": true}` or 403 `{"ok": false, "error": ...}`.
- `POST /superuser` — always denied; no superusers.
- `POST /acl` `{username, topic, clientid, acc}` — allows `WRITE` (acc=2) only on
  `telemetry/ingest`; everything else denied.

Config via env: `AUTHENTIK_ISSUER`, `AUTHENTIK_JWKS_URL`, `AUTHENTIK_AUDIENCE`,
`MQTT_INGEST_TOPIC` (see `helm/robo-services/values.yaml` → `mqttAuth`).

Run locally: `pip install -r requirements.txt && uvicorn app.main:app --port 8080`
Build (from the robo-services repo root):
`docker build -f services/mqtt_auth/Dockerfile -t dwilson2547/robo-services-mqtt-auth:latest .`
