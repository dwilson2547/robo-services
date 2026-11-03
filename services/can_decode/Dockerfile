FROM python:3.13-slim

WORKDIR /app

COPY services/can_decode/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt

COPY src /app/src
COPY services/can_decode/app /app/app

ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=/app

ENTRYPOINT ["python", "-m", "app.main"]
