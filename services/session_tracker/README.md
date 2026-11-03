# session_tracker

Reads `telemetry.session` (kreceiver) and maintains the registry's `sessions` and
`session_frames` tables, so the registry can say whether a recording session
arrived whole (docs/plans/2026-11-03-recording-sessions.md).

Records it acts on (kreceiver normalized messages, `message_type`):

- `session_start`, `session_end`: the phone gateway's events; upserted on
  `(source_session, device_id)`.
- `frame`: kreceiver's receipt for each frame of a session (base timestamp and
  sample counts). Inserted once per `(session, base_timestamp_us)`;
  `frames_received` grows only on a new insert, so redelivery never inflates it.

Everything else is ignored. The schema is the registry's (Alembic `0004`); deploy
the registry at that migration before enabling this.

Reads as Iggy consumer group `session-tracker` with committed offsets (same
pattern as can-decode). A new group starts at the beginning of the topic; every
write is idempotent, so replaying the topic is safe.

If the Iggy server restarts, apache-iggy 0.8's consumer silently stops (it
reconnects but never rejoins the group). A watchdog
(`can_pub_sub_probe.consumer_watchdog`) compares the topic's message count with
the consumed offset every 30 s and exits the process when the topic has grown
but the offset has not moved for 2 min, or the topic cannot be read 4 times in a
row; Kubernetes restarts it and it resumes from the committed offset. An idle
topic never trips it.

Config (env): `IGGY_CONNECTION_STRING`, `IGGY_STREAM`, `SESSION_TRACKER_TOPIC`,
`SESSION_TRACKER_GROUP`, `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SESSION_TRACKER_LOG_LEVEL`.

Test (needs a Postgres with the registry schema):

```bash
docker run -d --name sess-pg -e POSTGRES_HOST_AUTH_METHOD=trust -p 55432:5432 postgres:16-alpine
(cd ../registry && DATABASE_URL=postgresql://postgres@127.0.0.1:55432/postgres alembic upgrade head)
TEST_DATABASE_URL=postgresql://postgres@127.0.0.1:55432/postgres PYTHONPATH=. python -m pytest tests
```

Build (from the robo-services repo root):
`docker build -f services/session_tracker/Dockerfile -t dwilson2547/robo-services-session-tracker:<tag> .`
