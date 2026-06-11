FROM python:3.13-slim

WORKDIR /app

COPY pyproject.toml /app/pyproject.toml
COPY src /app/src
RUN pip install --no-cache-dir /app

COPY capture_device_configs/gps-test-feed/kreceiver_proto /opt/kreceiver_proto

ENV PYTHONPATH=/opt
ENV PYTHONUNBUFFERED=1

ENTRYPOINT ["python", "-m", "kreceiver_proto.receiver_app"]
CMD ["--help"]
