//! DBC decoding of kreceiver's raw CAN records, mirroring
//! services/can_decode/app/decode.py. Input is one NormalizedIngressMessage
//! JSON per CAN frame; output keeps every envelope field and replaces
//! `payload` / `source_type`.

use crate::dbc::{Dbc, Signal};
use serde_json::{json, Map, Value};

pub enum Outcome {
    Decoded(Value),
    Unknown(Value, String),
}

/// Decode one raw record against the DBC. `dbc_name` is stamped into the
/// payload so consumers can tell which DBC the decode (or the miss) is
/// relative to.
pub fn decode_record(record: &Value, dbc: &Dbc, dbc_name: &str) -> Outcome {
    let payload = &record["payload"];
    let can_id = payload["can_id"].as_u64().unwrap_or(0) as u32;
    let data_hex = payload["data_hex"].as_str().unwrap_or("");
    let data = match hex::decode(data_hex) {
        Ok(d) => d,
        Err(_) => {
            return Outcome::Unknown(
                with_payload(record, "can_unknown", raw_payload(can_id, data_hex, payload, Some(dbc_name))),
                "bad data_hex".to_string(),
            )
        }
    };
    let mut raw = raw_payload(can_id, data_hex, payload, Some(dbc_name));

    let message = match dbc.messages.get(&(can_id & 0x1FFF_FFFF)) {
        Some(m) => m,
        None => {
            return Outcome::Unknown(
                with_payload(record, "can_unknown", raw),
                "no DBC message".to_string(),
            )
        }
    };
    if data.len() < message.dlc as usize {
        raw.remove("message");
        return Outcome::Unknown(
            with_payload(record, "can_unknown", raw),
            format!(
                "{}: wrong data size: {} instead of {} bytes",
                message.name,
                data.len(),
                message.dlc
            ),
        );
    }

    let mut signals = Map::new();
    for sig in &message.signals {
        match extract(&data, sig) {
            Some(raw_value) => {
                signals.insert(sig.name.clone(), physical(raw_value, sig));
            }
            None => {
                return Outcome::Unknown(
                    with_payload(record, "can_unknown", raw),
                    format!("{}: signal {} out of range", message.name, sig.name),
                );
            }
        }
    }
    raw.insert("message".to_string(), json!(message.name));
    raw.insert("signals".to_string(), Value::Object(signals));
    Outcome::Decoded(with_payload(record, "can_decoded", raw))
}

/// {can_id, data_hex, dt_us, base_timestamp_us?, dbc}
fn raw_payload(can_id: u32, data_hex: &str, payload: &Value, dbc_name: Option<&str>) -> Map<String, Value> {
    let mut raw = Map::new();
    raw.insert("can_id".to_string(), json!(can_id));
    raw.insert("data_hex".to_string(), json!(data_hex));
    if let Some(dt_us) = payload.get("dt_us") {
        raw.insert("dt_us".to_string(), dt_us.clone());
    }
    if let Some(base) = payload.get("base_timestamp_us") {
        raw.insert("base_timestamp_us".to_string(), base.clone());
    }
    if let Some(name) = dbc_name {
        raw.insert("dbc".to_string(), json!(name));
    }
    raw
}

fn with_payload(record: &Value, source_type: &str, payload: Map<String, Value>) -> Value {
    let mut out = record.as_object().cloned().unwrap_or_default();
    out.insert("source_type".to_string(), json!(source_type));
    out.insert("payload".to_string(), Value::Object(payload));
    Value::Object(out)
}

/// Physical value: cantools returns an int when scale == 1 and offset == 0,
/// a float otherwise; match that so outputs stay comparable.
fn physical(raw: u64, sig: &Signal) -> Value {
    let value = if sig.signed {
        sign_extend(raw, sig.length) as f64
    } else {
        raw as f64
    };
    if sig.scale == 1.0 && sig.offset == 0.0 {
        json!(if sig.signed { sign_extend(raw, sig.length) } else { raw as i64 })
    } else {
        json!(value * sig.scale + sig.offset)
    }
}

