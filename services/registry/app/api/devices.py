from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Device, DeviceProfile
from app.schemas.devices import (
    DeviceCreate,
    DeviceOut,
    DeviceProfileCreate,
    DeviceProfileOut,
    DeviceUpdate,
)

router = APIRouter(prefix="/devices", tags=["devices"])


@router.get("/", response_model=list[DeviceOut])
def list_devices(db: Session = Depends(get_db)):
    return db.query(Device).order_by(Device.device_id).all()


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


# --- Device Profiles ---


@router.get("/{device_id}/profiles", response_model=list[DeviceProfileOut])
def list_profiles(device_id: str, db: Session = Depends(get_db)):
    return (
        db.query(DeviceProfile)
        .filter(DeviceProfile.device_id == device_id)
        .order_by(DeviceProfile.version.desc())
        .all()
    )


@router.get("/{device_id}/profile", response_model=DeviceProfileOut)
def get_active_profile(device_id: str, db: Session = Depends(get_db)):
    """Return the active profile for a device. Used by Flink jobs."""
    profile = (
        db.query(DeviceProfile)
        .filter(DeviceProfile.device_id == device_id, DeviceProfile.active.is_(True))
        .first()
    )
    if not profile:
        raise HTTPException(status_code=404, detail="No active profile for device")
    return profile


@router.post("/{device_id}/profiles", response_model=DeviceProfileOut, status_code=201)
def create_profile(device_id: str, body: DeviceProfileCreate, db: Session = Depends(get_db)):
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    last = (
        db.query(DeviceProfile)
        .filter(DeviceProfile.device_id == device_id)
        .order_by(DeviceProfile.version.desc())
        .first()
    )
    next_version = (last.version + 1) if last else 1

    # Deactivate existing active profile
    db.query(DeviceProfile).filter(
        DeviceProfile.device_id == device_id, DeviceProfile.active.is_(True)
    ).update({"active": False})

    profile = DeviceProfile(
        device_id=device_id,
        version=next_version,
        profile_json=body.profile_json,
        notes=body.notes,
        active=True,
    )
    db.add(profile)
    db.commit()
    db.refresh(profile)
    return profile


@router.post("/{device_id}/profiles/{profile_id}/activate", response_model=DeviceProfileOut)
def activate_profile(device_id: str, profile_id: int, db: Session = Depends(get_db)):
    profile = (
        db.query(DeviceProfile)
        .filter(DeviceProfile.id == profile_id, DeviceProfile.device_id == device_id)
        .first()
    )
    if not profile:
        raise HTTPException(status_code=404, detail="Profile not found")

    db.query(DeviceProfile).filter(
        DeviceProfile.device_id == device_id, DeviceProfile.active.is_(True)
    ).update({"active": False})

    profile.active = True
    db.commit()
    db.refresh(profile)
    return profile
