import time
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from app import jwt_validator
from app.config import settings
from app.main import app

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(autouse=True)
def fake_jwks(monkeypatch):
    jwks = SimpleNamespace(get_signing_key_from_jwt=lambda token: SimpleNamespace(key=KEY.public_key()))
    monkeypatch.setattr(jwt_validator, "_jwks", jwks)


client = TestClient(app)


def token(key=KEY, **overrides) -> str:
    now = int(time.time())
    claims = {
        "iss": settings.authentik_issuer,
        "aud": settings.authentik_audience,
        "sub": "user-hash",
        "iat": now,
        "exp": now + 600,
        **overrides,
    }
    return jwt.encode(claims, key, algorithm="RS256")


def check_user(password: str, username: str = "user-hash"):
    return client.post("/user", json={"username": username, "password": password, "clientid": "phone-1"})


def test_valid_token_is_accepted():
    resp = check_user(token())
    assert resp.status_code == 200 and resp.json()["ok"] is True


@pytest.mark.parametrize(
    "bad",
    [
        {"exp": int(time.time()) - 10},
        {"iss": "https://auth.danwils.com/application/o/gpu-scheduler/"},
        {"aud": "gpu-scheduler"},
    ],
    ids=["expired", "wrong-issuer", "wrong-audience"],
)
def test_bad_claims_are_rejected(bad):
    assert check_user(token(**bad)).status_code == 403


def test_wrong_signature_is_rejected():
    assert check_user(token(key=OTHER_KEY)).status_code == 403


def test_username_must_be_the_sub():
    assert check_user(token(), username="someone-else").status_code == 403
    assert check_user(token(), username="").status_code == 403


def test_empty_password_is_rejected():
    assert check_user("").status_code == 403


def test_no_superusers():
    assert client.post("/superuser", json={"username": "user-hash"}).status_code == 403


@pytest.mark.parametrize(
    ("topic", "acc", "allowed"),
    [
        ("telemetry/ingest", 2, True),
        ("telemetry/ingest", 1, False),
        ("telemetry/ingest", 4, False),
        ("telemetry/other", 2, False),
        ("#", 4, False),
    ],
)
def test_acl_allows_only_publishing_to_ingest(topic, acc, allowed):
    resp = client.post("/acl", json={"username": "user-hash", "topic": topic, "clientid": "phone-1", "acc": acc})
    assert (resp.status_code == 200) is allowed