fn sign_extend(raw: u64, length: u32) -> i64 {
    let shift = 64 - length;
    ((raw << shift) as i64) >> shift
}

fn bit_at(data: &[u8], linear: u32) -> u64 {
    ((data[(linear / 8) as usize] >> (linear % 8)) & 1) as u64
}

/// Extract the raw (unscaled) value of a signal. Returns None when the signal
/// runs past the end of the data.
fn extract(data: &[u8], sig: &Signal) -> Option<u64> {
    let mut value: u64 = 0;
    if sig.little_endian {
        // start is the LSB; bits run upward in linear numbering
        for i in 0..sig.length {
            let pos = sig.start + i;
            if pos / 8 >= data.len() as u32 {
                return None;
            }
            value |= bit_at(data, pos) << i;
        }
    } else {
        // start is the MSB; walk down within the byte, wrap to the next byte's MSB
        let mut pos = sig.start;
        for i in 0..sig.length {
            if pos / 8 >= data.len() as u32 {
                return None;
            }
            value |= bit_at(data, pos) << (sig.length - 1 - i);
            pos = if pos % 8 == 0 { pos + 15 } else { pos - 1 };
        }
    }
    Some(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dbc::load_dbc;

    // Same DBC as services/can_decode/tests/test_decode.py
    const DBC: &str = r#"VERSION ""

BS_:

BU_: PCM

BO_ 201 EngineStatus: 2 PCM
 SG_ RPM : 7|16@0+ (0.25,0) [0|16383] "rpm" Vector__XXX

BO_ 271360000 DoorStatus: 1 PCM
 SG_ DriverDoorOpen : 0|1@1+ (1,0) [0|1] "" Vector__XXX
"#;

    fn record(can_id: u64, data_hex: &str) -> Value {
        json!({
            "source_type": "can",
            "topic": "telemetry.raw.can",
            "device_id": "phone-1",
            "session_id": "s:phone-1",
            "captured_at": "2026-11-03T00:00:00+00:00",
            "payload": {"can_id": can_id, "data_hex": data_hex, "dlc": data_hex.len() / 2, "dt_us": 1500},
        })
    }

    #[test]
    fn decodes_11_bit_frame_and_keeps_envelope() {
        let db = load_dbc(DBC).unwrap();
        match decode_record(&record(201, "0fa0"), &db, "test") {
            Outcome::Decoded(out) => {
                assert_eq!(out["source_type"], "can_decoded");
                assert_eq!(out["device_id"], "phone-1");
                assert_eq!(out["captured_at"], "2026-11-03T00:00:00+00:00");
                assert_eq!(out["payload"]["message"], "EngineStatus");
                assert_eq!(out["payload"]["signals"]["RPM"], json!(1000.0));
                assert_eq!(out["payload"]["dt_us"], json!(1500));
                assert_eq!(out["payload"]["dbc"], json!("test"));
            }
            _ => panic!("expected decoded"),
        }
    }

    #[test]
    fn decodes_29_bit_frame_sent_without_flag() {
        let db = load_dbc(DBC).unwrap();
        match decode_record(&record(271360000, "01"), &db, "test") {
            Outcome::Decoded(out) => assert_eq!(out["payload"]["signals"]["DriverDoorOpen"], json!(1)),
            _ => panic!("expected decoded"),
        }
    }

    #[test]
    fn unknown_id() {
        let db = load_dbc(DBC).unwrap();
        match decode_record(&record(0x123, "00"), &db, "test") {
            Outcome::Unknown(out, reason) => {
                assert_eq!(out["source_type"], "can_unknown");
                assert_eq!(reason, "no DBC message");
            }
            _ => panic!("expected unknown"),
        }
    }

    #[test]
    fn short_frame_is_unknown_not_an_exception() {
        let db = load_dbc(DBC).unwrap();
        match decode_record(&record(201, "0f"), &db, "test") {
            Outcome::Unknown(_, reason) => assert!(reason.starts_with("EngineStatus:")),
            _ => panic!("expected unknown"),
        }
    }

    #[test]
    fn frame_timestamp_passes_through() {
        let db = load_dbc(DBC).unwrap();
        let mut rec = record(201, "0fa0");
        rec["payload"]["base_timestamp_us"] = json!(1_793_000_000_000_000u64);
        match decode_record(&rec, &db, "test") {
            Outcome::Decoded(out) => {
                assert_eq!(out["payload"]["base_timestamp_us"], json!(1_793_000_000_000_000u64))
            }
            _ => panic!("expected decoded"),
        }
        let mut rec = record(0x7FF, "00");
        rec["payload"]["base_timestamp_us"] = json!(1_793_000_000_000_000u64);
        match decode_record(&rec, &db, "test") {
            Outcome::Unknown(out, _) => {
                assert_eq!(out["payload"]["base_timestamp_us"], json!(1_793_000_000_000_000u64))
            }
            _ => panic!("expected unknown"),
        }
        let rec = record(201, "0fa0");
        match decode_record(&rec, &db, "test") {
            Outcome::Decoded(out) => assert!(out["payload"].get("base_timestamp_us").is_none()),
            _ => panic!("expected decoded"),
        }
    }

    // Ground truth from cantools + services/can_decode on the repo's own files:
    // can_data/dbc/custom/chevy_impala_2008_hscan.dbc decoding frames from
    // capture_device_configs/can_caps/can-capture-acc.txt.
    const IMPALA: &str = include_str!("../../../can_data/dbc/custom/chevy_impala_2008_hscan.dbc");

    fn impala() -> Dbc {
        load_dbc(IMPALA).unwrap()
    }

    #[test]
    fn impala_engine_status_matches_cantools() {
        // 0x0C9: EngineRPM 795.0, RollingCounter 8, BrakePedalPressed 0
        let db = impala();
        match decode_record(&record(0xC9, "840c6c07004000"), &db, "chevy_impala_2008_hscan") {
            Outcome::Decoded(out) => {
                assert_eq!(out["payload"]["message"], "ECMEngineStatus");
                assert_eq!(out["payload"]["signals"]["EngineRPM"], json!(795.0));
                assert_eq!(out["payload"]["signals"]["RollingCounter"], json!(8));
                assert_eq!(out["payload"]["signals"]["BrakePedalPressed"], json!(0));
            }
            _ => panic!("expected decoded"),
        }
    }

    #[test]
    fn impala_signed_steering_angle_matches_cantools() {
        // 0x1E5: SteeringWheelAngle -2016.0 (signed 16-bit, scale 0.0625)
        let db = impala();
        match decode_record(&record(0x1E5, "820000e000fffd82"), &db, "chevy_impala_2008_hscan") {
            Outcome::Decoded(out) => {
                assert_eq!(out["payload"]["message"], "PSCMSteeringAngle");
                assert_eq!(out["payload"]["signals"]["SteeringWheelAngle"], json!(-2016.0));
            }
            _ => panic!("expected decoded"),
        }
    }

    #[test]
    fn impala_brake_pressure_matches_cantools() {
        // 0x0F1: BrakePressure 6.5 (Intel 8|8@1+, scale 1.3)
        let db = impala();
        match decode_record(&record(0xF1, "00050040"), &db, "chevy_impala_2008_hscan") {
            Outcome::Decoded(out) => {
                assert_eq!(out["payload"]["message"], "EBCMBrakePressure");
                assert_eq!(out["payload"]["signals"]["BrakePressure"], json!(6.5));
            }
            _ => panic!("expected decoded"),
        }
    }

    #[test]
    fn impala_unmapped_id_is_unknown() {
        let db = impala();
        match decode_record(&record(0xF9, "00004000000000ff"), &db, "chevy_impala_2008_hscan") {
            Outcome::Unknown(_, reason) => assert_eq!(reason, "no DBC message"),
            _ => panic!("expected unknown"),
        }
    }
}

