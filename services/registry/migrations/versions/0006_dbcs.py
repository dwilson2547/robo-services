"""DBC files, and the DBC each vehicle decodes with

Additive: a new table and a nullable column on vehicles.

Revision ID: 0006
Revises: 0005
Create Date: 2026-11-03
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0006"
down_revision: Union[str, None] = "0005"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "dbcs",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("sha256", sa.String(64), nullable=False),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint("name", name="uq_dbcs_name"),
    )
    op.create_index("ix_dbcs_id", "dbcs", ["id"])
    op.add_column("vehicles", sa.Column("dbc_id", sa.Integer(), sa.ForeignKey("dbcs.id", ondelete="SET NULL"), nullable=True))


def downgrade() -> None:
    op.drop_column("vehicles", "dbc_id")
    op.drop_index("ix_dbcs_id", table_name="dbcs")
    op.drop_table("dbcs")
