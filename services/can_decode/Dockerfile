# Build from the robo-services repo root:
#   docker build -f services/can_decode/Dockerfile -t dwilson2547/robo-services-can-decode:<tag> .
FROM python:3.13-slim

WORKDIR /app

COPY services/can_decode/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

# the shared pipeline package (IggyPubSubBackend)
COPY pyproject.toml /app/pyproject.toml
COPY src /app/src
RUN pip install --no-cache-dir /app

COPY services/can_decode/app /app/app

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "app.main"]
