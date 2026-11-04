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
        c.execute(text("TRUNCATE sessions, vehicles, dbcs, tracks, devices, users RESTART IDENTITY CASCADE"))
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


def test_placeholder_user_is_merged_once_an_email_arrives(setup):
    with engine.begin() as c:
        old = c.execute(text("INSERT INTO users (name, email) VALUES ('Daniel', 'dan@example.com') RETURNING id")).scalar()
    setup.update(name="Daniel Wilson")  # first token: no email scope
    first = client.get("/api/app/me", headers=bearer()).json()
    assert first["email"].endswith("@phone-gateway.invalid")
    client.post("/api/app/vehicles", json={"name": "Impala"}, headers=bearer())

    setup.update(email="dan@example.com")  # signed in again with the email scope
    me = client.get("/api/app/me", headers=bearer()).json()
    assert (me["id"], me["sub"], me["email"]) == (old, "sub-a", "dan@example.com")
    assert [v["name"] for v in client.get("/api/app/vehicles", headers=bearer()).json()] == ["Impala"]
    with engine.connect() as c:
        assert c.execute(text("SELECT count(*) FROM users")).scalar() == 1


def test_placeholder_gets_the_email_when_no_one_else_has_it(setup):
    client.get("/api/app/me", headers=bearer("sub-q"))
    setup.update(email="new@example.com")
    assert client.get("/api/app/me", headers=bearer("sub-q")).json()["email"] == "new@example.com"


def test_an_email_owned_by_another_sign_in_is_not_taken(setup):
    setup.update(email="dan@example.com")
    client.get("/api/app/me", headers=bearer("sub-a"))
    setup.clear()
    client.get("/api/app/me", headers=bearer("sub-b"))  # placeholder
    setup.update(email="dan@example.com")
    me_b = client.get("/api/app/me", headers=bearer("sub-b")).json()
    assert me_b["email"].endswith("@phone-gateway.invalid")


def test_dbcs_and_the_session_lookup_can_decode_uses(setup):
    from app.models import RecordingSession  # noqa: F401  (table exists via migrations)

    created = client.post("/api/dbcs/", json={"name": "chevy_impala_2008_hscan", "content": "VERSION \"\"\n"})
    assert created.status_code == 201
    dbc = created.json()
    assert client.post("/api/dbcs/", json={"name": "chevy_impala_2008_hscan", "content": "x"}).status_code == 409
    assert [d["name"] for d in client.get("/api/app/dbcs", headers=bearer()).json()] == ["chevy_impala_2008_hscan"]
    assert client.post("/api/app/vehicles", json={"name": "x", "dbc_id": 9999}, headers=bearer()).status_code == 422

    vid = client.post("/api/app/vehicles", json={"name": "Impala", "dbc_id": dbc["id"]}, headers=bearer()).json()["id"]
    with engine.begin() as c:
        c.execute(text("INSERT INTO sessions (source_session, device_id, vehicle_id) VALUES ('100', 'phone-1', :v), ('101', 'phone-1', NULL)"), {"v": vid})
    hit = client.get("/api/sessions/phone-1/100/dbc").json()
    assert (hit["name"], hit["sha256"], hit["content"]) == ("chevy_impala_2008_hscan", dbc["sha256"], "VERSION \"\"\n")
    assert client.get("/api/sessions/phone-1/101/dbc").json()["detail"] == "session has no vehicle"
    assert client.get("/api/sessions/phone-1/999/dbc").status_code == 404

    replaced = client.put(f"/api/dbcs/{dbc['id']}", json={"name": "chevy_impala_2008_hscan", "content": "VERSION \"2\"\n"}).json()
    assert replaced["sha256"] != dbc["sha256"]
    assert client.get("/api/sessions/phone-1/100/dbc").json()["sha256"] == replaced["sha256"]


