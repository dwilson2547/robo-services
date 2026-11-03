"""Add sessions and session_frames (phone gateway recording sessions)

Additive only: two new tables, no change to existing ones.

Revision ID: 0004
Revises: 0003
Create Date: 2026-11-03
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "sessions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("source_session", sa.String(100), nullable=False),
        sa.Column("device_id", sa.String(100), nullable=False),
        sa.Column("user_sub", sa.String(200), nullable=True),
        sa.Column("mode", sa.String(10), nullable=True),
        sa.Column("vehicle_id", sa.Integer(), nullable=True),
        sa.Column("track_id", sa.Integer(), sa.ForeignKey("tracks.id", ondelete="SET NULL"), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("end_reason", sa.String(40), nullable=True),
        sa.Column("frames_expected", sa.Integer(), nullable=True),
        sa.Column("frames_received", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("first_frame_us", sa.BigInteger(), nullable=True),
        sa.Column("last_frame_us", sa.BigInteger(), nullable=True),
        sa.Column("last_frame_received_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("start_event", postgresql.JSONB(), nullable=True),
        sa.Column("end_event", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("source_session", "device_id", name="uq_sessions_source_device"),
    )
    op.create_index("ix_sessions_id", "sessions", ["id"])
    op.create_index("ix_sessions_device_id", "sessions", ["device_id"])

    op.create_table(
        "session_frames",
        sa.Column("session_pk", sa.Integer(), sa.ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("base_timestamp_us", sa.BigInteger(), primary_key=True),
        sa.Column("gps", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("imu", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("imu_missing", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("can", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("session_frames")
    op.drop_index("ix_sessions_device_id", table_name="sessions")
    op.drop_index("ix_sessions_id", table_name="sessions")
    op.drop_table("sessions")
