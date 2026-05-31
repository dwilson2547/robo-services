from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db import get_db
from app.models import Pipeline
from app.schemas.pipelines import PipelineCreate, PipelineOut, PipelineUpdate

router = APIRouter(prefix="/pipelines", tags=["pipelines"])


@router.get("/", response_model=list[PipelineOut])
def list_pipelines(db: Session = Depends(get_db)):
    return db.query(Pipeline).order_by(Pipeline.name).all()


@router.post("/", response_model=PipelineOut, status_code=201)
def create_pipeline(body: PipelineCreate, db: Session = Depends(get_db)):
    if db.query(Pipeline).filter(Pipeline.name == body.name).first():
        raise HTTPException(status_code=409, detail="pipeline name already exists")
    pipeline = Pipeline(**body.model_dump())
    db.add(pipeline)
    db.commit()
    db.refresh(pipeline)
    return pipeline


@router.put("/{name}", response_model=PipelineOut)
def update_pipeline(name: str, body: PipelineUpdate, db: Session = Depends(get_db)):
    pipeline = db.query(Pipeline).filter(Pipeline.name == name).first()
    if not pipeline:
        raise HTTPException(status_code=404, detail="Pipeline not found")
    for field, value in body.model_dump(exclude_none=True).items():
        setattr(pipeline, field, value)
    db.commit()
    db.refresh(pipeline)
    return pipeline


@router.delete("/{name}", status_code=204)
def delete_pipeline(name: str, db: Session = Depends(get_db)):
    pipeline = db.query(Pipeline).filter(Pipeline.name == name).first()
    if not pipeline:
        raise HTTPException(status_code=404, detail="Pipeline not found")
    db.delete(pipeline)
    db.commit()
