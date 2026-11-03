import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Device, User
from app.schemas.devices import (
    DeviceCreate,
    DeviceHeartbeatRequest,
    DeviceHeartbeatResponse,
    DeviceOut,
    DeviceUpdate,
)

LOG = logging.getLogger(__name__)

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("/", response_model=list[DeviceOut])
def list_devices(db: Session = Depends(get_db)):
    return db.query(Device).order_by(Device.device_id).all()


@router.post("/heartbeat", response_model=DeviceHeartbeatResponse)
def device_heartbeat(body: DeviceHeartbeatRequest, db: Session = Depends(get_db)):
    """Called by kreceiver when it sees a device publish.

    Creates the device if it is new and links it to the account it published as
    (`user_sub`, which kreceiver takes from the per-user MQTT topic). An owned
    device is never moved to another account: that is reported, not applied.
    """
    now = datetime.now(tz=timezone.utc)
    device = db.query(Device).filter(Device.device_id == body.device_id).first()
    created = device is None
    if created:
        device = Device(device_id=body.device_id, display_name=body.device_id, source="auto_detected")
        db.add(device)
    device.last_seen_at = now

    conflict = False
    if body.user_sub:
        user = db.query(User).filter(User.sub == body.user_sub).first()
        if user is None:
            LOG.info("device %s published as an account the registry has not seen yet", body.device_id)
        elif device.user_id is None:
            device.owner = user
        elif device.user_id != user.id:
            conflict = True
            LOG.warning(
                "device %s is owned by user %s but published as user %s; owner left unchanged",
                body.device_id, device.user_id, user.id,
            )

    db.commit()
    db.refresh(device)
    return DeviceHeartbeatResponse(
        device_id=device.device_id, created=created, owner_email=device.owner_email, owner_conflict=conflict
    )


@router.get("/{device_id}", response_model=DeviceOut)
def get_device(device_id: str, db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    return device


@router.post("/", response_model=DeviceOut, status_code=201)
def create_device(body: DeviceCreate, db: Session = Depends(get_db)):
    if db.query(Device).filter(Device.device_id == body.device_id).first():
        raise HTTPException(status_code=409, detail="device_id already registered")
    device = Device(**body.model_dump())
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@router.put("/{device_id}", response_model=DeviceOut)
def update_device(device_id: str, body: DeviceUpdate, db: Session = Depends(get_db)):
    """Edit a device; `user_id` here is the manual owner override."""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    for field, value in body.model_dump(exclude_none=True).items():
        setattr(device, field, value)
    db.commit()
    db.refresh(device)
    return device


@router.delete("/{device_id}", status_code=204)
def delete_device(device_id: str, db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    db.delete(device)
    db.commit()
