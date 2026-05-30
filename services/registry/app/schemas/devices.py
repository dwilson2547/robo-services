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
