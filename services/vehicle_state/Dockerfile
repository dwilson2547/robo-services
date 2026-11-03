# Build from the robo-services repo root:
#   docker build -f services/vehicle_state/Dockerfile -t dwilson2547/robo-services-vehicle-state:<tag> .
FROM python:3.13-slim

WORKDIR /app

COPY services/vehicle_state/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# the shared pipeline package (Iggy message envelope, consumer watchdog)
COPY pyproject.toml /app/pyproject.toml
COPY src /app/src
RUN pip install --no-cache-dir /app

COPY services/vehicle_state/app /app/app

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "app.main"]
