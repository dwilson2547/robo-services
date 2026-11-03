"""mqtt-auth: the HTTP backend mosquitto-go-auth calls for the public listener.

Contract (mosquitto-go-auth backends/http.go, params and response mode json):
the broker POSTs JSON and treats a 2xx with {"ok": true} as allowed, anything
else as denied.

  POST /user      {username, password, clientid}  password = authentik access
                                                   token, username = its sub
  POST /superuser {username}                      always denied
  POST /acl       {username, topic, clientid, acc} WRITE on the ingest topic only

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


@app.post("/user")
def check_user(body: UserCheck) -> JSONResponse:
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


@app.post("/acl")
def check_acl(body: AclCheck) -> JSONResponse:
    if body.acc == MOSQ_ACL_WRITE and body.topic == settings.mqtt_ingest_topic:
        return _ok()
    return _deny(f"client {body.clientid!r}: acc={body.acc} on {body.topic!r}")


@app.get("/healthz")
def healthz() -> dict:
    return {"status": "ok"}
