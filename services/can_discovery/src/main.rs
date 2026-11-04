//! can-discovery: DBC discovery for vehicles without a DBC
//! (docs/plans/2026-11-04-dbc-discovery.md §2).
//!
//! Reads telemetry.discovery.can (can-decode's undecoded CAN from sessions whose
//! vehicle has no DBC), telemetry.raw.gps, telemetry.raw.imu and telemetry.session
//! by explicit offset as consumer CAN_DISCOVERY_CONSUMER. Buffers each session until
//! it finishes, then writes a per-id inventory and correlated signal candidates to
//! the registry's session_discoveries. Sessions with no discovery traffic are
//! discarded. Stored offsets stay below each topic's oldest open session, so a
//! restart replays whatever was not yet analysed.
//!
//! `can-discovery backfill <source_session> <device_id>` analyses one past session
//! from the raw topics instead, then exits (src/backfill.rs).

mod analyze;
mod backfill;
mod bits;
mod config;
mod correlate;
mod envelope;
mod inventory;
mod records;
mod reference;
mod session;
mod store;

use std::time::{Duration, Instant};

use iggy::prelude::*;

use config::Settings;
use session::{Table, KINDS};
use store::Db;

const BATCH: u32 = 500;
const STALL_AFTER_S: f64 = 120.0;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_env("CAN_DISCOVERY_LOG_LEVEL").unwrap_or_else(|_| "info".into()),
        )
        .init();
    let s = Settings::from_env();
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("backfill") {
        let (Some(session), Some(device)) = (args.get(2), args.get(3)) else {
            panic!("usage: can-discovery backfill <source_session> <device_id>");
        };
        let raw_can = std::env::var("CAN_DISCOVERY_RAW_CAN_TOPIC").unwrap_or_else(|_| "telemetry.raw.can".into());
        backfill::run(&s, &raw_can, session, device).await;
        return;
    }

    let client = IggyClient::from_connection_string(&s.connection_string).expect("bad IGGY_CONNECTION_STRING");
    client.connect().await.expect("cannot connect to Iggy");
    let stream_id = Identifier::named(&s.stream).expect("bad stream name");
    let topic_ids: Vec<Identifier> = s.topics.iter().map(|t| Identifier::named(t).expect("bad topic name")).collect();
    for (id, name) in topic_ids.iter().zip(&s.topics) {
        ensure_topic(&client, &stream_id, id, name).await;
    }
    let consumer = Consumer { kind: ConsumerKind::Consumer, id: Identifier::named(&s.consumer).expect("bad consumer") };

    // where each topic resumes: after the stored offset, else latest/earliest
    let mut next = [0u64; 4];
    let mut stored: [Option<u64>; 4] = [None; 4];
    for (k, topic) in topic_ids.iter().enumerate() {
        let saved = client
            .get_consumer_offset(&consumer, &stream_id, topic, Some(0))
            .await
            .expect("cannot read the stored offset")
            .map(|o| o.stored_offset);
        stored[k] = saved;
        next[k] = match saved {
            Some(o) => o + 1,
            None if s.start == "earliest" => 0,
            None => message_count(&client, &stream_id, topic).await.unwrap_or(0),
        };
    }
    tracing::info!(
        "{} + {} + {} + {} -> registry session_discoveries (v{}); consumer {:?} starting at {:?}",
        s.topics[0], s.topics[1], s.topics[2], s.topics[3], analyze::VERSION, s.consumer, next
    );

    let mut db: Option<Db> = None;
    let mut table = Table::new(s.max_can_frames, s.end_quiet_s, s.idle_s);
    let params = correlate::Params::default();
    let start = Instant::now();
    let now = || start.elapsed().as_secs_f64();
    let mut last_check = 0.0;
    let mut progress = [(0u64, 0.0f64); 4]; // per topic: (next offset, when it last moved)
    let mut counts = [0usize; 4];

    loop {
        let mut idle = true;
        for k in 0..4 {
            let polled = match client
                .poll_messages(&stream_id, &topic_ids[k], Some(0), &consumer, &PollingStrategy::offset(next[k]), BATCH, false)
                .await
            {
                Ok(p) => p,
                Err(e) => {
                    tracing::warn!("poll of {} failed: {e}", s.topics[k]);
                    continue;
                }
            };
            for m in &polled.messages {
                let offset = m.header.offset;
                next[k] = offset + 1;
                idle = false;
                let kind = KINDS[k];
                let parsed = envelope::record(&m.payload)
                    .and_then(|r| Some((records::key(&r)?, records::parse(kind, &r)?)));
                match parsed {
                    Some((key, sample)) => {
                        counts[k] += 1;
                        table.ingest(kind, offset, key, sample, now());
                    }
                    None => table.skip(kind, offset),
                }
            }
        }

        if now() - last_check >= s.check_every_s {
            last_check = now();
            for (key, buf) in table.take_finished(now()) {
                if buf.can_frames + buf.can_dropped == 0 {
                    continue; // a session with a DBC: nothing to discover
                }
                let (session, device) = key.clone();
                let p = params.clone();
                let began = Instant::now();
                let a = tokio::task::spawn_blocking(move || analyze::analyze(buf, &p)).await.expect("analysis panicked");
                tracing::info!(
                    "session {session}:{device}: {} CAN frames, {} ids, {} with candidates, analysed in {:.1}s",
                    a.can_frames, a.ids, a.found, began.elapsed().as_secs_f64()
                );
                write_with_retry(&mut db, &s, &session, &device, &a).await;
            }
            for k in 0..4 {
                if let Some(o) = table.safe_offset(KINDS[k]) {
                    if stored[k] != Some(o) {
                        match client.store_consumer_offset(&consumer, &stream_id, &topic_ids[k], Some(0), o).await {
                            Ok(()) => stored[k] = Some(o),
                            Err(e) => tracing::warn!("storing the {} offset failed: {e}", s.topics[k]),
                        }
                    }
                }
                // watchdog: the topic grew but this reader has not moved -> restart from the stored offset
                if progress[k].0 != next[k] {
                    progress[k] = (next[k], now());
                } else if now() - progress[k].1 >= STALL_AFTER_S {
                    if let Some(count) = message_count(&client, &stream_id, &topic_ids[k]).await {
                        if count > next[k] {
                            tracing::error!("{} has {count} messages but reading stays at {}; exiting for a restart",
                                            s.topics[k], next[k]);
                            std::process::exit(1);
                        }
                    }
                    progress[k].1 = now();
                }
            }
            tracing::info!(
                "open={} read discovery={} gps={} imu={} session={} late={} next={:?}",
                table.open(), counts[0], counts[1], counts[2], counts[3], table.late, next
            );
        }
        if idle {
            tokio::time::sleep(Duration::from_millis(200)).await;
        }
    }
}