def test_dbc_roles_are_validated_kept_on_replace_and_served_with_the_session_dbc():
    content = 'BO_ 1001 ECMVehicleSpeed: 8 ECM\n SG_ VehicleSpeed : 7|15@0+ (0.01,0) [0|0] "mph" Vector__XXX\n'
    bad = client.post("/api/dbcs/", json={"name": "car", "content": content, "roles": {"rpm": {"signal": "EngineRPM"}}})
    assert bad.status_code == 422 and "EngineRPM" in str(bad.json())
    roles = {"speed_kph": {"signal": "VehicleSpeed", "unit": "mph"}}
    created = client.post("/api/dbcs/", json={"name": "car", "content": content, "roles": roles}).json()
    assert created["roles"] == roles
    # replacing the file without roles keeps them, as long as the signals still exist
    kept = client.put(f"/api/dbcs/{created['id']}", json={"name": "car", "content": content + "\n"}).json()
    assert kept["roles"] == roles
    gone = client.put(f"/api/dbcs/{created['id']}", json={"name": "car", "content": "BO_ 1 X: 1 Y\n SG_ Other : 0|1@1+ (1,0) [0|1] \"\" Z\n"})
    assert gone.status_code == 422
    with engine.begin() as c:
        uid = c.execute(text("INSERT INTO users (name, email, sub) VALUES ('A', 'a@x.com', 's') RETURNING id")).scalar()
        vid = c.execute(text("INSERT INTO vehicles (user_id, name, dbc_id) VALUES (:u, 'Car', :d) RETURNING id"),
                        {"u": uid, "d": created["id"]}).scalar()
        c.execute(text("INSERT INTO sessions (source_session, device_id, vehicle_id) VALUES ('1', 'phone-1', :v)"), {"v": vid})
    assert client.get("/api/sessions/phone-1/1/dbc").json()["roles"] == roles


def test_session_summary_is_served_to_its_owner_only(setup):
    setup.update(email="a@example.com")
    client.get("/api/app/me", headers=bearer("sub-a"))  # creates the user with its sub
    with engine.begin() as c:
        pk = c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub) VALUES ('77', 'phone-1', 'sub-a') RETURNING id")).scalar()
    assert client.get("/api/app/sessions/77/summary", headers=bearer("sub-a")).json()["detail"] == "no summary yet"
    with engine.begin() as c:
        c.execute(text("INSERT INTO session_summaries (session_pk, version, frames_used, frames_received, metrics)"
                       " VALUES (:p, 1, 10, 10, '{\"distance_km\": {\"gps\": 1.5}}')"), {"p": pk})
    mine = client.get("/api/app/sessions/77/summary", headers=bearer("sub-a")).json()
    assert (mine["metrics"]["distance_km"]["gps"], mine["frames_used"]) == (1.5, 10)
    setup.update(email="b@example.com")
    assert client.get("/api/app/sessions/77/summary", headers=bearer("sub-b")).status_code == 404
    assert client.get("/api/sessions/phone-1/77/summary").json()["device_id"] == "phone-1"


def test_session_frames_lists_what_reached_the_pipeline_for_its_owner_only(setup):
    setup.update(email="a@example.com")
    client.get("/api/app/me", headers=bearer("sub-a"))
    assert client.get("/api/app/sessions/88/frames", headers=bearer("sub-a")).status_code == 404  # not registered yet
    with engine.begin() as c:
        pk = c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub, frames_expected)"
                            " VALUES ('88', 'phone-1', 'sub-a', 3) RETURNING id")).scalar()
        c.execute(text("INSERT INTO session_frames (session_pk, base_timestamp_us, gps, imu, imu_missing, can)"
                       " VALUES (:p, 3000000, 1, 1, 0, 1), (:p, 1000000, 1, 1, 0, 1)"), {"p": pk})
    got = client.get("/api/app/sessions/88/frames", headers=bearer("sub-a")).json()
    assert (got["base_timestamp_us"], got["ended"], got["frames_expected"]) == ([1000000, 3000000], False, 3)
    setup.update(email="b@example.com")
    assert client.get("/api/app/sessions/88/frames", headers=bearer("sub-b")).status_code == 404


