//! Environment configuration, mirroring services/can_decode's Settings.

use std::env;

fn required(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| panic!("missing required env var {name}"))
}

fn var_or(name: &str, default: &str) -> String {
    env::var(name).unwrap_or_else(|_| default.to_string())
}

fn var_or_parse<T: std::str::FromStr>(name: &str, default: T) -> T {
    env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

#[derive(Debug, Clone)]
pub struct Settings {
    pub connection_string: String,
    pub stream: String,
    pub group: String,
    pub in_topic: String,
    pub out_topic: String,
    pub unknown_topic: String,
    pub dbc_path: String,
    pub default_dbc_name: String,
    pub commit_every: u64,
    pub batch_length: u32,
    pub poll_interval_ms: u64,
    pub watchdog_interval_s: u64,
    pub stall_after_s: u64,
    pub report_interval_s: u64,
}

impl Settings {
    pub fn from_env() -> Self {
        Self {
            connection_string: required("IGGY_CONNECTION_STRING"),
            stream: var_or("IGGY_STREAM", "can-pub-sub-probe"),
            group: var_or("CAN_DECODE_GROUP", "can-decode-rs"),
            in_topic: var_or("CAN_DECODE_INPUT_TOPIC", "telemetry.raw.can"),
            out_topic: var_or("CAN_DECODE_OUTPUT_TOPIC", "telemetry.decoded.can.rs"),
            unknown_topic: var_or("CAN_DECODE_UNKNOWN_TOPIC", ""),
            dbc_path: required("DBC_PATH"),
            default_dbc_name: var_or("CAN_DECODE_DEFAULT_DBC_NAME", "default"),
            commit_every: var_or_parse("COMMIT_EVERY", 100),
            batch_length: var_or_parse("BATCH_LENGTH", 200),
            poll_interval_ms: var_or_parse("POLL_INTERVAL_MS", 50),
            watchdog_interval_s: var_or_parse("WATCHDOG_INTERVAL_S", 30),
            stall_after_s: var_or_parse("STALL_AFTER_S", 120),
            report_interval_s: var_or_parse("REPORT_INTERVAL_S", 60),
        }
    }
}
