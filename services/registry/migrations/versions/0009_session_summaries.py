"""Drive summaries

One row per session, written by the drive-summary service from the session's
vehicle_state rows (docs/plans/2026-11-03-trip-pipelines.md §4).

Revision ID: 0009
Revises: 0008
Create Date: 2026-11-03
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0009"
down_revision: Union[str, None] = "0008"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "session_summaries",
        sa.Column("session_pk", sa.Integer(), sa.ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("frames_used", sa.Integer(), nullable=False),
        sa.Column("frames_received", sa.Integer(), nullable=False),
        sa.Column("metrics", postgresql.JSONB(), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("session_summaries")
