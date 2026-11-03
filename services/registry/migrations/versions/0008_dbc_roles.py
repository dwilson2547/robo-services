"""Signal roles on DBCs

A DBC's signals mapped onto fixed, vehicle-independent roles in fixed units
(speed_kph, rpm, ...), for the vehicle-state snapshots and drive summaries
(docs/plans/2026-11-03-trip-pipelines.md).

Revision ID: 0008
Revises: 0007
Create Date: 2026-11-03
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0008"
down_revision: Union[str, None] = "0007"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("dbcs", sa.Column("roles", postgresql.JSONB(), nullable=False, server_default="{}"))


def downgrade() -> None:
    op.drop_column("dbcs", "roles")
