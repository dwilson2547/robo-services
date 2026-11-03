"""Timescale `telemetry.vehicle_state`: one row per (session, device, interval).

The service creates the table (and hypertable) itself, like the track-position
sink. Writes are upserts on the primary key, so writing a frame twice gives the
same rows.
"""

from __future__ import annotations

from typing import Any

import psycopg2
from psycopg2 import sql
from psycopg2.extras import Json, execute_values

from .roles import ROLE_COLUMNS
from .snapshot import Carry

JSON_COLUMNS = (
    "accel_mean", "accel_min", "accel_max", "gyro_mean", "gyro_min", "gyro_max",
    "signals", "signal_stats", "carried",
)
COLUMNS = (
    "t", "source_session", "device_id", "frame_base_us", "vehicle_id", "user_sub",
    *ROLE_COLUMNS,
    "lat", "lon", "alt_m", "gps_speed_kph", "heading_deg", "num_sats", "hdop", "gps_fixes",
    "accel_mean", "accel_min", "accel_max", "gyro_mean", "gyro_min", "gyro_max",
    "imu_samples", "imu_filled_in_frame",
    "signals", "signal_stats", "carried", "dbc", "complete",
)
KEY = ("source_session", "device_id", "t")
ROLE_TYPES = {"brake_on": "BOOLEAN", "prndl": "TEXT"}


def _ddl(schema: str, table: str) -> list[sql.Composable]:
    qt = sql.Identifier(schema, table)
    role_cols = sql.SQL(", ").join(
        sql.SQL("{} {}").format(sql.Identifier(c), sql.SQL(ROLE_TYPES.get(c, "DOUBLE PRECISION")))
        for c in ROLE_COLUMNS
    )
    return [
        sql.SQL("CREATE SCHEMA IF NOT EXISTS {}").format(sql.Identifier(schema)),
        sql.SQL(
            "CREATE TABLE IF NOT EXISTS {} ("
            " t TIMESTAMPTZ NOT NULL,"
            " source_session TEXT NOT NULL,"
            " device_id TEXT NOT NULL,"
            " frame_base_us BIGINT NOT NULL,"
            " vehicle_id INTEGER,"
            " user_sub TEXT,"
            " {},"
            " lat DOUBLE PRECISION, lon DOUBLE PRECISION, alt_m DOUBLE PRECISION,"
            " gps_speed_kph DOUBLE PRECISION, heading_deg DOUBLE PRECISION,"
            " num_sats INTEGER, hdop DOUBLE PRECISION, gps_fixes INTEGER NOT NULL,"
            " accel_mean JSONB, accel_min JSONB, accel_max JSONB,"
            " gyro_mean JSONB, gyro_min JSONB, gyro_max JSONB,"
            " imu_samples INTEGER NOT NULL, imu_filled_in_frame INTEGER,"
            " signals JSONB NOT NULL, signal_stats JSONB NOT NULL, carried JSONB NOT NULL,"
            " dbc TEXT, complete BOOLEAN NOT NULL,"
            " PRIMARY KEY (source_session, device_id, t))"
        ).format(qt, role_cols),
        # role columns added after the table was first created
        *[
            sql.SQL("ALTER TABLE {} ADD COLUMN IF NOT EXISTS {} {}").format(
                qt, sql.Identifier(c), sql.SQL(ROLE_TYPES.get(c, "DOUBLE PRECISION")))
            for c in ROLE_COLUMNS
        ],
        sql.SQL("SELECT create_hypertable({}, 't', if_not_exists => TRUE)").format(sql.Literal(f"{schema}.{table}")),
        sql.SQL("CREATE INDEX IF NOT EXISTS {} ON {} (source_session, device_id, frame_base_us)").format(
            sql.Identifier(f"{table}_frame_idx"), qt
        ),
        # where each input topic is read from next (the service's own positions: no consumer groups)
        sql.SQL(
            "CREATE TABLE IF NOT EXISTS {} (topic TEXT PRIMARY KEY, next_offset BIGINT NOT NULL,"
            " updated_at TIMESTAMPTZ NOT NULL DEFAULT now())"
        ).format(sql.Identifier(schema, f"{table}_offsets")),
    ]


