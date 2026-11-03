# Build from the robo-services repo root:
#   docker build -f services/drive_summary/Dockerfile -t dwilson2547/robo-services-drive-summary:<tag> .
FROM python:3.13-slim

WORKDIR /app

COPY services/drive_summary/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY services/drive_summary/app /app/app

ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "app.main"]
