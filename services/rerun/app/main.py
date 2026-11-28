"""Re-run a drive: decode its archived raw records with the vehicle's current DBC,
replace its vehicle_state rows and recompute its summary.

    python -m app.main <source_session> [...]          # report only
    python -m app.main <source_session> --apply        # replace the stored results
    python -m app.main --vehicle <id> [--apply]        # every drive of a vehicle

Nothing is published to the live topics. The rows are replaced in one transaction,
and only when the archive holds everything the receipts count (--allow-short) and
the rebuild has at least as many rows as are stored (--allow-fewer).
"""

from __future__ import annotations

import argparse
import hashlib
import logging
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json, execute_values

from .rebuild import Receipt, rebuild
from .reuse import COLUMNS, JSON_COLUMNS, UPSERT, VERSION, completeness, load_dbc, one_line, session_rows, summarise
from .source import LocalArchive, S3Archive

LOG = logging.getLogger("rerun")
QUIET = timedelta(minutes=30)

SESSION = """
SELECT s.id, s.device_id, s.user_sub, s.vehicle_id, s.ended_at, s.last_frame_received_at,
       s.frames_expected, s.frames_received, d.name, d.content, d.roles
FROM sessions s LEFT JOIN vehicles v ON v.id = s.vehicle_id LEFT JOIN dbcs d ON d.id = v.dbc_id
WHERE s.source_session = %s
"""


class Refused(Exception):
    pass


def rerun(session: str, reg, ts, archive, *, apply: bool, schema: str = "telemetry", table: str = "vehicle_state",
          allow_short: bool = False, allow_fewer: bool = False, now: datetime | None = None) -> str:
    now = now or datetime.now(timezone.utc)
    with reg, reg.cursor() as cur:
        cur.execute(SESSION, (session,))
        found = cur.fetchall()
        if len(found) != 1:
            raise Refused(f"{len(found)} sessions with id {session}")
        pk, device, user_sub, vehicle_id, ended_at, last_at, expected, received, dbc_name, content, roles = found[0]
        cur.execute("SELECT base_timestamp_us, gps, imu, can, imu_missing, received_at FROM session_frames"
                    " WHERE session_pk = %s", (pk,))
        receipts = {r[0]: Receipt(gps=r[1], imu=r[2], can=r[3], imu_missing=r[4], received_at=r[5]) for r in cur.fetchall()}
        cur.execute("SELECT metrics FROM session_summaries WHERE session_pk = %s", (pk,))
        previous = (cur.fetchone() or [None])[0]
    # vehicle-state may still be writing a live or freshly caught-up drive
    if last_at is not None and now - last_at < QUIET:
        raise Refused(f"frames arrived {(now - last_at).seconds // 60} min ago; wait until 30 min quiet")
    if not receipts:
        raise Refused("the registry has no frame receipts for it")

    qt = sql.Identifier(schema, table)
    with ts, ts.cursor() as cur:
        cur.execute(sql.SQL("SELECT t, written_at, speed_kph IS NOT NULL, rpm IS NOT NULL, dbc FROM {}"
                            " WHERE source_session = %s AND device_id = %s").format(qt), (session, device))
        old = cur.fetchall()
    written_before = {t: w for t, w, *_ in old}

    dbc = (dbc_name, load_dbc(content)) if content else None
    stamp = datetime.now(timezone.utc)
    new_speed = new_rpm = 0
    # one transaction on the Timescale side: an error or a refusal leaves the old rows
    try:
        cur = ts.cursor()
        if apply:  # a report-only run never writes, not even inside a transaction
            cur.execute(sql.SQL("DELETE FROM {} WHERE source_session = %s AND device_id = %s").format(qt), (session, device))
        insert = sql.SQL("INSERT INTO {} ({}) VALUES %s").format(
            qt, sql.SQL(", ").join(map(sql.Identifier, COLUMNS))).as_string(ts)

        def write(rows: list[dict]) -> None:
            nonlocal new_speed, new_rpm
            for row in rows:
                # keep when each row was first written: the summary's lag figures come from it
                row["written_at"] = written_before.get(row["t"], stamp)
                new_speed += row.get("speed_kph") is not None
                new_rpm += row.get("rpm") is not None
            if rows and apply:
                execute_values(cur, insert, [
                    tuple(Json(r.get(c)) if c in JSON_COLUMNS and r.get(c) is not None else r.get(c) for c in COLUMNS)
                    for r in rows])

        with tempfile.TemporaryDirectory(prefix="rerun-") as work:
            result = rebuild(archive, session, device, receipts, dbc, (roles or {}) if dbc else {}, vehicle_id,
                             user_sub, Path(work), write)
        report = (
            f"{session}: dbc {dbc_name or 'none'}; frames {dict(result.frames)}; records {dict(result.records)}"
            f" (decoded {result.decoded}, unknown {result.unknown}, duplicates dropped {result.duplicates},"
            f" untagged {result.untagged}); rows {len(old)} -> {result.rows};"
            f" with speed {sum(bool(o[2]) for o in old)} -> {new_speed}, with rpm {sum(bool(o[3]) for o in old)} -> {new_rpm}"
        )
        if result.short and not allow_short:
            raise Refused(f"{report}\n  the archive is short of the receipts: {dict(result.short)}")
        if result.rows < len(old) and not allow_fewer:
            raise Refused(f"{report}\n  fewer rows than are stored")
        if not result.rows:
            raise Refused(f"{report}\n  nothing to write")
        if not apply:
            ts.rollback()
            return report + "\n  report only; nothing changed (--apply replaces)"
        ts.commit()
    except BaseException:
        ts.rollback()
        raise

    rows = session_rows(ts, schema, table, session, device)
    metrics = summarise(rows, frames_received=received,
                        completeness_pct=completeness(expected, received, ended_at is not None))
    if previous and previous.get("processing"):
        metrics["processing"] = previous["processing"]  # how the drive ran live; a re-run says nothing about it
    metrics["rerun"] = {"at": stamp.isoformat(), "dbc": dbc_name,
                        "dbc_sha256": hashlib.sha256(content.encode()).hexdigest() if content else None}
    with reg, reg.cursor() as cur:
        cur.execute(UPSERT, {"pk": pk, "version": VERSION, "frames_used": metrics["quality"]["frames_used"],
                             "frames_received": received, "metrics": Json(metrics)})
    return f"{report}\n  replaced; {one_line(session, metrics)}"


