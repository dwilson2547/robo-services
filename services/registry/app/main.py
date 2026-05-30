from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.api import devices, tracks, users
from app.config import settings

app = FastAPI(title="robo-registry", version="0.1.0")

app.include_router(users.router, prefix=settings.api_prefix)
app.include_router(devices.router, prefix=settings.api_prefix)
app.include_router(tracks.router, prefix=settings.api_prefix)


@app.get("/health")
def health():
    return {"status": "ok"}


# Serve React SPA — must be last so API routes take precedence
_static = Path(__file__).parent / "static"
if _static.exists():
    app.mount("/", StaticFiles(directory=str(_static), html=True), name="spa")
