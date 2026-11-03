"""Stateless validation of authentik access tokens (JWT) against the provider JWKS.

The phone app is a public OIDC client with no secret, so it cannot call
authentik's introspection endpoint; mqtt-auth checks the token's signature and
iss/aud/exp itself.
"""

import jwt
from jwt import PyJWKClient

from .config import settings

# PyJWKClient caches the key set and refetches it when a token names a kid it
# has not seen, so authentik key rotation needs no restart.
_jwks = PyJWKClient(settings.authentik_jwks_url, lifespan=settings.jwks_cache_seconds)


class TokenRejected(Exception):
    pass


def validate_access_token(token: str) -> dict:
    """Return the verified claims, or raise TokenRejected."""
    if not token:
        raise TokenRejected("empty token")
    try:
        key = _jwks.get_signing_key_from_jwt(token)
        return jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=settings.authentik_audience,
            issuer=settings.authentik_issuer,
            options={"require": ["exp", "iat", "sub", "iss", "aud"]},
        )
    except jwt.PyJWTError as exc:
        raise TokenRejected(str(exc)) from exc
