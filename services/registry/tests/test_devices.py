"""Device auto-registration and owner linking from kreceiver's heartbeat.
Needs a Postgres at the registry schema, like test_app_api.py. Skipped otherwise."""

import os

import pytest

URL = os.environ.get("TEST_DATABASE_URL")
pytestmark = pytest.mark.skipif(not URL or os.environ.get("DATABASE_URL") != URL,
                                reason="needs TEST_DATABASE_URL (and DATABASE_URL set to it)")

if URL and os.environ.get("DATABASE_URL") == URL:
    from fastapi.testclient import TestClient
    from sqlalchemy import text

    from app.db import engine
    from app.main import app

    client = TestClient(app)


@pytest.fixture(autouse=True)
def users():
    with engine.begin() as c:
        c.execute(text("TRUNCATE devices, sessions, vehicles, dbcs, users RESTART IDENTITY CASCADE"))
        a = c.execute(text("INSERT INTO users (name, email, sub) VALUES ('A', 'a@example.com', 'sub-a') RETURNING id")).scalar()
        b = c.execute(text("INSERT INTO users (name, email, sub) VALUES ('B', 'b@example.com', 'sub-b') RETURNING id")).scalar()
    return a, b


def beat(device_id, user_sub=None):
    r = client.post("/api/devices/heartbeat", json={"device_id": device_id, "user_sub": user_sub})
    assert r.status_code == 200, r.text
    return r.json()


def test_a_new_device_is_created_and_linked_to_the_account_it_published_as(users):
    first = beat("phone-1", "sub-a")
    assert (first["created"], first["owner_email"], first["owner_conflict"]) == (True, "a@example.com", False)
    device = client.get("/api/devices/phone-1").json()
    assert (device["user_id"], device["owner_email"], device["source"]) == (users[0], "a@example.com", "auto_detected")
    assert beat("phone-1", "sub-a")["created"] is False


def test_an_unowned_device_is_linked_when_its_account_first_appears():
    assert beat("phone-1")["owner_email"] is None
    assert beat("phone-1", "sub-unknown")["owner_email"] is None  # not signed in to the registry yet
    assert beat("phone-1", "sub-b")["owner_email"] == "b@example.com"


def test_an_owned_device_is_never_moved_to_another_account(users):
    beat("phone-1", "sub-a")
    other = beat("phone-1", "sub-b")
    assert (other["owner_email"], other["owner_conflict"]) == ("a@example.com", True)
    assert client.get("/api/devices/phone-1").json()["user_id"] == users[0]


def test_the_owner_can_still_be_set_by_hand(users):
    beat("phone-1", "sub-a")
    r = client.put("/api/devices/phone-1", json={"user_id": users[1]})
    assert r.json()["owner_email"] == "b@example.com"


def test_removed_endpoints_are_gone():
    beat("phone-1")
    assert client.post("/api/devices/phone-1/claim", json={"user_id": 1}).status_code in (404, 405)
    assert client.get("/api/devices/phone-1/profile").status_code == 404  # lap job falls back on 404
    assert client.get("/api/pipelines/").status_code in (404, 405)
    assert client.post("/api/users/", json={"name": "x", "email": "x@example.com"}).status_code == 405
