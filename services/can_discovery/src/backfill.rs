//! One-off discovery for a past session: `can-discovery backfill <source_session> <device_id>`.
//!
//! The service only analyses sessions that arrive on telemetry.discovery.can while
//! it runs. A past drive (recorded before discovery existed, or under the wrong
//! vehicle) is read instead from the raw topics: telemetry.raw.can (which has the
//! same payload as a discovery record), GPS, IMU and session events. Each topic is
//! binary-searched for the session's start (records are close to time order; the
//! session id is its start in epoch ms), read forward to the end, and filtered to
//! the session. The result is written like any other. Offsets are never stored.

use std::time::Instant;

use iggy::prelude::*;
use serde_json::Value;

use crate::config::Settings;
use crate::records::{self, Key};
use crate::session::{Table, KINDS};
use crate::{analyze, correlate, envelope, write_with_retry};

/// How far before the session's start to begin reading, for records slightly out of order.
const LEAD_MS: i64 = 5 * 60 * 1000;
const BATCH: u32 = 1000;

fn record_time_ms(rec: &Value) -> Option<i64> {
    if let Some(us) = rec.get("payload").and_then(|p| p.get("base_timestamp_us")).and_then(Value::as_i64) {
        return Some(us / 1000);
    }
    records::time_ms(rec.get("captured_at")?.as_str()?)
}

async fn time_at(client: &IggyClient, stream: &Identifier, topic: &Identifier, consumer: &Consumer, offset: u64) -> Option<i64> {
    // a message without a time (unreadable) borrows the next one's
    let polled = client
        .poll_messages(stream, topic, Some(0), consumer, &PollingStrategy::offset(offset), 20, false)
        .await
        .ok()?;
    polled.messages.iter().find_map(|m| envelope::record(&m.payload).as_ref().and_then(record_time_ms))
}

/// First offset whose record time is at or after `from_ms` (or the topic's end).
async fn first_offset_from(client: &IggyClient, stream: &Identifier, topic: &Identifier, consumer: &Consumer,
                           count: u64, from_ms: i64) -> u64 {
    let (mut lo, mut hi) = (0u64, count);
    while lo < hi {
        let mid = lo + (hi - lo) / 2;
        match time_at(client, stream, topic, consumer, mid).await {
            Some(t) if t < from_ms => lo = mid + 1,
            _ => hi = mid,
        }
    }
    lo
}

pub async fn run(s: &Settings, raw_can_topic: &str, session: &str, device: &str) {
    let start_ms: i64 = session.parse().expect("a session id is its start time in epoch ms");
    let client = IggyClient::from_connection_string(&s.connection_string).expect("bad IGGY_CONNECTION_STRING");
    client.connect().await.expect("cannot connect to Iggy");
    let stream = Identifier::named(&s.stream).expect("bad stream name");
    let consumer = Consumer { kind: ConsumerKind::Consumer, id: Identifier::named("can-discovery-backfill").unwrap() };
    let key: Key = (session.to_string(), device.to_string());
    let mut table = Table::new(s.max_can_frames, 0.0, 0.0);
    let began = Instant::now();

    // the raw CAN topic stands in for the discovery topic
    let topics = [raw_can_topic, s.topics[1].as_str(), s.topics[2].as_str(), s.topics[3].as_str()];
    for (k, name) in topics.iter().enumerate() {
        let topic = Identifier::named(name).expect("bad topic name");
        let count = client.get_topic(&stream, &topic).await.ok().flatten().map(|t| t.messages_count).unwrap_or(0);
        let mut next = first_offset_from(&client, &stream, &topic, &consumer, count, start_ms - LEAD_MS).await;
        let first = next;
        let mut kept = 0usize;
        while next < count {
            let polled = match client
                .poll_messages(&stream, &topic, Some(0), &consumer, &PollingStrategy::offset(next), BATCH, false)
                .await
            {
                Ok(p) if !p.messages.is_empty() => p,
                Ok(_) => break,
                Err(e) => panic!("poll of {name} failed: {e}"),
            };
            for m in &polled.messages {
                next = m.header.offset + 1;
                let Some(rec) = envelope::record(&m.payload) else { continue };
                if records::key(&rec).as_ref() != Some(&key) {
                    continue;
                }
                if let Some(sample) = records::parse(KINDS[k], &rec) {
                    table.ingest(KINDS[k], m.header.offset, key.clone(), sample, 0.0);
                    kept += 1;
                }
            }
        }
        tracing::info!("{name}: read offsets {first}..{next}, kept {kept} for {session}:{device}");
    }

    let Some((_, buf)) = table.take_finished(f64::MAX).into_iter().next() else {
        tracing::error!("nothing found for {session}:{device}");
        std::process::exit(1);
    };
    if buf.can_frames + buf.can_dropped == 0 {
        tracing::error!("{session}:{device} has no CAN frames in {raw_can_topic}");
        std::process::exit(1);
    }
    let a = tokio::task::spawn_blocking(move || analyze::analyze(buf, &correlate::Params::default()))
        .await
        .expect("analysis panicked");
    tracing::info!(
        "session {session}:{device} (backfill): {} CAN frames, {} ids, {} with candidates; {:.0}s in all",
        a.can_frames, a.ids, a.found, began.elapsed().as_secs_f64()
    );
    let mut db = None;
    write_with_retry(&mut db, s, session, device, &a).await;
}
