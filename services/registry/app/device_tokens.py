"""Per-phone upload tokens (docs/plans/2026-11-04-reliable-uploads.md §2).

At sign-in the phone creates an EC P-256 key in its hardware keystore and registers
the public half here (PUT /api/app/devices/{device_id}/upload-key). For uploads it
then signs its own short-lived JWTs with it: no refresh, nothing long-lived sent.

    header  {"alg": "ES256", "typ": "JWT", "kid": <device_id>}
    claims  {"iss": "device:<device_id>", "sub": <user's authentik sub>,
             "aud": "mqtt-ingest", "iat", "exp" (at most 15 min after iat), "jti"}

mqtt-auth verifies them for MQTT (with the key from GET /api/devices/{id}/upload-key);
the registry accepts them for the phone's catch-up (GET /api/app/sessions/{id}/frames).
"""

from __future__ import annotations

import time

import jwt
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key

ISSUER_PREFIX = "device:"
AUDIENCE = "mqtt-ingest"
MAX_LIFETIME_S = 15 * 60
MAX_SKEW_S = 60


class DeviceTokenRejected(Exception):
    pass


def device_of(token: str) -> str | None:
    """The device a token claims to be from (unverified), or None if it isn't a device token."""
    try:
        iss = jwt.decode(token, options={"verify_signature": False}).get("iss")
    except jwt.PyJWTError:
        return None
    if isinstance(iss, str) and iss.startswith(ISSUER_PREFIX) and len(iss) > len(ISSUER_PREFIX):
        return iss[len(ISSUER_PREFIX):]
    return None


def check_public_key(pem: str) -> None:
    """Raise ValueError unless pem is an EC P-256 public key."""
    key = load_pem_public_key(pem.encode())
    if not isinstance(key, ec.EllipticCurvePublicKey) or key.curve.name != "secp256r1":
        raise ValueError("upload key must be an EC P-256 public key")


def verify(token: str, device_id: str, public_key_pem: str, owner_sub: str, now: float | None = None) -> dict:
    """The claims of a device token signed by the registered key, for its owner; else DeviceTokenRejected."""
    now = time.time() if now is None else now
    try:
        claims = jwt.decode(
            token, public_key_pem, algorithms=["ES256"], audience=AUDIENCE, issuer=ISSUER_PREFIX + device_id,
            options={"require": ["exp", "iat", "sub", "iss", "aud"], "verify_iat": False}, leeway=MAX_SKEW_S,
        )
    except jwt.PyJWTError as exc:
        raise DeviceTokenRejected(str(exc)) from exc
    if claims["sub"] != owner_sub:
        raise DeviceTokenRejected("sub is not the key's owner")
    if claims["iat"] > now + MAX_SKEW_S:
        raise DeviceTokenRejected("issued in the future")
    if claims["exp"] - claims["iat"] > MAX_LIFETIME_S:
        raise DeviceTokenRejected("lifetime over 15 minutes")
    return claims
