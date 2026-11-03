"""Authentication for the phone app API (/api/app/*).

The phone sends its authentik access token (the same one it uses as its MQTT
password) as `Authorization: Bearer <token>`. The token is checked against the
phone-gateway provider's JWKS (signature, iss, aud, exp), and its `sub` is the
user. A sub seen for the first time becomes a registry user: linked to an existing
user with the same email (from authentik's userinfo) if there is one, else created.
"""

from __future__ import annotations

import logging

import jwt
import requests
from fastapi import Depends, Header, HTTPException
from jwt import PyJWKClient
from sqlalchemy.orm import Session

from app.config import settings
from app.db import get_db
from app.models import User, Vehicle

log = logging.getLogger("registry.auth")

# caches the key set; refetches when a token names a kid it has not seen (rotation)
_jwks = PyJWKClient(settings.authentik_jwks_url, lifespan=settings.jwks_cache_seconds)


class TokenRejected(Exception):
    pass


def validate_access_token(token: str) -> dict:
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


def fetch_userinfo(token: str) -> dict:
    """Name and email from authentik; {} if it cannot be reached (the user is still created)."""
    try:
        resp = requests.get(
            settings.authentik_userinfo_url, headers={"Authorization": f"Bearer {token}"}, timeout=5
        )
        resp.raise_for_status()
        return resp.json()
    except (requests.RequestException, ValueError):
        log.warning("userinfo unavailable", exc_info=True)
        return {}


PLACEHOLDER_DOMAIN = "@phone-gateway.invalid"


def _claims(info: dict) -> tuple[str | None, str | None]:
    email = info.get("email") if isinstance(info.get("email"), str) and info.get("email") else None
    name = next((info[k] for k in ("name", "preferred_username", "nickname") if isinstance(info.get(k), str) and info[k]), None)
    return email, name


def _complete_placeholder(db: Session, user: User, token: str) -> User:
    """A user created before authentik shared an email (a token from before the app
    asked for the email scope). Once userinfo has it: hand this sub, and anything the
    placeholder owns, to an existing unlinked user with that email; or just record it."""
    email, _ = _claims(fetch_userinfo(token))
    if not email:
        return user
    existing = db.query(User).filter(User.email == email, User.id != user.id).first()
    if existing is None:
        user.email = email
        db.commit()
        db.refresh(user)
        return user
    if existing.sub is not None:
        return user  # that email belongs to another sign-in; leave both alone
    sub = user.sub
    db.query(Vehicle).filter(Vehicle.user_id == user.id).update({Vehicle.user_id: existing.id})
    db.delete(user)
    db.flush()  # the unique sub is free before it moves
    existing.sub = sub
    db.commit()
    db.refresh(existing)
    log.info("merged placeholder user into registry user %s", existing.id)
    return existing


def user_for_sub(db: Session, sub: str, token: str) -> User:
    user = db.query(User).filter(User.sub == sub).first()
    if user is not None:
        if user.email.endswith(PLACEHOLDER_DOMAIN):
            return _complete_placeholder(db, user, token)
        return user
    email, name = _claims(fetch_userinfo(token))
    if email:
        existing = db.query(User).filter(User.email == email, User.sub.is_(None)).first()
        if existing is not None:
            existing.sub = sub
            db.commit()
            db.refresh(existing)
            log.info("linked registry user %s to sub", existing.id)
            return existing
    # users.email is required and unique; a placeholder until authentik shares one
    user = User(name=name or "phone user", email=email or f"{sub}{PLACEHOLDER_DOMAIN}", sub=sub)
    db.add(user)
    db.commit()
    db.refresh(user)
    log.info("created registry user %s for a new sub", user.id)
    return user


def current_user(authorization: str | None = Header(default=None), db: Session = Depends(get_db)) -> User:
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(status_code=401, detail="bearer token required", headers={"WWW-Authenticate": "Bearer"})
    try:
        claims = validate_access_token(token.strip())
    except TokenRejected as exc:
        raise HTTPException(status_code=401, detail=f"invalid token: {exc}", headers={"WWW-Authenticate": "Bearer"})
    return user_for_sub(db, claims["sub"], token.strip())
