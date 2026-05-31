from datetime import datetime

from pydantic import BaseModel


class DeviceCreate(BaseModel):
    device_id: str
    display_name: str
    hardware_spec: str | None = None
    notes: str | None = None
    user_id: int | None = None


class DeviceUpdate(BaseModel):
    display_name: str | None = None
    hardware_spec: str | None = None
    notes: str | None = None
    user_id: int | None = None


class DeviceOut(BaseModel):
    id: int
    device_id: str
    display_name: str
    hardware_spec: str | None
    notes: str | None
    user_id: int | None
    created_at: datetime
    last_seen_at: datetime | None
    last_mode: str | None
    source: str

    model_config = {"from_attributes": True}


class DeviceProfileCreate(BaseModel):
    profile_json: dict
    notes: str | None = None


class DeviceProfileOut(BaseModel):
    id: int
    device_id: str
    version: int
    profile_json: dict
    active: bool
    notes: str | None
    created_at: datetime

    model_config = {"from_attributes": True}


class DeviceHeartbeatRequest(BaseModel):
    device_id: str  # may include -r/-t mode suffix


class DeviceHeartbeatResponse(BaseModel):
    device_id: str  # canonical (suffix stripped)
    mode: str | None
    created: bool  # True if this was a new auto-detected device


class DeviceClaimRequest(BaseModel):
    user_id: int
    display_name: str | None = None


class PipelineAssignmentOut(BaseModel):
    id: int | None
    pipeline_id: int
    pipeline_name: str
    enabled: bool
    config_overrides: dict | None

    model_config = {"from_attributes": True}


class DeviceModeConfigOut(BaseModel):
    id: int | None
    device_id: str
    mode: str
    assignments: list[PipelineAssignmentOut]

    model_config = {"from_attributes": True}


class SetPipelineAssignmentRequest(BaseModel):
    enabled: bool = True
    config_overrides: dict | None = None

