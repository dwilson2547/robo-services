from datetime import datetime

from pydantic import BaseModel


class PipelineCreate(BaseModel):
    name: str
    description: str | None = None
    default_config: dict | None = None


class PipelineUpdate(BaseModel):
    description: str | None = None
    default_config: dict | None = None


class PipelineOut(BaseModel):
    id: int
    name: str
    description: str | None
    default_config: dict | None
    created_at: datetime

    model_config = {"from_attributes": True}
