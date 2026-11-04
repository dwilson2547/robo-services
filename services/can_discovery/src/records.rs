//! The four inputs, parsed from kreceiver-shaped records (one per message):
//! discovery CAN (from can-decode), GPS, IMU and session events. Times are ms on
//! the phone's clock.

use serde_json::Value;

pub type Key = (String, String); // (source_session, device_id)

#[derive(Debug, Clone, PartialEq)]
pub enum Sample {
    Can { t: i64, id: u32, dlc: u8, data: [u8; 8] },
    Gps { t: i64, speed_kph: f64 },
    Gyro { t: i64, deg_s: [f64; 3] },
    SessionEnd,
    /// session_start, frame receipts: they keep the session alive, nothing more
    SessionOther,
}

pub fn key(record: &Value) -> Option<Key> {
    let session = record.get("source_session")?.as_str()?;
    let device = record.get("device_id")?.as_str()?;
    (!session.is_empty() && session != "proto-live").then(|| (session.to_string(), device.to_string()))
}

pub fn time_ms(iso: &str) -> Option<i64> {
    chrono::DateTime::parse_from_rfc3339(iso).ok().map(|t| t.timestamp_millis())
}

/// `kind`: which topic it came from ("discovery", "gps", "imu", "session").
pub fn parse(kind: &str, record: &Value) -> Option<Sample> {
    let payload = record.get("payload");
    match kind {
        "discovery" => {
            let p = payload?;
            let base_us = p.get("base_timestamp_us")?.as_i64()?;
            let dt_us = p.get("dt_us").and_then(Value::as_i64).unwrap_or(0);
            let hex = p.get("data_hex")?.as_str()?;
            let bytes = decode_hex(hex)?;
            let mut data = [0u8; 8];
            let n = bytes.len().min(8);
            data[..n].copy_from_slice(&bytes[..n]);
            let dlc = p.get("dlc").and_then(Value::as_u64).map(|d| d as u8).unwrap_or(n as u8);
            Some(Sample::Can { t: (base_us + dt_us) / 1000, id: p.get("can_id")?.as_u64()? as u32, dlc, data })
        }
        "gps" => {
            let speed = payload?.get("ground_speed_kph")?.as_f64()?;
            Some(Sample::Gps { t: time_ms(record.get("captured_at")?.as_str()?)?, speed_kph: speed })
        }
        "imu" => {
            let g = payload?.get("gyro_deg_s")?;
            let axis = |a: &str| g.get(a).and_then(Value::as_f64);
            Some(Sample::Gyro {
                t: time_ms(record.get("captured_at")?.as_str()?)?,
                deg_s: [axis("x")?, axis("y")?, axis("z")?],
            })
        }
        "session" => Some(match record.get("message_type").and_then(Value::as_str) {
            Some("session_end") => Sample::SessionEnd,
            _ => Sample::SessionOther,
        }),
        _ => None,
    }
}

fn decode_hex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_each_input() {
        let can = json!({"source_session": "1", "device_id": "phone", "source_type": "can_discovery",
            "payload": {"can_id": 514, "data_hex": "0000001f40", "dlc": 5, "dt_us": 2500, "base_timestamp_us": 1_793_767_574_000_000i64}});
        assert_eq!(key(&can), Some(("1".into(), "phone".into())));
        assert_eq!(parse("discovery", &can), Some(Sample::Can { t: 1_793_767_574_002, id: 514, dlc: 5, data: [0, 0, 0, 0x1f, 0x40, 0, 0, 0] }));
        let gps = json!({"captured_at": "2026-11-04T04:46:14.579000+00:00", "payload": {"ground_speed_kph": 42.5}});
        assert_eq!(parse("gps", &gps), Some(Sample::Gps { t: 1_793_767_574_579, speed_kph: 42.5 }));
        let imu = json!({"captured_at": "2026-11-04T04:46:14Z", "payload": {"gyro_deg_s": {"x": 1.0, "y": -2.0, "z": 0.5}}});
        assert_eq!(parse("imu", &imu), Some(Sample::Gyro { t: 1_793_767_574_000, deg_s: [1.0, -2.0, 0.5] }));
        assert_eq!(parse("session", &json!({"message_type": "session_end"})), Some(Sample::SessionEnd));
        assert_eq!(parse("session", &json!({"message_type": "frame"})), Some(Sample::SessionOther));
    }

    #[test]
    fn records_without_a_session_have_no_key() {
        assert_eq!(key(&json!({"source_session": "proto-live", "device_id": "x"})), None);
        assert_eq!(key(&json!({"device_id": "x"})), None);
    }
}
