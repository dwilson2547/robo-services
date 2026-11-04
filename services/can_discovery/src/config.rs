use std::env;

use crate::store::DbSettings;

pub struct Settings {
    pub connection_string: String,
    pub stream: String,
    pub consumer: String,
    /// topic per input kind, in session::KINDS order
    pub topics: [String; 4],
    /// where a consumer with no stored offset starts: "latest" (default) or "earliest"
    pub start: String,
    pub max_can_frames: usize,
    pub end_quiet_s: f64,
    pub idle_s: f64,
    pub check_every_s: f64,
    pub db: DbSettings,
}

fn var(name: &str, default: &str) -> String {
    env::var(name).ok().filter(|v| !v.is_empty()).unwrap_or_else(|| default.to_string())
}

fn required(name: &str) -> String {
    env::var(name).unwrap_or_else(|_| panic!("{name} is required"))
}

fn num<T: std::str::FromStr>(name: &str, default: &str) -> T {
    var(name, default).parse().unwrap_or_else(|_| panic!("{name} must be a number"))
}

impl Settings {
    pub fn from_env() -> Self {
        Self {
            connection_string: required("IGGY_CONNECTION_STRING"),
            stream: var("IGGY_STREAM", "can-pub-sub-probe"),
            consumer: var("CAN_DISCOVERY_CONSUMER", "can-discovery"),
            topics: [
                var("CAN_DISCOVERY_TOPIC", "telemetry.discovery.can"),
                var("CAN_DISCOVERY_GPS_TOPIC", "telemetry.raw.gps"),
                var("CAN_DISCOVERY_IMU_TOPIC", "telemetry.raw.imu"),
                var("CAN_DISCOVERY_SESSION_TOPIC", "telemetry.session"),
            ],
            start: var("CAN_DISCOVERY_START", "latest"),
            max_can_frames: num("MAX_CAN_FRAMES", "2000000"),
            end_quiet_s: num("END_QUIET_S", "120"),
            idle_s: num("IDLE_S", "1800"),
            check_every_s: num("CHECK_EVERY_S", "30"),
            db: DbSettings {
                host: required("DB_HOST"),
                port: num("DB_PORT", "5432"),
                name: var("DB_NAME", "postgres"),
                user: required("DB_USER"),
                password: required("DB_PASSWORD"),
            },
        }
    }
}
