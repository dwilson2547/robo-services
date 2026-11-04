//! Results into the registry database (table session_discoveries, registry
//! migration 0011), the way drive-summary writes session_summaries.

use serde_json::Value;
use tokio_postgres::{Client, NoTls};

pub struct Db {
    client: Client,
}

pub struct DbSettings {
    pub host: String,
    pub port: u16,
    pub name: String,
    pub user: String,
    pub password: String,
}

impl Db {
    pub async fn connect(s: &DbSettings) -> Result<Self, tokio_postgres::Error> {
        let mut config = tokio_postgres::Config::new();
        config.host(&s.host).port(s.port).dbname(&s.name).user(&s.user).password(&s.password);
        let (client, connection) = config.connect(NoTls).await?;
        tokio::spawn(async move {
            if let Err(e) = connection.await {
                tracing::error!("registry database connection lost: {e}");
            }
        });
        Ok(Self { client })
    }

    pub fn is_closed(&self) -> bool {
        self.client.is_closed()
    }

    /// Upsert one session's results; Ok(false) when the registry has no such session.
    /// An existing result built from more CAN frames is kept: frames that arrive after a
    /// session was analysed (the phone's catch-up) look like a new, tiny session here,
    /// and must not replace the analysis of the whole drive.
    pub async fn write(&self, session: &str, device: &str, version: i32, can_frames: i64, inventory: &Value,
                       candidates: &Value) -> Result<bool, tokio_postgres::Error> {
        let row = self
            .client
            .query_opt("SELECT id FROM sessions WHERE source_session = $1 AND device_id = $2", &[&session, &device])
            .await?;
        let Some(row) = row else { return Ok(false) };
        let pk: i32 = row.get(0);
        self.client
            .execute(
                "INSERT INTO session_discoveries (session_pk, version, can_frames, inventory, candidates, computed_at) \
                 VALUES ($1, $2, $3, $4, $5, now()) \
                 ON CONFLICT (session_pk) DO UPDATE SET version = EXCLUDED.version, can_frames = EXCLUDED.can_frames, \
                 inventory = EXCLUDED.inventory, candidates = EXCLUDED.candidates, computed_at = now() \
                 WHERE session_discoveries.can_frames <= EXCLUDED.can_frames",
                &[&pk, &version, &can_frames, inventory, candidates],
            )
            .await?;
        Ok(true)
    }
}
