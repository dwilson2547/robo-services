# Build from the robo-services repo root:
#   docker build -f services/rerun/Dockerfile -t dwilson2547/robo-services-rerun:<tag> .
FROM python:3.13-slim

WORKDIR /app

COPY services/rerun/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# the live services' code, laid out as in the repo (app/reuse.py loads it from there)
COPY services/can_decode/app /app/services/can_decode/app
COPY services/vehicle_state/app /app/services/vehicle_state/app
COPY services/drive_summary/app /app/services/drive_summary/app
COPY services/rerun/app /app/services/rerun/app

WORKDIR /app/services/rerun
ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "app.main"]
