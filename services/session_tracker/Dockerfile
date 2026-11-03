# Build from the robo-services repo root:
#   docker build -f services/session_tracker/Dockerfile -t dwilson2547/robo-services-session-tracker:<tag> .
FROM python:3.13-slim

WORKDIR /app

COPY services/session_tracker/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# the shared pipeline package (Iggy message envelope)
COPY pyproject.toml /app/pyproject.toml
COPY src /app/src
RUN pip install --no-cache-dir /app

COPY services/session_tracker/app /app/app

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "app.main"]
