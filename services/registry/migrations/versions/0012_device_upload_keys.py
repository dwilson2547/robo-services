"""Per-phone upload keys

The public half of the key a phone creates in its hardware keystore at sign-in; it
signs its own short-lived upload tokens with the private half
(docs/plans/2026-11-04-reliable-uploads.md §2). Bound to the user who registered it.

Revision ID: 0012
Revises: 0011
Create Date: 2026-11-04
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0012"
down_revision: Union[str, None] = "0011"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("devices", sa.Column("upload_key", sa.Text(), nullable=True))
    op.add_column("devices", sa.Column("upload_key_sub", sa.String(100), nullable=True))
    op.add_column("devices", sa.Column("upload_key_at", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("devices", "upload_key_at")
    op.drop_column("devices", "upload_key_sub")
    op.drop_column("devices", "upload_key")
