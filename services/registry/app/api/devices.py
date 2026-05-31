from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Device, DeviceProfile, DeviceModeConfig, DevicePipelineAssignment, Pipeline
from app.schemas.devices import (
    DeviceClaimRequest,
    DeviceCreate,
    DeviceModeConfigOut,
    DeviceHeartbeatRequest,
    DeviceHeartbeatResponse,
    DeviceOut,
    DeviceProfileCreate,
    DeviceProfileOut,
    DeviceUpdate,
    PipelineAssignmentOut,
    SetPipelineAssignmentRequest,
)

router = APIRouter(prefix="/devices", tags=["devices"])


def _strip_mode_suffix(raw_device_id: str) -> tuple[str, str | None]:
    """Strip -r (race) or -t (trip) suffix from a device_id."""
    if raw_device_id.endswith("-r"):
        return raw_device_id[:-2], "race"
    if raw_device_id.endswith("-t"):
        return raw_device_id[:-2], "trip"
    return raw_device_id, None


def _build_mode_config_out(
    device_id: str,
    mode: str,
    config: DeviceModeConfig | None,
    all_pipelines: list[Pipeline],
) -> DeviceModeConfigOut:
    """Build a DeviceModeConfigOut merging all pipelines with their assignment state."""
    assignment_map: dict[int, DevicePipelineAssignment] = {}
    if config:
        assignment_map = {a.pipeline_id: a for a in config.assignments}

    assignments = []
    for p in all_pipelines:
        a = assignment_map.get(p.id)
        if a:
            assignments.append(PipelineAssignmentOut(
                id=a.id,
                pipeline_id=p.id,
                pipeline_name=p.name,
                enabled=a.enabled,
                config_overrides=a.config_overrides,
            ))
        else:
            assignments.append(PipelineAssignmentOut(
                id=None,
                pipeline_id=p.id,
                pipeline_name=p.name,
                enabled=False,
                config_overrides=None,
            ))

    return DeviceModeConfigOut(
        id=config.id if config else None,
        device_id=device_id,
        mode=mode,
        assignments=assignments,
    )


@router.get("/", response_model=list[DeviceOut])
def list_devices(db: Session = Depends(get_db)):
    return db.query(Device).order_by(Device.device_id).all()


@router.post("/heartbeat", response_model=DeviceHeartbeatResponse)
def device_heartbeat(body: DeviceHeartbeatRequest, db: Session = Depends(get_db)):
    """Called by kreceiver when it sees a device publish. Upserts auto-detected devices."""
    canonical_id, mode = _strip_mode_suffix(body.device_id)
    now = datetime.now(tz=timezone.utc)

    device = db.query(Device).filter(Device.device_id == canonical_id).first()
    created = False
    if device is None:
        device = Device(
            device_id=canonical_id,
            display_name=canonical_id,
            source="auto_detected",
            last_seen_at=now,
            last_mode=mode,
        )
        db.add(device)
        created = True
    else:
        device.last_seen_at = now
        if mode:
            device.last_mode = mode

    db.commit()
    return DeviceHeartbeatResponse(device_id=canonical_id, mode=mode, created=created)


@router.post("/{device_id}/claim", response_model=DeviceOut)
def claim_device(device_id: str, body: DeviceClaimRequest, db: Session = Depends(get_db)):
    """Assign an owner to an auto-detected device."""
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    device.user_id = body.user_id
    if body.display_name:
        device.display_name = body.display_name
    db.commit()
    db.refresh(device)
    return device


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


# --- Device Mode Pipeline Config ---


@router.get("/{device_id}/mode/{mode}", response_model=DeviceModeConfigOut)
def get_mode_config(device_id: str, mode: str, db: Session = Depends(get_db)):
    """Return the pipeline config for a device+mode, including all pipelines with their enabled state."""
    if mode not in ("race", "trip"):
        raise HTTPException(status_code=400, detail="mode must be 'race' or 'trip'")
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    config = (
        db.query(DeviceModeConfig)
        .filter(DeviceModeConfig.device_id == device_id, DeviceModeConfig.mode == mode)
        .first()
    )
    all_pipelines = db.query(Pipeline).order_by(Pipeline.name).all()
    return _build_mode_config_out(device_id, mode, config, all_pipelines)


@router.get("/{device_id}/mode/{mode}/pipelines", response_model=list[str])
def get_enabled_pipelines(device_id: str, mode: str, db: Session = Depends(get_db)):
    """Return names of enabled pipelines for a device+mode. Used by Flink jobs."""
    if mode not in ("race", "trip"):
        raise HTTPException(status_code=400, detail="mode must be 'race' or 'trip'")
    config = (
        db.query(DeviceModeConfig)
        .filter(DeviceModeConfig.device_id == device_id, DeviceModeConfig.mode == mode)
        .first()
    )
    if not config:
        return []
    return [a.pipeline.name for a in config.assignments if a.enabled]


@router.put("/{device_id}/mode/{mode}/pipelines/{pipeline_name}", response_model=DeviceModeConfigOut)
def set_pipeline_assignment(
    device_id: str,
    mode: str,
    pipeline_name: str,
    body: SetPipelineAssignmentRequest,
    db: Session = Depends(get_db),
):
    """Enable/disable a pipeline for a device+mode. Creates mode config if needed."""
    if mode not in ("race", "trip"):
        raise HTTPException(status_code=400, detail="mode must be 'race' or 'trip'")
    device = db.query(Device).filter(Device.device_id == device_id).first()
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")
    pipeline = db.query(Pipeline).filter(Pipeline.name == pipeline_name).first()
    if not pipeline:
        raise HTTPException(status_code=404, detail="Pipeline not found")

    config = (
        db.query(DeviceModeConfig)
        .filter(DeviceModeConfig.device_id == device_id, DeviceModeConfig.mode == mode)
        .first()
    )
    if not config:
        config = DeviceModeConfig(device_id=device_id, mode=mode)
        db.add(config)
        db.flush()

    assignment = (
        db.query(DevicePipelineAssignment)
        .filter(
            DevicePipelineAssignment.device_mode_config_id == config.id,
            DevicePipelineAssignment.pipeline_id == pipeline.id,
        )
        .first()
    )
    if assignment:
        assignment.enabled = body.enabled
        assignment.config_overrides = body.config_overrides
    else:
        assignment = DevicePipelineAssignment(
            device_mode_config_id=config.id,
            pipeline_id=pipeline.id,
            enabled=body.enabled,
            config_overrides=body.config_overrides,
        )
        db.add(assignment)

    db.commit()
    db.refresh(config)
    all_pipelines = db.query(Pipeline).order_by(Pipeline.name).all()
    return _build_mode_config_out(device_id, mode, config, all_pipelines)

