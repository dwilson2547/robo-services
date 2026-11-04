//! can-decode-rs: Rust shadow of services/can_decode.
//!
//! Reads Iggy `telemetry.raw.can` as consumer group CAN_DECODE_GROUP (default
//! "can-decode-rs"), decodes each frame with the default DBC, and publishes to
//! CAN_DECODE_OUTPUT_TOPIC (default "telemetry.decoded.can.rs"; unknown frames
//! to CAN_DECODE_UNKNOWN_TOPIC when set). The group offset is committed every
//! COMMIT_EVERY messages *after* they are processed and published, and once
//! more on shutdown: a crash re-decodes at most that many frames, never skips
//! any. A watchdog exits the process if the consumer silently stalls (iggy
//! consumers do not rejoin their group after a server restart).

mod config;
mod dbc;
mod decode;
mod envelope;
mod watchdog;

use config::Settings;
use dbc::Dbc;
use decode::Outcome;
use envelope::{decode_message, encode_message};
use iggy::prelude::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use watchdog::ProgressWatchdog;

struct Counters {
    decoded: AtomicU64,
    unknown: AtomicU64,
    unreadable: AtomicU64,
}

#[tokio::main]
async fn main() {
    let settings = Settings::from_env();
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_env("CAN_DECODE_LOG_LEVEL")
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let dbc_text = std::fs::read_to_string(&settings.dbc_path)
        .unwrap_or_else(|e| panic!("cannot read DBC_PATH {}: {e}", settings.dbc_path));
    let dbc = dbc::load_dbc(&dbc_text).unwrap_or_else(|e| panic!("cannot parse DBC: {e}"));
    let dbc_name = settings.default_dbc_name.clone();
    tracing::info!(
        "{} -> {} (unknown: {}), DBC {} ({} messages), consumer group {}",
        settings.in_topic,
        settings.out_topic,
        if settings.unknown_topic.is_empty() { "dropped" } else { &settings.unknown_topic },
        dbc_name,
        dbc.messages.len(),
        settings.group,
    );

    let client = IggyClient::from_connection_string(&settings.connection_string)
        .expect("bad IGGY_CONNECTION_STRING");
    client.connect().await.expect("cannot connect to Iggy");

    let stream_id = Identifier::named(&settings.stream).expect("bad stream name");
    let in_topic_id = Identifier::named(&settings.in_topic).expect("bad input topic");
    let out_topic_id = Identifier::named(&settings.out_topic).expect("bad output topic");
    let unknown_topic_id = if settings.unknown_topic.is_empty() {
        None
    } else {
        Some(Identifier::named(&settings.unknown_topic).expect("bad unknown topic"))
    };
    let group_id = Identifier::named(&settings.group).expect("bad group name");

    ensure_stream(&client, &stream_id, &settings.stream).await;
    ensure_topic(&client, &stream_id, &in_topic_id, &settings.in_topic).await;
    ensure_topic(&client, &stream_id, &out_topic_id, &settings.out_topic).await;
    if let Some(id) = &unknown_topic_id {
        ensure_topic(&client, &stream_id, id, &settings.unknown_topic).await;
    }
    // create the group if missing, then join it
    let _ = client
        .create_consumer_group(&stream_id, &in_topic_id, &settings.group)
        .await;
    client
        .join_consumer_group(&stream_id, &in_topic_id, &group_id)
        .await
        .expect("cannot join consumer group");

    let consumer = Consumer {
        kind: ConsumerKind::ConsumerGroup,
        id: group_id.clone(),
    };

    let counters = Arc::new(Counters {
        decoded: AtomicU64::new(0),
        unknown: AtomicU64::new(0),
        unreadable: AtomicU64::new(0),
    });
    let last_offset = Arc::new(AtomicU64::new(u64::MAX)); // u64::MAX = none yet
    let client = Arc::new(client);
    let (stop_tx, stop_rx) = tokio::sync::watch::channel(false);

    // SIGTERM/SIGINT -> graceful stop, then commit exactly what was processed
    tokio::spawn(async move {
        let mut sigterm = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("SIGTERM handler");
        let mut sigint = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())
            .expect("SIGINT handler");
        tokio::select! {
            _ = sigterm.recv() => tracing::info!("SIGTERM received"),
            _ = sigint.recv() => tracing::info!("SIGINT received"),
        }
        let _ = stop_tx.send(true);
    });

    // periodic counters
    {
        let counters = counters.clone();
        let last_offset = last_offset.clone();
        let mut stop_rx = stop_rx.clone();
        let interval = settings.report_interval_s;
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_secs(interval)) => {},
                    _ = stop_rx.changed() => break,
                }
                let off = last_offset.load(Ordering::Relaxed);
                tracing::info!(
                    "decoded={} unknown={} unreadable={} offset={}",
                    counters.decoded.load(Ordering::Relaxed),
                    counters.unknown.load(Ordering::Relaxed),
                    counters.unreadable.load(Ordering::Relaxed),
                    if off == u64::MAX { "none".to_string() } else { off.to_string() },
                );
            }
        });
    }

    // watchdog: exit(1) on stall so the pod restarts and rejoins the group
    {
        let last_offset = last_offset.clone();
        let client = client.clone();
        let stream_id = stream_id.clone();
        let in_topic_id = in_topic_id.clone();
        let mut stop_rx = stop_rx.clone();
        tokio::spawn(async move {
            let mut dog = ProgressWatchdog::new(settings.stall_after_s as f64, 4);
            let mut ticker = tokio::time::interval(Duration::from_secs(settings.watchdog_interval_s));
            loop {
                tokio::select! {
                    _ = ticker.tick() => {},
                    _ = stop_rx.changed() => break,
                }
                let count = match client.get_topic(&stream_id, &in_topic_id).await {
                    Ok(Some(details)) => details.messages_count,
                    Ok(None) => {
                        tracing::warn!("watchdog: topic not found");
                        continue;
                    }
                    Err(e) => {
                        tracing::warn!("watchdog: could not read topic: {e}");
                        if let Some(reason) = dog.failed() {
                            tracing::error!("consumer stalled ({reason}); exiting for a restart from the committed offset");
                            std::process::exit(1);
                        }
                        continue;
                    }
                };
                let off = last_offset.load(Ordering::Relaxed);
                let consumed = if off == u64::MAX { None } else { Some(off) };
                if let Some(reason) = dog.observe(count, consumed, monotonic_now()) {
                    tracing::error!("consumer stalled ({reason}); exiting for a restart from the committed offset");
                    std::process::exit(1);
                }
            }
        });
    }

    // consume loop
    let mut since_commit = 0u64;
    loop {
        if *stop_rx.borrow() {
            break;
        }
        let polled = match client
            .poll_messages(
                &stream_id,
                &in_topic_id,
                None, // consumer group assigns the partition
                &consumer,
                &PollingStrategy::next(),
                settings.batch_length,
                false, // manual commit: only after publish succeeds
            )
            .await
        {
            Ok(p) => p,
            Err(e) => {
                tracing::warn!("poll failed: {e}; retrying");
                tokio::time::sleep(Duration::from_millis(settings.poll_interval_ms)).await;
                continue;
            }
        };
        if polled.messages.is_empty() {
            tokio::time::sleep(Duration::from_millis(settings.poll_interval_ms)).await;
            continue;
        }
        let partition = Some(polled.partition_id);
        for message in &polled.messages {
            let offset = message.header.offset;
            process_message(
                &client, &stream_id, &out_topic_id, unknown_topic_id.as_ref(),
                &dbc, &dbc_name, message, &counters,
            )
            .await;
            last_offset.store(offset, Ordering::Relaxed);
            since_commit += 1;
            if since_commit >= settings.commit_every {
                let _ = client
                    .store_consumer_offset(&consumer, &stream_id, &in_topic_id, partition, offset)
                    .await;
                since_commit = 0;
            }
        }
    }

    // commit exactly what was processed, then leave the group
    let off = last_offset.load(Ordering::Relaxed);
    if off != u64::MAX {
        // topics here have a single partition; the group polled partition 0
        if let Err(e) = client
            .store_consumer_offset(&consumer, &stream_id, &in_topic_id, Some(0), off)
            .await
        {
            tracing::warn!("final offset commit failed: {e}");
        } else {
            tracing::info!("stopped; committed offset {off}");
        }
    }
    let _ = client
        .leave_consumer_group(&stream_id, &in_topic_id, &group_id)
        .await;
}

