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
        ("telemetry/ingest/user-hash", 2, True),  # own topic
        ("telemetry/ingest/someone-else", 2, False),
        ("telemetry/ingest/user-hash", 1, False),
        ("telemetry/ingest/user-hash", 4, False),
        ("telemetry/ingest/+", 2, False),
        ("telemetry/ingest/user-hash/x", 2, False),
        ("telemetry/ingest", 2, True),  # bare topic, while allow_bare_ingest_topic
        ("telemetry/ingest", 1, False),
        ("telemetry/ingest", 4, False),
        ("telemetry/other", 2, False),
        ("#", 4, False),
    ],
)
def test_acl_allows_only_publishing_to_ingest(topic, acc, allowed):
    resp = client.post("/acl", json={"username": "user-hash", "topic": topic, "clientid": "phone-1", "acc": acc})
    assert (resp.status_code == 200) is allowed


def test_bare_topic_can_be_closed(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "allow_bare_ingest_topic", False)
    bare = client.post("/acl", json={"username": "user-hash", "topic": "telemetry/ingest", "clientid": "p", "acc": 2})
    own = client.post("/acl", json={"username": "user-hash", "topic": "telemetry/ingest/user-hash", "clientid": "p", "acc": 2})
    assert (bare.status_code, own.status_code) == (403, 200)


@pytest.mark.parametrize("username", ["a/b", "+", "#", ""])
def test_usernames_with_topic_syntax_get_no_topic(username):
    topic = f"telemetry/ingest/{username}"
    resp = client.post("/acl", json={"username": username, "topic": topic, "clientid": "p", "acc": 2})
    assert resp.status_code == 403


# --- per-phone upload tokens (docs/plans/2026-11-04-reliable-uploads.md §2) ---

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402

from app import device_tokens, main  # noqa: E402

PHONE = ec.generate_private_key(ec.SECP256R1())
PHONE_PEM = PHONE.public_key().public_bytes(
    serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()


def device_token(key=PHONE, device="phone-1", sub="user-hash", life=600):
    now = int(time.time())
    return jwt.encode({"iss": f"device:{device}", "sub": sub, "aud": "mqtt-ingest", "iat": now, "exp": now + life},
                      key, algorithm="ES256")


@pytest.fixture
def registry(monkeypatch):
    calls = []
    answers = {"phone-1": ("user-hash", PHONE_PEM)}

    def fetch(device_id):
        calls.append(device_id)
        return answers.get(device_id)

    monkeypatch.setattr(main, "keys", device_tokens.KeyLookup("http://registry", fetch=fetch))
    return calls


def test_a_phone_token_from_its_registered_key_connects(registry):
    assert check_user(device_token()).status_code == 200
    assert check_user(device_token()).status_code == 200
    assert registry == ["phone-1"]  # cached


def test_phone_tokens_are_refused_when_wrong(registry):
    assert check_user(device_token(key=ec.generate_private_key(ec.SECP256R1()))).status_code == 403
    assert check_user(device_token(device="phone-2")).status_code == 403  # no key registered
    assert check_user(device_token(sub="someone-else")).status_code == 403
    assert check_user(device_token(life=3600)).status_code == 403
    assert check_user(device_token(), username="someone-else").status_code == 403


def test_registry_down_refuses_rather_than_allows(monkeypatch):
    def down(_):
        raise device_tokens.DeviceTokenRejected("registry unreachable")
    monkeypatch.setattr(main, "keys", device_tokens.KeyLookup("http://registry", fetch=down))
    assert check_user(device_token()).status_code == 403
