from datetime import datetime

from pydantic import BaseModel, Field, field_validator

MAX_FILTER_IDS = 64  # what the dongle's FILTER: command takes
MAX_CAN_ID = 0x1FFFFFFF


class MeOut(BaseModel):
    id: int
    name: str
    email: str
    sub: str

    model_config = {"from_attributes": True}


class VehicleIn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    year: int | None = Field(default=None, ge=1900, le=2100)
    make: str | None = Field(default=None, max_length=100)
    model: str | None = Field(default=None, max_length=100)
    can_filter: list[int] = Field(default_factory=list)
    # registry DBC can-decode uses for this vehicle (GET /api/app/dbcs); null = default
    dbc_id: int | None = None
    notes: str | None = None

    @field_validator("can_filter")
    @classmethod
    def _filter(cls, ids: list[int]) -> list[int]:
        if len(ids) > MAX_FILTER_IDS:
            raise ValueError(f"at most {MAX_FILTER_IDS} CAN ids")
        if any(i < 0 or i > MAX_CAN_ID for i in ids):
            raise ValueError("CAN ids must be 0..0x1FFFFFFF")
        return sorted(set(ids))


class DbcChoice(BaseModel):
    id: int
    name: str
    notes: str | None


class VehicleOut(VehicleIn):
    id: int
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class AppTrackOut(BaseModel):
    """A track as the phone lists it."""

    id: int
    name: str
    source: str
    center: list[float] | None  # [lat, lon]
    distance_km: float | None  # from the point asked about, if any
    has_start_line: bool
    added_by_me: bool
    geometry: dict | None
    start_line: dict | None  # GeoJSON Point, if set


class AppDiscoverIn(BaseModel):
    """Search near a point, or near a US ZIP code (geocoded by the registry)."""

    lat: float | None = None
    lon: float | None = None
    zip: str | None = None
    radius_m: int = 40_234  # 25 mi


class AppCandidateOut(BaseModel):
    index: int
    name: str
    geometry_type: str
    center: list[float] | None
    distance_km: float | None
    already_imported: bool
    geometry: dict


class AppDiscoverOut(BaseModel):
    token: str
    origin: list[float]  # [lat, lon] searched around
    origin_label: str
    candidates: list[AppCandidateOut]


class AppIngestIn(BaseModel):
    token: str
    selected_indices: list[int]


class AppIngestOut(BaseModel):
    ingested: list[AppTrackOut]
    skipped: list[dict]
