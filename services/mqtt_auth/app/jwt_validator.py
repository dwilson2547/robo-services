"""Stateless validation of authentik OIDC access tokens (JWT) against the
provider JWKS.

authentik introspection is provider-scoped and the phone app is a public OIDC
client (no secret), so the broker-side validator checks the JWT signature via
JWKS plus iss/aud/exp instead. Revocation therefore lands within one access
token lifetime; the broker's short auth/acl cache bounds it further.
"""

import time

import jwt
from jwt import PyJWKClient

from .config import settings


class _RefreshingJWKClient:
    """PyJWKClient with a simple time-based refresh of the JWKS document."""

    def __init__(self, jwks_url: str, refresh_seconds: int) -> None:
        self._client = PyJWKClient(jwks_url)
        self._refresh_seconds = refresh_seconds
        self._fetched_at = 0.0

    def get_signing_key_from_jwt(self, token: str):
        if time.monotonic() - self._fetched_at > self._refresh_seconds:
            self._client = PyJWKClient(self._client.uri)  # refetch JWKS
            self._fetched_at = time.monotonic()
        return self._client.get_signing_key_from_jwt(token)


_jwks = _RefreshingJWKClient(settings.authentik_jwks_url, settings.jwks_refresh_seconds)


class TokenRejected(Exception):
    pass


def validate_access_token(token: str) -> dict:
    """Return the verified claims, or raise TokenRejected."""
    if not token:
        raise TokenRejected("empty token")
    try:
        key = _jwks.get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=settings.authentik_audience,
            issuer=settings.authentik_issuer,
            options={"require": ["exp", "iat", "sub", "iss", "aud"]},
        )
    except jwt.PyJWTError as exc:
        raise TokenRejected(str(exc)) from exc
    # authentik marks revoked/deactivated sessions via token_use or by expiry;
    # a non-"access" token_type must never authenticate to MQTT.
    if claims.get("token_type") not in (None, "access"):
        raise TokenRejected(f"unexpected token_type {claims.get('token_type')!r}")
    return claims
