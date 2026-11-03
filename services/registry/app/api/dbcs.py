"""DBC files (LAN API, like the rest of /api outside /api/app)."""

import hashlib

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Dbc
from app.schemas.dbcs import DbcIn, DbcMeta, DbcOut
from app.services.roles import validate_roles

router = APIRouter(prefix="/dbcs", tags=["dbcs"])


def meta(d: Dbc) -> dict:
    return {"id": d.id, "name": d.name, "sha256": d.sha256, "size": len(d.content.encode()),
            "notes": d.notes, "roles": d.roles or {}, "updated_at": d.updated_at}


def sha(content: str) -> str:
    return hashlib.sha256(content.encode()).hexdigest()


def checked_roles(roles: dict, content: str) -> dict:
    problems = validate_roles(roles, content)
    if problems:
        raise HTTPException(status_code=422, detail={"roles": problems})
    return roles


@router.get("/", response_model=list[DbcMeta])
def list_dbcs(db: Session = Depends(get_db)):
    return [meta(d) for d in db.query(Dbc).order_by(Dbc.name).all()]


@router.get("/{dbc_id}", response_model=DbcOut)
def get_dbc(dbc_id: int, db: Session = Depends(get_db)):
    d = db.get(Dbc, dbc_id)
    if d is None:
        raise HTTPException(status_code=404, detail="dbc not found")
    return {**meta(d), "content": d.content}


@router.post("/", response_model=DbcMeta, status_code=201)
def create_dbc(body: DbcIn, db: Session = Depends(get_db)):
    if db.query(Dbc).filter(Dbc.name == body.name).first():
        raise HTTPException(status_code=409, detail="a DBC with that name exists; PUT to replace it")
    roles = checked_roles(body.roles or {}, body.content)
    d = Dbc(name=body.name, content=body.content, sha256=sha(body.content), notes=body.notes, roles=roles)
    db.add(d)
    db.commit()
    db.refresh(d)
    return meta(d)


@router.put("/{dbc_id}", response_model=DbcMeta)
def replace_dbc(dbc_id: int, body: DbcIn, db: Session = Depends(get_db)):
    d = db.get(Dbc, dbc_id)
    if d is None:
        raise HTTPException(status_code=404, detail="dbc not found")
    roles = checked_roles((d.roles or {}) if body.roles is None else body.roles, body.content)
    d.name, d.content, d.sha256, d.notes = body.name, body.content, sha(body.content), body.notes
    d.roles = roles
    d.updated_at = func.now()
    db.commit()
    db.refresh(d)
    return meta(d)
