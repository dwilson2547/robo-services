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
    owner_email: str | None
    created_at: datetime
    last_seen_at: datetime | None
    source: str

    model_config = {"from_attributes": True}


class DeviceHeartbeatRequest(BaseModel):
    device_id: str
    # the account the device published as (kreceiver's stamp from the MQTT topic)
    user_sub: str | None = None


class DeviceHeartbeatResponse(BaseModel):
    device_id: str
    created: bool  # True if this was a new auto-detected device
    owner_email: str | None
    owner_conflict: bool  # owned by another account than the one it published as
