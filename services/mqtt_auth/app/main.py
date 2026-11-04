"""mqtt-auth: the HTTP backend mosquitto-go-auth calls for the public listener.

Contract (mosquitto-go-auth backends/http.go, params and response mode json):
the broker POSTs JSON and treats a 2xx with {"ok": true} as allowed, anything
else as denied.

  POST /user      {username, password, clientid}  password = authentik access
                                                   token, or the phone's own upload
                                                   token (device_tokens.py);
                                                   username = its sub
  POST /superuser {username}                      always denied
  POST /acl       {username, topic, clientid, acc} WRITE on <ingest>/<username>
                                                   only (and the bare ingest topic
                                                   while allow_bare_ingest_topic)

acc values: READ=1, WRITE=2, SUBSCRIBE=4. The broker calls /user only at
CONNECT; /acl never sees the token.

Handlers are plain functions so FastAPI runs them in its threadpool: the first
validation after start, or after a key rotation, fetches the JWKS synchronously.
"""

import logging

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .config import settings
from . import device_tokens
from .jwt_validator import TokenRejected, validate_access_token

MOSQ_ACL_WRITE = 0x02

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
log = logging.getLogger("mqtt-auth")
app = FastAPI(title="mqtt-auth", version="0.2.0")


class UserCheck(BaseModel):
    username: str = ""
    password: str = ""
    clientid: str = ""


class AclCheck(BaseModel):
    username: str = ""
    topic: str = ""
    clientid: str = ""
    acc: int = 0


def _ok() -> JSONResponse:
    return JSONResponse({"ok": True, "error": ""})


def _deny(reason: str) -> JSONResponse:
    log.info("deny: %s", reason)
    return JSONResponse({"ok": False, "error": reason}, status_code=403)


keys = device_tokens.KeyLookup(settings.registry_url)


@app.post("/user")
def check_user(body: UserCheck) -> JSONResponse:
    device_id = device_tokens.device_of(body.password)
    if device_id is not None:
        try:
            registered = keys.get(device_id)
            if registered is None:
                return _deny(f"client {body.clientid!r}: no upload key for {device_id!r}")
            owner, pem = registered
            claims = device_tokens.verify(body.password, device_id, pem, owner)
        except device_tokens.DeviceTokenRejected as exc:
            return _deny(f"client {body.clientid!r}: invalid device token: {exc}")
        if body.username != claims["sub"]:
            return _deny(f"client {body.clientid!r}: username is not the token's sub")
        return _ok()
    try:
        claims = validate_access_token(body.password)
    except TokenRejected as exc:
        return _deny(f"client {body.clientid!r}: invalid token: {exc}")
    if body.username != claims["sub"]:
        return _deny(f"client {body.clientid!r}: username is not the token's sub")
    return _ok()


@app.post("/superuser")
def check_superuser() -> JSONResponse:
    return _deny("no superusers")


def _user_topic(username: str) -> str | None:
    # a sub with MQTT topic syntax in it could reach someone else's topic
    if not username or any(c in username for c in "/+#") or "\x00" in username:
        return None
    return f"{settings.mqtt_ingest_topic}/{username}"


@app.post("/acl")
def check_acl(body: AclCheck) -> JSONResponse:
    if body.acc == MOSQ_ACL_WRITE:
        own = _user_topic(body.username)
        if own is not None and body.topic == own:
            return _ok()
        if settings.allow_bare_ingest_topic and body.topic == settings.mqtt_ingest_topic:
            return _ok()
    return _deny(f"client {body.clientid!r}: acc={body.acc} on {body.topic!r}")


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}
