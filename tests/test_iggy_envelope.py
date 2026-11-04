import base64
import json

from can_pub_sub_probe.iggy_backend import _decode_message, _encode_message

HEADERS = {"x-device-id": "d1", "x-source-type": "gps"}


def test_json_payload_is_stored_readable():
    payload = json.dumps({"device_id": "d1", "payload": {"lat": 1.5}}).encode()
    envelope = json.loads(_encode_message(payload, HEADERS))
    assert envelope == {"body": {"device_id": "d1", "payload": {"lat": 1.5}}, "headers": HEADERS}


def test_round_trip_keeps_the_json():
    payload = b'{"a": 1, "b": [1, 2.5, "x"], "c": null}'
    body, headers = _decode_message(_encode_message(payload, HEADERS))
    assert json.loads(body) == json.loads(payload)
    assert headers == HEADERS


def test_binary_payload_falls_back_to_base64():
    payload = b"\x00\xffnot json"
    envelope = json.loads(_encode_message(payload, HEADERS))
    assert envelope["payload_b64"] == base64.b64encode(payload).decode()
    assert _decode_message(_encode_message(payload, HEADERS)) == (payload, HEADERS)


def test_old_base64_envelope_still_decodes():
    payload = b'{"device_id": "d1"}'
    old = json.dumps({"payload_b64": base64.b64encode(payload).decode(), "headers": HEADERS}).encode()
    assert _decode_message(old) == (payload, HEADERS)


def test_unwrapped_message_passes_through():
    for raw in (b'{"device_id": "d1"}', b"[1, 2]", b"\x00\x01"):
        assert _decode_message(raw) == (raw, {})
