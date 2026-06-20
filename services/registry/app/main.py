from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select

from app.api import analytics, devices, pipelines, tracks, users
from app.config import settings
from app.db import SessionLocal
from app.models import Pipeline

app = FastAPI(title="robo-registry", version="0.1.0")

_KNOWN_PIPELINES = [
    ("speed", "Speed aggregation pipeline"),
    ("lap", "Lap tracking pipeline"),
    ("track-position", "GPS to track-relative s-coordinate derivation"),
]

app.include_router(users.router, prefix=settings.api_prefix)
app.include_router(devices.router, prefix=settings.api_prefix)
app.include_router(tracks.router, prefix=settings.api_prefix)
app.include_router(pipelines.router, prefix=settings.api_prefix)
app.include_router(analytics.router, prefix=settings.api_prefix)


@app.on_event("startup")
def ensure_known_pipelines() -> None:
    with SessionLocal() as db:
        existing = {
            name
            for name in db.execute(select(Pipeline.name)).scalars().all()
        }
        created = False
        for name, description in _KNOWN_PIPELINES:
            if name in existing:
                continue
            db.add(Pipeline(name=name, description=description))
            created = True
        if created:
            db.commit()


@app.get("/health")
def health():
    return {"status": "ok"}


# Serve React SPA — must be last so API routes take precedence
_static = Path(__file__).parent / "static"
if _static.exists():
    app.mount("/", StaticFiles(directory=str(_static), html=True), name="spa")
