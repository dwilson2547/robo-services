# rerun

Re-run a drive: decode its raw records from the Parquet archive with the vehicle's
**current** DBC, replace its `telemetry.vehicle_state` rows and recompute its
summary. For after a DBC is added or corrected, and for frames that arrived too
late for the live vehicle-state.

```bash
python -m app.main <source_session> [...]        # report only
python -m app.main <source_session> --apply      # replace the stored results
python -m app.main --vehicle <id> [--apply]      # every drive of a vehicle
```

Status (2026-11-28): tested on a local copy; run **report-only** in the cluster as a one-off Job. Nothing has
been applied to the live databases. Nothing deploys or triggers it.

## What it reads and writes

- Raw CAN, GPS and IMU: the archive bucket, `raw-telemetry/topic=…/source_session=<id>/`.
  Not Iggy: its topics are set to expire after 14 days (nothing is deleted until the
  server's cleaner, off on 2026-11-27, is turned on).
- Session, vehicle, DBC, roles, per-frame receipts: the registry database.
- Writes `telemetry.vehicle_state` (that session's rows, in one transaction) and
  `session_summaries`. Nothing is published to a live topic.

Decoding, frame closing, row building and summary metrics are the live services'
own code (`app/reuse.py`), so a change there applies here after a rebuild.

## Refusals

A refused or failed run leaves the stored rows as they were.

| Refused when | Override |
|---|---|
| frames arrived in the last 30 minutes | none |
| the archive lacks records the receipts count | `--allow-short` |
| the rebuild has fewer rows than are stored | `--allow-fewer` |

If the summary write fails after the rows are replaced (a second database), run it again.

A frame sent twice is counted once. `written_at` is kept per row and the summary's
`processing` (lag) block is carried over, since a re-run says nothing about how the
drive ran live. The summary records the DBC used under `rerun`; drive-summary
drops that when it next recomputes (a new metrics version).

Not re-run: the Flink derivations, `can-discovery` (it has its own `backfill`),
`telemetry.decoded.can`.

## Verified (2026-11-27, local copy)

- Impala 1795552564554, DBC unchanged: 530 of 530 rows identical to the live rows
  in every column (floats to 1e-6); the summary differs only by `rerun`.
- EcoSport 1795565699699, `ford_ecosport_2018_hscan` newly assigned: rows with speed
  and rpm 0 → 338; speed against phone GPS r = 0.983, median difference 0.87 km/h.
  A second run changed no row.
- In the cluster, report-only, against the real bucket: the two drives above gave the same figures; the
  4.6 h drive 1795362016311 (11.7 M CAN records) took under 8 minutes, 16492 stored rows -> 16509.
- Not tried: `--apply` on the live databases.

## Config (env)

`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (registry); `TS_HOST`,
`TS_PORT`, `TS_NAME`, `TS_USER`, `TS_PASSWORD`, `TS_SCHEMA`, `TS_TABLE`;
`ARCHIVE_S3_ENDPOINT`, `ARCHIVE_S3_ACCESS_KEY`, `ARCHIVE_S3_SECRET_KEY`,
`ARCHIVE_S3_BUCKET`, `ARCHIVE_KEY_PREFIX`. `--local-archive <dir>` reads
`<dir>/<session>/<kind>.parquet` instead of the bucket.

## Test

See the header of `tests/test_rerun.py`.
