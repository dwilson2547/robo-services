from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    email: Mapped[str] = mapped_column(String(320), unique=True, nullable=False, index=True)
    # authentik sub (phone-gateway provider), set on the user's first app API call
    sub: Mapped[str | None] = mapped_column(String(200), unique=True)
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
    source: Mapped[str] = mapped_column(String(20), default="manual")
    # the public half of the phone's upload key and the user who registered it
    # (docs/plans/2026-11-04-reliable-uploads.md §2); null = none registered
    upload_key: Mapped[str | None] = mapped_column(Text, nullable=True)
    upload_key_sub: Mapped[str | None] = mapped_column(String(100), nullable=True)
    upload_key_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    owner: Mapped["User | None"] = relationship("User", back_populates="devices")

    @property
    def owner_email(self) -> str | None:
        return self.owner.email if self.owner else None


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
    # who added it from the phone app (tracks are shared: a track is a place)
    added_by_user_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("users.id", ondelete="SET NULL"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class RecordingSession(Base):
    """One recording (phone gateway Start..Stop capture), maintained by session-tracker
    from telemetry.session. Keyed like the pipeline: (source_session, device_id)."""

    __tablename__ = "sessions"
    __table_args__ = (UniqueConstraint("source_session", "device_id", name="uq_sessions_source_device"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    source_session: Mapped[str] = mapped_column(String(100), nullable=False)
    device_id: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    user_sub: Mapped[str | None] = mapped_column(String(200))
    mode: Mapped[str | None] = mapped_column(String(10))
    vehicle_id: Mapped[int | None] = mapped_column(Integer)
    track_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("tracks.id", ondelete="SET NULL"))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    end_reason: Mapped[str | None] = mapped_column(String(40))
    # what the phone says it built (session_end) vs what reached the pipeline
    frames_expected: Mapped[int | None] = mapped_column(Integer)
    frames_received: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    first_frame_us: Mapped[int | None] = mapped_column(BigInteger)
    last_frame_us: Mapped[int | None] = mapped_column(BigInteger)
    last_frame_received_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    start_event: Mapped[dict | None] = mapped_column(JSONB)
    end_event: Mapped[dict | None] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SessionFrame(Base):
    """One frame (second) of a session that reached the pipeline; the key makes
    redelivered receipts count once."""

    __tablename__ = "session_frames"

    session_pk: Mapped[int] = mapped_column(
        Integer, ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True
    )
    base_timestamp_us: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    gps: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    imu: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    imu_missing: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    can: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    received_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


class Vehicle(Base):
    """A vehicle a user records in. Created from the phone app; its CAN filter is
    what the dongle is told to forward when this vehicle is picked."""

    __tablename__ = "vehicles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    user_id: Mapped[int] = mapped_column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    year: Mapped[int | None] = mapped_column(Integer)
    make: Mapped[str | None] = mapped_column(String(100))
    model: Mapped[str | None] = mapped_column(String(100))
    # CAN ids the dongle forwards for this vehicle; empty = all
    can_filter: Mapped[list] = mapped_column(JSONB, nullable=False, default=list, server_default="[]")
    # the DBC can-decode uses for this vehicle's sessions; null = can-decode's default
    dbc_id: Mapped[int | None] = mapped_column(Integer, ForeignKey("dbcs.id", ondelete="SET NULL"))
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Dbc(Base):
    """A CAN database file. Vehicles point at one; can-decode fetches it by session."""

    __tablename__ = "dbcs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    name: Mapped[str] = mapped_column(String(200), unique=True, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # lets can-decode tell an edited DBC from the one it has compiled
    sha256: Mapped[str] = mapped_column(String(64), nullable=False)
    # signal roles (app.services.roles): {role: {signal, scale?, offset?, unit?} | {value}}
    roles: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict, server_default="{}")
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SessionSummary(Base):
    """A drive summary, computed by drive-summary from the session's vehicle_state rows
    (docs/plans/2026-11-03-trip-pipelines.md §4). One per session; recomputed when the
    session gains frames or the metrics version changes."""

    __tablename__ = "session_summaries"

    session_pk: Mapped[int] = mapped_column(
        Integer, ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    frames_used: Mapped[int] = mapped_column(Integer, nullable=False)
    # the session's frames_received when computed: a later change means late frames
    frames_received: Mapped[int] = mapped_column(Integer, nullable=False)
    metrics: Mapped[dict] = mapped_column(JSONB, nullable=False)
    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class SessionDiscovery(Base):
    """DBC discovery for a session whose vehicle has no DBC, written by can-discovery
    (docs/plans/2026-11-04-dbc-discovery.md §2): a per-CAN-id inventory and signal
    candidates correlated against GPS and IMU. For review only."""

    __tablename__ = "session_discoveries"

    session_pk: Mapped[int] = mapped_column(
        Integer, ForeignKey("sessions.id", ondelete="CASCADE"), primary_key=True
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False)
    can_frames: Mapped[int] = mapped_column(BigInteger, nullable=False)
    inventory: Mapped[dict] = mapped_column(JSONB, nullable=False)
    candidates: Mapped[list] = mapped_column(JSONB, nullable=False)
    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