def _connect(prefix: str, name: str):
    return psycopg2.connect(
        host=os.environ[f"{prefix}_HOST"], port=int(os.getenv(f"{prefix}_PORT", "5432")),
        dbname=os.getenv(f"{prefix}_NAME", name), user=os.environ[f"{prefix}_USER"],
        password=os.getenv(f"{prefix}_PASSWORD", ""))


def _archive(local: str | None):
    if local:
        return LocalArchive(local)
    import boto3

    client = boto3.client("s3", endpoint_url=os.environ["ARCHIVE_S3_ENDPOINT"],
                          aws_access_key_id=os.environ["ARCHIVE_S3_ACCESS_KEY"],
                          aws_secret_access_key=os.environ["ARCHIVE_S3_SECRET_KEY"], region_name="us-east-1")
    return S3Archive(client, os.getenv("ARCHIVE_S3_BUCKET", "race-logger"), os.getenv("ARCHIVE_KEY_PREFIX", "raw-telemetry"))


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="*", help="source session ids")
    ap.add_argument("--vehicle", type=int, help="every session of this registry vehicle")
    ap.add_argument("--apply", action="store_true", help="replace the stored rows and summary")
    ap.add_argument("--allow-short", action="store_true", help="go ahead though the archive lacks records")
    ap.add_argument("--allow-fewer", action="store_true", help="go ahead though there are fewer rows than stored")
    ap.add_argument("--local-archive", help="read <dir>/<session>/<kind>.parquet instead of the bucket")
    a = ap.parse_args(argv)
    if bool(a.sessions) == (a.vehicle is not None):
        ap.error("give session ids or --vehicle")
    logging.basicConfig(level="INFO", format="%(message)s")
    reg = _connect("DB", "robo_registry")
    ts = _connect("TS", "postgres")
    schema, table = os.getenv("TS_SCHEMA", "telemetry"), os.getenv("TS_TABLE", "vehicle_state")
    sessions = a.sessions
    if a.vehicle is not None:
        with reg, reg.cursor() as cur:
            cur.execute("SELECT source_session FROM sessions WHERE vehicle_id = %s ORDER BY id", (a.vehicle,))
            sessions = [r[0] for r in cur.fetchall()]
    archive = _archive(a.local_archive)
    refused = 0
    for session in sessions:
        try:
            LOG.info(rerun(session, reg, ts, archive, apply=a.apply, schema=schema, table=table,
                           allow_short=a.allow_short, allow_fewer=a.allow_fewer))
        except Refused as exc:
            refused += 1
            LOG.error("%s: refused: %s", session, exc)
    return 1 if refused else 0


if __name__ == "__main__":
    sys.exit(main())
