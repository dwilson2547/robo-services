"""Per-phone upload tokens (docs/plans/2026-11-04-reliable-uploads.md §2).

At sign-in the phone creates an EC P-256 key in its hardware keystore and registers
the public half here (PUT /api/app/devices/{device_id}/upload-key). For uploads it
then signs its own short-lived JWTs with it: no refresh, nothing long-lived sent.

    header  {"alg": "ES256", "typ": "JWT", "kid": <device_id>}
    claims  {"iss": "device:<device_id>", "sub": <user's authentik sub>,
             "aud": "mqtt-ingest", "iat", "exp" (at most 15 min after iat), "jti"}

mqtt-auth verifies them for MQTT (with the key from GET /api/devices/{id}/upload-key);
the registry accepts them for the phone's catch-up (GET /api/app/sessions/{id}/frames).

A copy of services/registry/app/device_tokens.py (the two services ship separately);
keep them the same. Plus the key lookup, cached briefly so a revoked key stops
working within KEY_CACHE_S.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

ISSUER_PREFIX = "device:"
AUDIENCE = "mqtt-ingest"
MAX_LIFETIME_S = 15 * 60
MAX_SKEW_S = 60


KEY_CACHE_S = 60
MISSING_CACHE_S = 15


class DeviceTokenRejected(Exception):
    pass


class KeyLookup:
    """The registry's answer for a device's upload key: (owner sub, public key PEM) or None.

    Answers are cached (KEY_CACHE_S, MISSING_CACHE_S for none). A registry that can't be
    reached raises DeviceTokenRejected: the phone retries its CONNECT."""

    def __init__(self, registry_url: str, fetch=None, clock=time.monotonic):
        self._url = registry_url.rstrip("/")
        self._fetch = fetch or self._http
        self._clock = clock
        self._cache: dict[str, tuple[float, tuple[str, str] | None]] = {}
        self._lock = threading.Lock()

    def get(self, device_id: str) -> tuple[str, str] | None:
        now = self._clock()
        with self._lock:
            hit = self._cache.get(device_id)
        if hit is not None and hit[0] > now:
            return hit[1]
        answer = self._fetch(device_id)
        with self._lock:
            self._cache[device_id] = (now + (KEY_CACHE_S if answer else MISSING_CACHE_S), answer)
        return answer

    def _http(self, device_id: str) -> tuple[str, str] | None:
        url = f"{self._url}/api/devices/{urllib.parse.quote(device_id, safe='')}/upload-key"
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                body = json.loads(resp.read())
            return body["sub"], body["public_key_pem"]
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise DeviceTokenRejected(f"registry: HTTP {exc.code}") from exc
        except (OSError, ValueError, KeyError) as exc:
            raise DeviceTokenRejected(f"registry unreachable: {exc}") from exc


def device_of(token: str) -> str | None:
    """The device a token claims to be from (unverified), or None if it isn't a device token."""
    try:
        iss = jwt.decode(token, options={"verify_signature": False}).get("iss")
    except jwt.PyJWTError:
        return None
    if isinstance(iss, str) and iss.startswith(ISSUER_PREFIX) and len(iss) > len(ISSUER_PREFIX):
        return iss[len(ISSUER_PREFIX):]
    return None


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
