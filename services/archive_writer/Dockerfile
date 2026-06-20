FROM python:3.13-slim

WORKDIR /app

COPY pyproject.toml /app/pyproject.toml
COPY src /app/src

RUN pip install --no-cache-dir /app

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "can_pub_sub_probe.archive_writer"]
