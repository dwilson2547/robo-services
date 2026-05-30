FROM node:20-alpine AS ui-builder
WORKDIR /ui
COPY ui/package*.json ./
RUN npm ci
COPY ui/ ./
RUN npm run build

FROM python:3.12-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends libpq5 && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
RUN pip install --no-cache-dir \
    "fastapi>=0.115" \
    "uvicorn[standard]>=0.30" \
    "sqlalchemy>=2.0" \
    "alembic>=1.13" \
    "psycopg2-binary>=2.9" \
    "pydantic>=2.7" \
    "pydantic-settings>=2.3" \
    "python-multipart>=0.0.9", \
    "email-validator>=2.1"

COPY app/ ./app/
COPY migrations/ ./migrations/
COPY alembic.ini ./

# Embed the built SPA
COPY --from=ui-builder /ui/dist ./app/static/

EXPOSE 8000
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000"]
