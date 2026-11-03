from app.decode import Decoded, Unknown, decode_record, load_dbc

# One 11-bit and one 29-bit message, the 29-bit id written without the
# extended flag the way phone-dbc-repo DBCs do.
DBC = """VERSION ""

BS_:

BU_: PCM

BO_ 201 EngineStatus: 2 PCM
 SG_ RPM : 7|16@0+ (0.25,0) [0|16383] "rpm" Vector__XXX

BO_ 271360000 DoorStatus: 1 PCM
 SG_ DriverDoorOpen : 0|1@1+ (1,0) [0|1] "" Vector__XXX
"""


def _record(can_id: int, data_hex: str) -> dict:
    return {
        "source_type": "can",
        "topic": "telemetry.raw.can",
        "device_id": "phone-1",
        "session_id": "s:phone-1",
        "captured_at": "2026-11-03T00:00:00+00:00",
        "payload": {"can_id": can_id, "data_hex": data_hex, "dlc": len(data_hex) // 2, "dt_us": 1500},
    }


def test_load_dbc_accepts_unflagged_29_bit_ids():
    db = load_dbc(DBC)
    assert set(db) == {201, 271360000}


def test_decodes_11_bit_frame_and_keeps_envelope():
    result = decode_record(_record(201, "0fa0"), load_dbc(DBC))
    assert isinstance(result, Decoded)
    out = result.record
    assert out["source_type"] == "can_decoded"
    assert out["device_id"] == "phone-1" and out["captured_at"] == "2026-11-03T00:00:00+00:00"
    assert out["payload"]["message"] == "EngineStatus"
    assert out["payload"]["signals"] == {"RPM": 1000.0}
    assert out["payload"]["dt_us"] == 1500


def test_decodes_29_bit_frame_sent_without_flag():
    result = decode_record(_record(271360000, "01"), load_dbc(DBC))
    assert isinstance(result, Decoded)
    assert result.record["payload"]["signals"] == {"DriverDoorOpen": 1}


def test_unknown_id():
    result = decode_record(_record(0x123, "00"), load_dbc(DBC))
    assert isinstance(result, Unknown)
    assert result.record["source_type"] == "can_unknown"
    assert result.reason == "no DBC message"


def test_short_frame_is_unknown_not_an_exception():
    result = decode_record(_record(201, "0f"), load_dbc(DBC))
    assert isinstance(result, Unknown)
    assert result.reason.startswith("EngineStatus:")


def test_frame_timestamp_passes_through_decoded_and_unknown():
    db = load_dbc(DBC)
    rec = _record(201, "0fa0")
    rec["payload"]["base_timestamp_us"] = 1_793_000_000_000_000
    assert decode_record(rec, db).record["payload"]["base_timestamp_us"] == 1_793_000_000_000_000
    rec = _record(0x7FF, "00")
    rec["payload"]["base_timestamp_us"] = 1_793_000_000_000_000
    out = decode_record(rec, db)
    assert isinstance(out, Unknown) and out.record["payload"]["base_timestamp_us"] == 1_793_000_000_000_000
    assert "base_timestamp_us" not in decode_record(_record(201, "0fa0"), db).record["payload"]
