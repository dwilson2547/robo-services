//! Iggy message envelope, byte-compatible with can_pub_sub_probe.iggy_backend:
//! a JSON object `{"headers": {...}, "payload_b64": "..."}` with sorted keys and
//! compact separators. Anything that is not such an object is treated as a raw
//! payload with no headers (the Python fallback).

use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Serialize, Deserialize)]
struct Envelope {
    headers: BTreeMap<String, String>,
    payload_b64: String,
}

pub fn encode_message(payload: &[u8], headers: &BTreeMap<String, String>) -> Vec<u8> {
    let envelope = Envelope {
        headers: headers.clone(),
        payload_b64: B64.encode(payload),
    };
    // BTreeMap iterates in sorted key order and serde_json emits compact output,
    // matching Python's json.dumps(..., separators=(",", ":"), sort_keys=True).
    serde_json::to_vec(&envelope).expect("envelope serialization cannot fail")
}

pub fn decode_message(payload: &[u8]) -> (Vec<u8>, BTreeMap<String, String>) {
    match serde_json::from_slice::<Envelope>(payload) {
        Ok(envelope) => match B64.decode(envelope.payload_b64) {
            Ok(decoded) => (decoded, envelope.headers),
            Err(_) => (payload.to_vec(), BTreeMap::new()),
        },
        Err(_) => (payload.to_vec(), BTreeMap::new()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let mut headers = BTreeMap::new();
        headers.insert("x-source-type".to_string(), "can_decoded".to_string());
        headers.insert("x-session-id".to_string(), "s:phone-1".to_string());
        let payload = b"{\"a\":1}";
        let encoded = encode_message(payload, &headers);
        let (decoded, got_headers) = decode_message(&encoded);
        assert_eq!(decoded, payload);
        assert_eq!(got_headers, headers);
    }

    #[test]
    fn matches_python_byte_format() {
        // json.dumps({"payload_b64": ..., "headers": {...}}, separators=(",", ":"), sort_keys=True)
        let mut headers = BTreeMap::new();
        headers.insert("b".to_string(), "2".to_string());
        headers.insert("a".to_string(), "1".to_string());
        let encoded = encode_message(b"hi", &headers);
        let expected = b"{\"headers\":{\"a\":\"1\",\"b\":\"2\"},\"payload_b64\":\"aGk=\"}";
        assert_eq!(encoded, expected);
    }

    #[test]
    fn non_envelope_payload_passes_through() {
        let raw = b"not json at all";
        let (payload, headers) = decode_message(raw);
        assert_eq!(payload, raw);
        assert!(headers.is_empty());
    }
}
