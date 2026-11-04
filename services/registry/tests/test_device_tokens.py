"""Per-phone upload tokens: what verify() accepts and refuses (no database needed)."""

import time
import uuid

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

from app import device_tokens as dt

KEY = ec.generate_private_key(ec.SECP256R1())
PEM = KEY.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()


def token(device="phone-1", sub="sub-a", key=KEY, iat=None, life=600, **over):
    iat = int(time.time()) if iat is None else iat
    claims = {"iss": f"device:{device}", "sub": sub, "aud": "mqtt-ingest", "iat": iat, "exp": iat + life,
              "jti": uuid.uuid4().hex, **over}
    return jwt.encode(claims, key, algorithm="ES256", headers={"kid": device})


def test_a_token_from_the_registered_key_is_accepted():
    assert dt.device_of(token()) == "phone-1"
    assert dt.verify(token(), "phone-1", PEM, "sub-a")["sub"] == "sub-a"


@pytest.mark.parametrize("bad, why", [
    (lambda: token(key=ec.generate_private_key(ec.SECP256R1())), "Signature"),
    (lambda: token(sub="sub-b"), "owner"),
    (lambda: token(device="phone-2"), "issuer"),
    (lambda: token(aud="phone-gateway"), "(?i)audience"),
    (lambda: token(iat=int(time.time()) - 3600), "xpired"),
    (lambda: token(life=3600), "15 minutes"),
    (lambda: token(iat=int(time.time()) + 600), "future"),
])
def test_refused(bad, why):
    with pytest.raises(dt.DeviceTokenRejected, match=why):
        dt.verify(bad(), "phone-1", PEM, "sub-a")


def test_authentik_tokens_are_not_device_tokens():
    other = jwt.encode({"iss": "https://auth.danwils.com/application/o/phone-gateway/", "sub": "x"},
                       rsa.generate_private_key(public_exponent=65537, key_size=2048), algorithm="RS256")
    assert dt.device_of(other) is None
    assert dt.device_of("nonsense") is None


def test_only_p256_public_keys_register():
    dt.check_public_key(PEM)
    rsa_pem = rsa.generate_private_key(public_exponent=65537, key_size=2048).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    with pytest.raises(ValueError):
        dt.check_public_key(rsa_pem)