def test_upload_key_lets_the_phone_catch_up_without_its_authentik_session(setup):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    phone = ec.generate_private_key(ec.SECP256R1())
    pem = phone.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    setup.update(email="a@example.com")
    client.get("/api/app/me", headers=bearer("sub-a"))
    put = client.put("/api/app/devices/phone-1/upload-key", json={"public_key_pem": pem}, headers=bearer("sub-a"))
    assert put.status_code == 204
    assert client.get("/api/devices/phone-1/upload-key").json()["sub"] == "sub-a"
    with engine.begin() as c:
        c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub) VALUES ('91', 'phone-1', 'sub-a')"))
    now = int(time.time())
    device_token = jwt.encode({"iss": "device:phone-1", "sub": "sub-a", "aud": "mqtt-ingest", "iat": now, "exp": now + 600},
                              phone, algorithm="ES256")
    got = client.get("/api/app/sessions/91/frames", headers={"Authorization": f"Bearer {device_token}"})
    assert got.status_code == 200 and got.json()["base_timestamp_us"] == []
    # device tokens open only the catch-up's endpoint
    assert client.get("/api/app/me", headers={"Authorization": f"Bearer {device_token}"}).status_code == 401
    # another user can't take the device over
    setup.update(email="b@example.com")
    assert client.put("/api/app/devices/phone-1/upload-key", json={"public_key_pem": pem}, headers=bearer("sub-b")).status_code == 403
    # revoked: the phone's tokens stop working
    assert client.delete("/api/app/devices/phone-1/upload-key", headers=bearer("sub-a")).status_code == 204
    assert client.get("/api/devices/phone-1/upload-key").status_code == 404
    assert client.get("/api/app/sessions/91/frames", headers={"Authorization": f"Bearer {device_token}"}).status_code == 401


def test_history_lists_the_callers_sessions_newest_first_with_headlines(setup):
    setup.update(email="a@example.com")
    me = client.get("/api/app/me", headers=bearer("sub-a")).json()
    with engine.begin() as c:
        vid = c.execute(text("INSERT INTO vehicles (user_id, name) VALUES (:u, 'Impala') RETURNING id"), {"u": me["id"]}).scalar()
        old = c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub, vehicle_id, started_at, ended_at,"
                             " frames_expected, frames_received) VALUES ('1', 'p', 'sub-a', :v, now() - interval '2 days',"
                             " now() - interval '2 days', 10, 9) RETURNING id"), {"v": vid}).scalar()
        c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub, started_at, ended_at, frames_expected,"
                       " frames_received) VALUES ('2', 'p', 'sub-a', now() - interval '1 hour', now(), 5, 5),"
                       " ('3', 'p', 'sub-b', now(), now(), 1, 1)"))
        c.execute(text("INSERT INTO session_summaries (session_pk, version, frames_used, frames_received, metrics) VALUES"
                       " (:p, 2, 9, 9, '{\"duration_s\": 600, \"moving_s\": 500, \"distance_km\": {\"gps\": 7.4},"
                       " \"speed_kph\": {\"avg_moving\": 48.7, \"max_can\": null, \"max_gps\": 78.0}}')"), {"p": old})
    sessions = client.get("/api/app/sessions", headers=bearer("sub-a")).json()
    assert [s["source_session"] for s in sessions] == ["2", "1"]  # newest first; sub-b's is not listed
    newest, oldest = sessions
    assert (newest["state"], newest["summary"], newest["vehicle_name"]) == ("complete", None, None)
    assert (oldest["state"], oldest["completeness_pct"], oldest["vehicle_name"]) == ("incomplete", 90.0, "Impala")
    assert oldest["summary"] == {"distance_km": 7.4, "duration_s": 600, "moving_s": 500, "avg_moving_kph": 48.7, "max_kph": 78.0}
    assert [s["source_session"] for s in client.get("/api/app/sessions?limit=1&offset=1", headers=bearer("sub-a")).json()] == ["1"]


