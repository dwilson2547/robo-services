from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    email: Mapped[str] = mapped_column(String(320), unique=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    devices: Mapped[list["Device"]] = relationship("Device", back_populates="owner")


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    device_id: Mapped[str] = mapped_column(String(100), unique=True, nullable=False, index=True)
    display_name: Mapped[str] = mapped_column(String(200), nullable=False)
    hardware_spec: Mapped[str | None] = mapped_column(String(200))
    notes: Mapped[str | None] = mapped_column(Text)
    user_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("users.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    last_mode: Mapped[str | None] = mapped_column(String(10), nullable=True)
    source: Mapped[str] = mapped_column(String(20), default="manual")

    owner: Mapped["User | None"] = relationship("User", back_populates="devices")
    profiles: Mapped[list["DeviceProfile"]] = relationship(
        "DeviceProfile", back_populates="device",
        order_by="DeviceProfile.version.desc()",
        cascade="all, delete-orphan",
    )
    mode_configs: Mapped[list["DeviceModeConfig"]] = relationship(
        "DeviceModeConfig", back_populates="device", cascade="all, delete-orphan"
    )


class DeviceProfile(Base):
    __tablename__ = "device_profiles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    device_id: Mapped[str] = mapped_column(
        String(100), ForeignKey("devices.device_id"), nullable=False, index=True
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    profile_json: Mapped[dict] = mapped_column(JSONB, nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    device: Mapped["Device"] = relationship("Device", back_populates="profiles")


class Track(Base):
    __tablename__ = "tracks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(300), nullable=False)
    country: Mapped[str | None] = mapped_column(String(100))
    surface_type: Mapped[str | None] = mapped_column(String(50))
    source: Mapped[str] = mapped_column(String(50), default="user_built")  # osm | user_built
    geometry: Mapped[dict | None] = mapped_column(JSONB)  # GeoJSON centerline/canonical course geometry
    start_line: Mapped[dict | None] = mapped_column(JSONB)  # GeoJSON Point feature
    osm_relation_id: Mapped[int | None] = mapped_column(Integer)
    osm_way_ids: Mapped[list | None] = mapped_column(JSONB)  # list[int] for unnamed-way tracks
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Pipeline(Base):
    __tablename__ = "pipelines"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    description: Mapped[str | None] = mapped_column(Text)
    default_config: Mapped[dict | None] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    assignments: Mapped[list["DevicePipelineAssignment"]] = relationship(
        "DevicePipelineAssignment", back_populates="pipeline", cascade="all, delete-orphan"
    )


class DeviceModeConfig(Base):
    __tablename__ = "device_mode_configs"
    __table_args__ = (UniqueConstraint("device_id", "mode"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    device_id: Mapped[str] = mapped_column(
        String(100), ForeignKey("devices.device_id", ondelete="CASCADE"), nullable=False
    )
    mode: Mapped[str] = mapped_column(String(10), nullable=False)  # "race" | "trip"
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    device: Mapped["Device"] = relationship("Device", back_populates="mode_configs")
    assignments: Mapped[list["DevicePipelineAssignment"]] = relationship(
        "DevicePipelineAssignment", back_populates="mode_config", cascade="all, delete-orphan"
    )


class DevicePipelineAssignment(Base):
    __tablename__ = "device_pipeline_assignments"
    __table_args__ = (UniqueConstraint("device_mode_config_id", "pipeline_id"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    device_mode_config_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("device_mode_configs.id", ondelete="CASCADE"), nullable=False
    )
    pipeline_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("pipelines.id", ondelete="CASCADE"), nullable=False
    )
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    config_overrides: Mapped[dict | None] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    mode_config: Mapped["DeviceModeConfig"] = relationship("DeviceModeConfig", back_populates="assignments")
    pipeline: Mapped["Pipeline"] = relationship("Pipeline", back_populates="assignments")