class Store:
    def __init__(self, db: dict[str, Any], schema: str = "telemetry", table: str = "vehicle_state",
                 hypertable: bool = True) -> None:
        self.db, self.schema, self.table, self.hypertable = db, schema, table, hypertable
        self.conn = None

    def connect(self) -> None:
        if self.conn is not None:
            try:
                self.conn.close()
            except psycopg2.Error:
                pass
        self.conn = psycopg2.connect(**self.db)

    def ensure_schema(self) -> None:
        with self.conn, self.conn.cursor() as cur:
            if self.hypertable:
                cur.execute("CREATE EXTENSION IF NOT EXISTS timescaledb")
            for stmt in _ddl(self.schema, self.table):
                if not self.hypertable and "create_hypertable" in stmt.as_string(self.conn):
                    continue
                cur.execute(stmt)

    def session_state(self, session: str, device: str, interval_us: int) -> tuple[set[int], Carry]:
        """Frames already written for the session, and its carry state at the last row."""
        qt = sql.Identifier(self.schema, self.table)
        with self.conn, self.conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT DISTINCT frame_base_us FROM {} WHERE source_session = %s AND device_id = %s").format(qt),
                (session, device),
            )
            written = {r[0] for r in cur.fetchall()}
            cur.execute(
                sql.SQL("SELECT t, signals, carried FROM {} WHERE source_session = %s AND device_id = %s"
                        " ORDER BY t DESC LIMIT 1").format(qt),
                (session, device),
            )
            row = cur.fetchone()
        carry: Carry = {}
        if row:
            t, signals, carried = row
            end_us = int(t.timestamp() * 1_000_000) + interval_us
            carry = {name: (value, end_us - int(carried.get(name, 0)) * 1000) for name, value in signals.items()}
        return written, carry

    def write(self, rows: list[dict[str, Any]]) -> None:
        if not rows:
            return
        values = [
            tuple(Json(r.get(c)) if c in JSON_COLUMNS and r.get(c) is not None else r.get(c) for c in COLUMNS)
            for r in rows
        ]
        updates = sql.SQL(", ").join(
            sql.SQL("{0} = EXCLUDED.{0}").format(sql.Identifier(c)) for c in COLUMNS if c not in KEY
        )
        stmt = sql.SQL("INSERT INTO {} ({}) VALUES %s ON CONFLICT ({}) DO UPDATE SET {}").format(
            sql.Identifier(self.schema, self.table),
            sql.SQL(", ").join(map(sql.Identifier, COLUMNS)),
            sql.SQL(", ").join(map(sql.Identifier, KEY)),
            updates,
        )
        with self.conn, self.conn.cursor() as cur:
            execute_values(cur, stmt.as_string(self.conn), values)

    def load_offsets(self) -> dict[str, int]:
        with self.conn, self.conn.cursor() as cur:
            cur.execute(sql.SQL("SELECT topic, next_offset FROM {}").format(
                sql.Identifier(self.schema, f"{self.table}_offsets")))
            return dict(cur.fetchall())

    def save_offsets(self, offsets: dict[str, int]) -> None:
        if not offsets:
            return
        with self.conn, self.conn.cursor() as cur:
            execute_values(
                cur,
                sql.SQL("INSERT INTO {} (topic, next_offset) VALUES %s ON CONFLICT (topic)"
                        " DO UPDATE SET next_offset = EXCLUDED.next_offset, updated_at = now()").format(
                    sql.Identifier(self.schema, f"{self.table}_offsets")).as_string(self.conn),
                list(offsets.items()),
            )

    def rows(self, session: str, device: str) -> list[dict[str, Any]]:
        """For tests and checks."""
        with self.conn, self.conn.cursor() as cur:
            cur.execute(
                sql.SQL("SELECT {} FROM {} WHERE source_session = %s AND device_id = %s ORDER BY t").format(
                    sql.SQL(", ").join(map(sql.Identifier, COLUMNS)), sql.Identifier(self.schema, self.table)
                ),
                (session, device),
            )
            return [dict(zip(COLUMNS, r)) for r in cur.fetchall()]


__all__ = ["Store", "COLUMNS"]