def test_track_comes_from_vehicle_state_for_the_owner_only(setup, monkeypatch):
    setup.update(email="a@example.com")
    client.get("/api/app/me", headers=bearer("sub-a"))
    monkeypatch.setattr(settings, "timescale_database_url", URL)  # vehicle_state in the same test database
    with engine.begin() as c:
        c.execute(text("INSERT INTO sessions (source_session, device_id, user_sub) VALUES ('77', 'phone-1', 'sub-a')"))
        c.execute(text("CREATE SCHEMA IF NOT EXISTS telemetry; DROP TABLE IF EXISTS telemetry.vehicle_state;"
                       " CREATE TABLE telemetry.vehicle_state (t timestamptz, source_session text, device_id text,"
                       " lat float, lon float, speed_kph float, gps_speed_kph float, rpm float, throttle_pct float,"
                       " brake_on bool, gear float, prndl text, brake_kpa float)"))
        c.execute(text("INSERT INTO telemetry.vehicle_state VALUES"
                       " ('2026-11-03 22:37:25+00', '77', 'phone-1', 42.5, -83.0, 0, 1, 750.25, 0, true, 15, 'P', 101.37),"
                       " ('2026-11-03 22:37:26+00', '77', 'phone-1', null, null, 5, null, 800, 3, false, 1, 'D', 7.8),"
                       " ('2026-11-03 22:37:27+00', '77', 'phone-1', 42.501, -83.0, null, 20.04, null, null, null, null, null, null),"
                       " ('2026-11-03 22:37:25+00', '77', 'phone-2', 1, 1, 1, 1, 1, 1, false, 1, 'D', 1)"))
    track = client.get("/api/app/sessions/77/track", headers=bearer("sub-a")).json()
    assert track["columns"] == ["s", "lat", "lon", "speed_kph", "rpm", "throttle_pct", "brake_on", "gear", "prndl", "brake_kpa"]
    assert track["rows"] == [  # CAN speed first, else GPS; rows without a fix are left out
        [0.0, 42.5, -83.0, 0.0, 750.2, 0.0, True, 15.0, "P", 101.4],
        [2.0, 42.501, -83.0, 20.0, None, None, None, None, None, None],
    ]
    setup.update(email="b@example.com")
    assert client.get("/api/app/sessions/77/track", headers=bearer("sub-b")).status_code == 404


def test_tracks_found_near_a_zip_added_and_listed_nearest_first(setup, monkeypatch):
    from app.services import osm as osm_service
    setup.update(email="a@example.com")
    me = client.get("/api/app/me", headers=bearer("sub-a")).json()
    line = lambda lat: {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[-83.0, lat], [-83.01, lat + 0.01]]}}
    asked = {}

    def fake_discover(lat, lon, radius_m):
        asked.update(lat=lat, lon=lon, radius_m=radius_m)
        return "tok", [
            {"name": "Far Raceway", "osm_relation_id": 2, "osm_way_ids": None, "geometry": line(43.0), "geometry_type": "linestring"},
            {"name": "Near Kart", "osm_relation_id": None, "osm_way_ids": [7, 8], "geometry": line(42.51), "geometry_type": "linestring"},
        ]
    cache = {}
    monkeypatch.setattr(osm_service, "discover", lambda *a: (cache.setdefault("c", fake_discover(*a)))  )
    monkeypatch.setattr(osm_service, "ingest_from_cache", lambda token, idx: [cache["c"][1][i] for i in idx] if token == "tok" else None)
    monkeypatch.setattr(osm_service, "geocode_zip", lambda z: (42.5, -83.0, "Warren, MI") if z == "48092" else None)

    assert client.post("/api/app/tracks/discover", json={"zip": "4809"}, headers=bearer("sub-a")).status_code == 422
    assert client.post("/api/app/tracks/discover", json={"zip": "00000"}, headers=bearer("sub-a")).status_code == 404
    found = client.post("/api/app/tracks/discover", json={"zip": "48092", "radius_m": 10**7}, headers=bearer("sub-a")).json()
    assert asked["radius_m"] == 160_934  # capped at 100 mi
    assert (found["origin"], found["origin_label"]) == ([42.5, -83.0], "Warren, MI")
    assert [(c["name"], c["index"]) for c in found["candidates"]] == [("Near Kart", 1), ("Far Raceway", 0)]  # nearest first

    added = client.post("/api/app/tracks/ingest", json={"token": "tok", "selected_indices": [1, 0]}, headers=bearer("sub-a")).json()
    assert [t["name"] for t in added["ingested"]] == ["Near Kart", "Far Raceway"] and added["skipped"] == []
    again = client.post("/api/app/tracks/ingest", json={"token": "tok", "selected_indices": [1]}, headers=bearer("sub-a")).json()
    assert again["ingested"] == [] and "already imported" in again["skipped"][0]["reason"]

    listed = client.get("/api/app/tracks?lat=42.5&lon=-83.0", headers=bearer("sub-a")).json()
    assert [t["name"] for t in listed] == ["Near Kart", "Far Raceway"]
    assert listed[0]["added_by_me"] is True and listed[0]["distance_km"] < 2 and listed[0]["has_start_line"] is False
    assert client.post("/api/app/tracks/ingest", json={"token": "old", "selected_indices": [0]}, headers=bearer("sub-a")).status_code == 410
