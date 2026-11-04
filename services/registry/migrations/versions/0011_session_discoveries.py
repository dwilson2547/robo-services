"""DBC discovery results

One row per session whose vehicle has no DBC, written by the can-discovery service:
a per-CAN-id inventory and the signal candidates correlated against GPS and IMU
(docs/plans/2026-11-04-dbc-discovery.md §2). For review; nothing is promoted to a
DBC automatically.

Revision ID: 0011
Revises: 0010
Create Date: 2026-11-04
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0011"
down_revision: Union[str, None] = "0010"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "session_discoveries",
        sa.Column("session_pk", sa.Integer(), sa.ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("can_frames", sa.BigInteger(), nullable=False),
        sa.Column("inventory", postgresql.JSONB(), nullable=False),
        sa.Column("candidates", postgresql.JSONB(), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("session_discoveries")