fn monotonic_now() -> f64 {
    use std::sync::OnceLock;
    static START: OnceLock<std::time::Instant> = OnceLock::new();
    START.get_or_init(std::time::Instant::now).elapsed().as_secs_f64()
}

#[allow(clippy::too_many_arguments)]
async fn process_message(
    client: &IggyClient,
    stream_id: &Identifier,
    out_topic_id: &Identifier,
    unknown_topic_id: Option<&Identifier>,
    dbc: &Dbc,
    dbc_name: &str,
    message: &IggyMessage,
    counters: &Counters,
) {
    let offset = message.header.offset;
    let (payload, mut headers) = decode_message(&message.payload);
    let record: serde_json::Value = match serde_json::from_slice(&payload) {
        Ok(r) => r,
        Err(_) => {
            counters.unreadable.fetch_add(1, Ordering::Relaxed);
            tracing::warn!("unreadable record at offset {offset}");
            return;
        }
    };
    let outcome = decode::decode_record(&record, dbc, dbc_name);
    let (topic_id, record, source_type) = match outcome {
        Outcome::Decoded(rec) => {
            counters.decoded.fetch_add(1, Ordering::Relaxed);
            (out_topic_id, rec, "can_decoded")
        }
        Outcome::Unknown(rec, _reason) => {
            counters.unknown.fetch_add(1, Ordering::Relaxed);
            match unknown_topic_id {
                Some(id) => (id, rec, "can_unknown"),
                None => return,
            }
        }
    };
    headers.insert("x-source-type".to_string(), source_type.to_string());
    headers.insert("x-source-offset".to_string(), offset.to_string());
    // serde_json::Value uses a BTreeMap, so keys serialize sorted, matching
    // Python's json.dumps(..., sort_keys=True, separators=(",", ":")).
    let body = serde_json::to_vec(&record).expect("record serialization cannot fail");
    let envelope = encode_message(&body, &headers);
    publish_with_retry(client, stream_id, topic_id, envelope).await;
}

