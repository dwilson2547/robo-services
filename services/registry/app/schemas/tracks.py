from datetime import datetime

from pydantic import BaseModel


class TrackCreate(BaseModel):
    name: str
    country: str | None = None
    surface_type: str | None = None
    source: str = "user_built"
    geometry: dict | None = None
    start_line: dict | None = None
    osm_relation_id: int | None = None
    notes: str | None = None


class TrackUpdate(BaseModel):
    name: str | None = None
    country: str | None = None
    surface_type: str | None = None
    source: str | None = None
    geometry: dict | None = None
    start_line: dict | None = None
    osm_relation_id: int | None = None
    notes: str | None = None


class TrackOut(BaseModel):
    id: int
    name: str
    country: str | None
    surface_type: str | None
    source: str
    geometry: dict | None
    start_line: dict | None
    osm_relation_id: int | None
    notes: str | None
    created_at: datetime

    model_config = {"from_attributes": True}
