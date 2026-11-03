from datetime import datetime

from pydantic import BaseModel, Field

MAX_DBC_BYTES = 2 * 1024 * 1024


class DbcIn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=MAX_DBC_BYTES)
    notes: str | None = None
    # signal roles; omitted on PUT keeps the current ones (checked against the new content)
    roles: dict[str, dict] | None = None


class DbcMeta(BaseModel):
    id: int
    name: str
    sha256: str
    size: int
    notes: str | None
    roles: dict[str, dict]
    updated_at: datetime


class DbcOut(DbcMeta):
    content: str


class SessionDbcOut(BaseModel):
    """What can-decode needs to decode one session."""

    vehicle_id: int
    dbc_id: int
    name: str
    sha256: str
    content: str
    roles: dict[str, dict]
