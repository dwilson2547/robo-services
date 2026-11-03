"""mqtt-auth: auth backend for mosquitto-go-auth (iegomez) HTTP backend.

Contract (backends/http.go in mosquitto-go-auth): the broker POSTs JSON params
and, in json response mode, expects a 2xx status plus {"ok": bool, "error": str}.
Non-2xx means unauthorized.

  POST /user      {username, password, clientid}  -> password is the authentik
                                                      access token (JWT)
  POST /superuser {username}                      -> always deny (no superusers)
  POST /acl       {username, topic, clientid, acc}-> allow WRITE only on the
                                                      ingest topic

acc values (mosquitto): READ=0x01, WRITE=0x02, SUBSCRIBE=0x04.
"""

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .config import settings
from .jwt_validator import TokenRejected, validate_access_token

MOSQ_ACL_READ = 0x01
MOSQ_ACL_WRITE = 0x02
MOSQ_ACL_SUBSCRIBE = 0x04

log = logging.getLogger("mqtt-auth")

app = FastAPI(title="mqtt-auth", version="0.1.0")


def _ok() -> JSONResponse:
    return JSONResponse({"ok": True, "error": ""}, status_code=200)


def _deny(reason: str, status: int = 403) -> JSONResponse:
    log.info("deny: %s", reason)
    return JSONResponse({"ok": False, "error": reason}, status_code=status)


@app.post("/user")
async def check_user(request: Request) -> JSONResponse:
    body = await request.json()
    username = body.get("username", "")
    password = body.get("password", "")
    try:
        claims = validate_access_token(password)
    except TokenRejected as exc:
        return _deny(f"invalid token: {exc}")
    sub = claims.get("sub", "")
    if username and sub and username != sub:
        return _deny("username does not match token sub")
    return _ok()


@app.post("/superuser")
async def check_superuser(request: Request) -> JSONResponse:
    # No superusers: every authenticated phone is confined by the ACL check.
    return _deny("no superusers")


@app.post("/acl")
async def check_acl(request: Request) -> JSONResponse:
    body = await request.json()
    topic = body.get("topic", "")
    acc = int(body.get("acc", 0))
    if acc == MOSQ_ACL_WRITE and topic == settings.mqtt_ingest_topic:
        return _ok()
    return _deny(f"acc={acc} topic={topic!r} not permitted")


@app.get("/healthz")
async def healthz() -> dict:
    return {"status": "ok"}
