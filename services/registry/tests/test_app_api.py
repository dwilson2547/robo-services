"""Phone app API: token auth, user linking, vehicles scoped to their owner.
Needs a Postgres at the registry schema: run with DATABASE_URL=TEST_DATABASE_URL
(see services/session_tracker/README.md for a throwaway one). Skipped otherwise."""

import os
import time
from types import SimpleNamespace

import pytest

URL = os.environ.get("TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not URL or os.environ.get("DATABASE_URL") != URL,
                                reason="needs TEST_DATABASE_URL (and DATABASE_URL set to it)")

if URL and os.environ.get("DATABASE_URL") == URL:
    import jwt
    from cryptography.hazmat.primitives.asymmetric import rsa
    from fastapi.testclient import TestClient
    from sqlalchemy import text

    from app import auth
    from app.config import settings
    from app.db import engine
    from app.main import app

    KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    client = TestClient(app)


@pytest.fixture(autouse=True)
def setup(monkeypatch):
    monkeypatch.setattr(auth, "_jwks", SimpleNamespace(get_signing_key_from_jwt=lambda t: SimpleNamespace(key=KEY.public_key())))
    info = {}
    monkeypatch.setattr(auth, "fetch_userinfo", lambda token: dict(info))
    with engine.begin() as c:
        c.execute(text("TRUNCATE vehicles, users RESTART IDENTITY CASCADE"))
    return info


def bearer(sub="sub-a", **overrides):
    now = int(time.time())
    claims = {"iss": settings.authentik_issuer, "aud": settings.authentik_audience, "sub": sub,
              "iat": now, "exp": now + 600, **overrides}
    return {"Authorization": "Bearer " + jwt.encode(claims, KEY, algorithm="RS256")}


def test_requests_without_a_valid_token_are_refused():
    assert client.get("/api/app/me").status_code == 401
    assert client.get("/api/app/me", headers={"Authorization": "Bearer nonsense"}).status_code == 401
    assert client.get("/api/app/me", headers=bearer(aud="gpu-scheduler")).status_code == 401
    assert client.get("/api/app/me", headers=bearer(exp=int(time.time()) - 5)).status_code == 401


def test_first_call_creates_the_user_once(setup):
    setup.update(name="Dan", email="dan@example.com")
    first = client.get("/api/app/me", headers=bearer()).json()
    again = client.get("/api/app/me", headers=bearer()).json()
    assert first == again
    assert (first["name"], first["email"], first["sub"]) == ("Dan", "dan@example.com", "sub-a")


def test_existing_registry_user_is_linked_by_email(setup):
    with engine.begin() as c:
        uid = c.execute(text("INSERT INTO users (name, email) VALUES ('Daniel', 'dan@example.com') RETURNING id")).scalar()
    setup.update(email="dan@example.com")
    me = client.get("/api/app/me", headers=bearer()).json()
    assert (me["id"], me["sub"]) == (uid, "sub-a")


def test_without_userinfo_the_user_gets_a_placeholder_email():
    me = client.get("/api/app/me", headers=bearer("sub-z")).json()
    assert me["email"] == "sub-z@phone-gateway.invalid"


def test_vehicles_belong_to_their_owner():
    impala = {"name": "Impala", "year": 2014, "make": "Chevrolet", "model": "Impala", "can_filter": [0x1E5, 0xC9, 0xC9]}
    created = client.post("/api/app/vehicles", json=impala, headers=bearer("sub-a"))
    assert created.status_code == 201
    vid = created.json()["id"]
    assert created.json()["can_filter"] == [0xC9, 0x1E5]  # sorted, duplicates dropped

    assert [v["name"] for v in client.get("/api/app/vehicles", headers=bearer("sub-a")).json()] == ["Impala"]
    assert client.get("/api/app/vehicles", headers=bearer("sub-b")).json() == []
    assert client.put(f"/api/app/vehicles/{vid}", json={"name": "mine now"}, headers=bearer("sub-b")).status_code == 404
    assert client.delete(f"/api/app/vehicles/{vid}", headers=bearer("sub-b")).status_code == 404

    updated = client.put(f"/api/app/vehicles/{vid}", json={**impala, "name": "Impala LTZ"}, headers=bearer("sub-a"))
    assert updated.json()["name"] == "Impala LTZ"
    assert client.delete(f"/api/app/vehicles/{vid}", headers=bearer("sub-a")).status_code == 204
    assert client.get("/api/app/vehicles", headers=bearer("sub-a")).json() == []


def test_vehicle_filter_is_validated():
    too_many = {"name": "x", "can_filter": list(range(65))}
    assert client.post("/api/app/vehicles", json=too_many, headers=bearer()).status_code == 422
    assert client.post("/api/app/vehicles", json={"name": "x", "can_filter": [0x20000000]}, headers=bearer()).status_code == 422
