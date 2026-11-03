"""Drop the profile / pipeline placeholders; link devices to users by sub

Device profiles, the pipeline catalogue, per-mode device configs and pipeline
assignments never held data (0 rows each when dropped, 2026-11-03), and the -r/-t
device-id suffix behind devices.last_mode is gone with them: sessions carry their
mode. Self-registering pipelines a user picks for their data feed are still the
goal; they will be designed afresh when there are pipelines to register.

Devices now get their owner from the account that publishes them (kreceiver's
user_sub stamp). Existing devices are linked from the sessions they recorded.

Revision ID: 0007
Revises: 0006
Create Date: 2026-11-03
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0007"
down_revision: Union[str, None] = "0006"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.drop_table("device_pipeline_assignments")
    op.drop_table("device_mode_configs")
    op.drop_table("pipelines")
    op.drop_table("device_profiles")
    op.drop_column("devices", "last_mode")

    # owner from the newest session each unowned device recorded under a known account
    op.execute(
        """
        UPDATE devices d SET user_id = u.id
        FROM (
            SELECT DISTINCT ON (device_id) device_id, user_sub
            FROM sessions WHERE user_sub IS NOT NULL
            ORDER BY device_id, started_at DESC NULLS LAST
        ) s
        JOIN users u ON u.sub = s.user_sub
        WHERE d.device_id = s.device_id AND d.user_id IS NULL
        """
    )


def downgrade() -> None:
    # structure only: the dropped tables were empty
    op.add_column("devices", sa.Column("last_mode", sa.String(10), nullable=True))
    op.create_table(
        "device_profiles",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("device_id", sa.String(100), sa.ForeignKey("devices.device_id"), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("profile_json", postgresql.JSONB(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_device_profiles_device_id", "device_profiles", ["device_id"])
    op.create_table(
        "pipelines",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(100), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("default_config", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("uq_pipelines_name", "pipelines", ["name"], unique=True)
    op.create_table(
        "device_mode_configs",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "device_id", sa.String(100), sa.ForeignKey("devices.device_id", ondelete="CASCADE"), nullable=False
        ),
        sa.Column("mode", sa.String(10), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("uq_device_mode_configs", "device_mode_configs", ["device_id", "mode"], unique=True)
    op.create_table(
        "device_pipeline_assignments",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column(
            "device_mode_config_id",
            sa.Integer(),
            sa.ForeignKey("device_mode_configs.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("pipeline_id", sa.Integer(), sa.ForeignKey("pipelines.id", ondelete="CASCADE"), nullable=False),
        sa.Column("enabled", sa.Boolean(), nullable=False, server_default="true"),
        sa.Column("config_overrides", postgresql.JSONB(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index(
        "uq_device_pipeline_assignments",
        "device_pipeline_assignments",
        ["device_mode_config_id", "pipeline_id"],
        unique=True,
    )
