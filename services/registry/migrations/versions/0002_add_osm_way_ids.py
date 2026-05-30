"""add osm_way_ids to tracks + partial unique index on osm_relation_id

Revision ID: 0002
Revises: 0001
Create Date: 2026-05-30
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("tracks", sa.Column("osm_way_ids", postgresql.JSONB(), nullable=True))
    # Prevent double-importing the same OSM relation; only applies to non-null values.
    op.create_index(
        "uq_tracks_osm_relation_id",
        "tracks",
        ["osm_relation_id"],
        unique=True,
        postgresql_where=sa.text("osm_relation_id IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("uq_tracks_osm_relation_id", "tracks")
    op.drop_column("tracks", "osm_way_ids")
