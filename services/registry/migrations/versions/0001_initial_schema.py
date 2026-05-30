"""initial schema

Revision ID: 0001
Revises:
Create Date: 2026-05-30
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("email", sa.String(320), nullable=False, unique=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_users_email", "users", ["email"])

    op.create_table(
        "devices",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("device_id", sa.String(100), nullable=False, unique=True),
        sa.Column("display_name", sa.String(200), nullable=False),
        sa.Column("hardware_spec", sa.String(200), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_devices_device_id", "devices", ["device_id"])

    op.create_table(
        "device_profiles",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("device_id", sa.String(100), sa.ForeignKey("devices.device_id"), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("profile_json", postgresql.JSONB(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False, default=False),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_device_profiles_device_id", "device_profiles", ["device_id"])

    op.create_table(
        "tracks",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(300), nullable=False),
        sa.Column("country", sa.String(100), nullable=True),
        sa.Column("surface_type", sa.String(50), nullable=True),
        sa.Column("source", sa.String(50), nullable=False, server_default="user_built"),
        sa.Column("geometry", postgresql.JSONB(), nullable=True),
        sa.Column("start_line", postgresql.JSONB(), nullable=True),
        sa.Column("osm_relation_id", sa.Integer(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_table("tracks")
    op.drop_table("device_profiles")
    op.drop_index("ix_devices_device_id", "devices")
    op.drop_table("devices")
    op.drop_index("ix_users_email", "users")
    op.drop_table("users")
