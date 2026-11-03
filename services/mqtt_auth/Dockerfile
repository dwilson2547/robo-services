FROM python:3.13-slim

# Build from the robo-services repo root:
#   docker build -f services/mqtt_auth/Dockerfile -t dwilson2547/robo-services-mqtt-auth:latest .
WORKDIR /app

COPY services/mqtt_auth/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY services/mqtt_auth/app /app/app

ENV PYTHONUNBUFFERED=1

EXPOSE 8080

ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
