//! Messages on iggy are JSON envelopes written by can_pub_sub_probe.iggy_backend:
//! `{"body": <record>, "headers": {...}}`, or the older
//! `{"payload_b64": "...", "headers": {...}}`; anything else is the record itself.

use base64::Engine;
use serde_json::Value;

pub fn record(payload: &[u8]) -> Option<Value> {
    let v: Value = serde_json::from_slice(payload).ok()?;
    if let Some(obj) = v.as_object() {
        if let Some(body) = obj.get("body") {
            if body.is_object() {
                return Some(body.clone());
            }
        }
        if let Some(b64) = obj.get("payload_b64").and_then(Value::as_str) {
            let raw = base64::engine::general_purpose::STANDARD.decode(b64).ok()?;
            return serde_json::from_slice(&raw).ok();
        }
    }
    Some(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_body_b64_and_bare_forms() {
        let rec = br#"{"device_id":"d","payload":{"can_id":1}}"#;
        let b64 = base64::engine::general_purpose::STANDARD.encode(rec);
        let body = record(br#"{"body":{"device_id":"d","payload":{"can_id":1}},"headers":{}}"#).unwrap();
        let old = record(format!(r#"{{"payload_b64":"{b64}","headers":{{}}}}"#).as_bytes()).unwrap();
        let bare = record(rec).unwrap();
        assert_eq!(body, bare);
        assert_eq!(old, bare);
        assert!(record(b"not json").is_none());
    }
}