/// Publish retries until Iggy accepts the message, because the offset must not
/// be committed past a frame whose publish failed.
async fn publish_with_retry(client: &IggyClient, stream_id: &Identifier, topic_id: &Identifier, envelope: Vec<u8>) {
    let mut delay = Duration::from_secs(1);
    loop {
        let mut messages = vec![IggyMessage::builder()
            .payload(bytes::Bytes::from(envelope.clone()))
            .build()
            .expect("message build cannot fail")];
        match client
            .send_messages(stream_id, topic_id, &Partitioning::partition_id(0), &mut messages)
            .await
        {
            Ok(()) => return,
            Err(e) => {
                tracing::warn!("publish failed, retrying in {:?}: {e}", delay);
                tokio::time::sleep(delay).await;
                delay = std::cmp::min(delay * 2, Duration::from_secs(30));
            }
        }
    }
}

async fn ensure_stream(client: &IggyClient, stream_id: &Identifier, name: &str) {
    if client.get_stream(stream_id).await.expect("get_stream").is_none() {
        client.create_stream(name).await.expect("create_stream");
    }
}

async fn ensure_topic(client: &IggyClient, stream_id: &Identifier, topic_id: &Identifier, name: &str) {
    if client
        .get_topic(stream_id, topic_id)
        .await
        .expect("get_topic")
        .is_none()
    {
        client
            .create_topic(
                stream_id,
                name,
                1,
                CompressionAlgorithm::None,
                None,
                IggyExpiry::ServerDefault,
                MaxTopicSize::ServerDefault,
            )
            .await
            .expect("create_topic");
    }
}
