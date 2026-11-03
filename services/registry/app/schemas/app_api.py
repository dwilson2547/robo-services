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
