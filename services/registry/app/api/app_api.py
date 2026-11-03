"""The phone app's API: everything here needs the user's authentik token
(app/auth.py) and only ever touches that user's own rows. This prefix is the only
part of the registry exposed publicly."""

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import current_user
from app.db import get_db
from app.models import Dbc, User, Vehicle
from app.schemas.app_api import DbcChoice, MeOut, VehicleIn, VehicleOut

router = APIRouter(prefix="/app", tags=["app"])


@router.get("/me", response_model=MeOut)
def me(user: User = Depends(current_user)):
    return user


@router.get("/dbcs", response_model=list[DbcChoice])
def list_dbcs(user: User = Depends(current_user), db: Session = Depends(get_db)):
    """DBCs a vehicle can use (names only; the content stays in the cluster)."""
    return db.query(Dbc).order_by(Dbc.name).all()


def _check_dbc(db: Session, body: VehicleIn) -> None:
    if body.dbc_id is not None and db.get(Dbc, body.dbc_id) is None:
        raise HTTPException(status_code=422, detail="unknown dbc_id")


@router.get("/vehicles", response_model=list[VehicleOut])
def list_vehicles(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return db.query(Vehicle).filter(Vehicle.user_id == user.id).order_by(Vehicle.name).all()


@router.post("/vehicles", response_model=VehicleOut, status_code=201)
def create_vehicle(body: VehicleIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    _check_dbc(db, body)
    vehicle = Vehicle(user_id=user.id, **body.model_dump())
    db.add(vehicle)
    db.commit()
    db.refresh(vehicle)
    return vehicle


def _own(db: Session, user: User, vehicle_id: int) -> Vehicle:
    vehicle = db.query(Vehicle).filter(Vehicle.id == vehicle_id, Vehicle.user_id == user.id).first()
    if vehicle is None:  # someone else's is "not found" too, so ids reveal nothing
        raise HTTPException(status_code=404, detail="vehicle not found")
    return vehicle


@router.put("/vehicles/{vehicle_id}", response_model=VehicleOut)
def update_vehicle(vehicle_id: int, body: VehicleIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    vehicle = _own(db, user, vehicle_id)
    _check_dbc(db, body)
    for field, value in body.model_dump().items():
        setattr(vehicle, field, value)
    vehicle.updated_at = func.now()
    db.commit()
    db.refresh(vehicle)
    return vehicle


@router.delete("/vehicles/{vehicle_id}", status_code=204)
def delete_vehicle(vehicle_id: int, user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.delete(_own(db, user, vehicle_id))
    db.commit()
    return Response(status_code=204)