pub(crate) async fn write_with_retry(db: &mut Option<Db>, s: &Settings, session: &str, device: &str, a: &analyze::Analysis) {
    let mut delay = 1u64;
    loop {
        if db.as_ref().is_none_or(Db::is_closed) {
            match Db::connect(&s.db).await {
                Ok(d) => *db = Some(d),
                Err(e) => {
                    tracing::warn!("cannot reach the registry database: {e}; retrying in {delay}s");
                    tokio::time::sleep(Duration::from_secs(delay)).await;
                    delay = (delay * 2).min(30);
                    continue;
                }
            }
        }
        match db.as_ref().unwrap().write(session, device, analyze::VERSION, a.can_frames as i64, &a.inventory, &a.candidates).await {
            Ok(true) => return,
            Ok(false) => {
                tracing::warn!("session {session}:{device} is not in the registry; results not stored");
                return;
            }
            Err(e) => {
                tracing::warn!("writing session {session}:{device} failed: {e}; retrying in {delay}s");
                *db = None;
                tokio::time::sleep(Duration::from_secs(delay)).await;
                delay = (delay * 2).min(30);
            }
        }
    }
}

async fn message_count(client: &IggyClient, stream: &Identifier, topic: &Identifier) -> Option<u64> {
    client.get_topic(stream, topic).await.ok().flatten().map(|t| t.messages_count)
}

async fn ensure_topic(client: &IggyClient, stream: &Identifier, topic: &Identifier, name: &str) {
    if client.get_topic(stream, topic).await.expect("get_topic").is_none() {
        client
            .create_topic(stream, name, 1, CompressionAlgorithm::None, None, IggyExpiry::ServerDefault, MaxTopicSize::ServerDefault)
            .await
            .expect("create_topic");
    }
}
