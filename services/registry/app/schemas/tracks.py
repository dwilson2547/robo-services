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
    osm_way_ids: list[int] | None = None
    notes: str | None = None


class TrackUpdate(BaseModel):
    name: str | None = None
    country: str | None = None
    surface_type: str | None = None
    source: str | None = None
    geometry: dict | None = None
    start_line: dict | None = None
    osm_relation_id: int | None = None
    osm_way_ids: list[int] | None = None
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
    osm_way_ids: list[int] | None
    notes: str | None
    created_at: datetime

    model_config = {"from_attributes": True}


# ── OSM discovery / ingest ────────────────────────────────────────────────────

class OsmDiscoverRequest(BaseModel):
    lat: float
    lon: float
    radius_m: int = 5000


class OsmCandidate(BaseModel):
    name: str
    osm_relation_id: int | None
    osm_way_ids: list[int] | None
    geometry: dict           # GeoJSON Feature — for display only, not trusted at ingest
    geometry_type: str       # "polygon" | "linestring"
    already_imported: bool


class OsmDiscoverResponse(BaseModel):
    token: str
    candidates: list[OsmCandidate]


class OsmIngestRequest(BaseModel):
    token: str
    selected_indices: list[int]


class OsmIngestResult(BaseModel):
    ingested: list[TrackOut]
    skipped: list[dict]      # {name, reason}
