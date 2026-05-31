"""Add pipelines, device_mode_configs, device_pipeline_assignments; extend devices with heartbeat columns

Revision ID: 0003
Revises: 0002
Create Date: 2026-05-31
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Extend devices with heartbeat / auto-detection columns (all nullable/defaulted — safe for existing rows)
    op.add_column("devices", sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("devices", sa.Column("last_mode", sa.String(10), nullable=True))
    op.add_column(
        "devices",
        sa.Column("source", sa.String(20), nullable=False, server_default="manual"),
    )

    # System-level pipeline catalogue
    op.create_table(
        "pipelines",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("default_config", postgresql.JSONB(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("uq_pipelines_name", "pipelines", ["name"], unique=True)

    # Seed the two known pipelines
    op.execute(
        sa.text(
            "INSERT INTO pipelines (name, description) VALUES "
            "('speed', 'Speed aggregation pipeline'), "
            "('lap', 'Lap tracking pipeline')"
        )
    )

    # Per-device, per-mode configuration bucket
    op.create_table(
        "device_mode_configs",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "device_id",
            sa.String(100),
            sa.ForeignKey("devices.device_id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("mode", sa.String(10), nullable=False),  # "race" | "trip"
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "uq_device_mode_configs",
        "device_mode_configs",
        ["device_id", "mode"],
        unique=True,
    )

    # Which pipelines are active for each device+mode config
    op.create_table(
        "device_pipeline_assignments",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "device_mode_config_id",
            sa.Integer(),
            sa.ForeignKey("device_mode_configs.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "pipeline_id",
            sa.Integer(),
            sa.ForeignKey("pipelines.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("config_overrides", postgresql.JSONB(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "uq_device_pipeline_assignments",
        "device_pipeline_assignments",
        ["device_mode_config_id", "pipeline_id"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("uq_device_pipeline_assignments", "device_pipeline_assignments")
    op.drop_table("device_pipeline_assignments")
    op.drop_index("uq_device_mode_configs", "device_mode_configs")
    op.drop_table("device_mode_configs")
    op.drop_index("uq_pipelines_name", "pipelines")
    op.drop_table("pipelines")
    op.drop_column("devices", "source")
    op.drop_column("devices", "last_mode")
    op.drop_column("devices", "last_seen_at")
