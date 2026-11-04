"""OpenTelemetry for the pipeline services: lag metrics (docs/plans/2026-11-03-processing-lag.md).

setup() exports over OTLP/HTTP to the endpoint in the standard OTEL_* environment
(OTEL_EXPORTER_OTLP_ENDPOINT, OTEL_SERVICE_NAME). Without an endpoint it does
nothing, and the instruments below record into the API's no-op provider, so
services, tests and local runs call them the same way.

Names follow OTel conventions; the collector's Prometheus exporter turns them into
robo_upload_lag_seconds, robo_pipeline_lag_seconds, robo_consumer_backlog and
robo_frames_total.
"""

from __future__ import annotations

import logging
import os
from datetime import datetime, timezone

from opentelemetry import metrics

LOG = logging.getLogger("observability")

# seconds: sub-second processing up to hours of offline catch-up
LAG_BUCKETS_S = (0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300, 900, 3600, 4 * 3600, 24 * 3600)
EXPORT_INTERVAL_MS = 15_000

_meter = metrics.get_meter("robo-services")
upload_lag = _meter.create_histogram(
    "robo.upload.lag", unit="s",
    description="kreceiver receive time minus the end of the frame on the phone (phone and network)",
)
pipeline_lag = _meter.create_histogram(
    "robo.pipeline.lag", unit="s",
    description="when a stage finished with a record or frame minus kreceiver's receive time",
)
frames = _meter.create_counter("robo.frames", description="frames closed by vehicle-state, by status")
_backlog: dict[tuple[str, str], int] = {}


def _observe_backlog(_options):
    return [metrics.Observation(v, {"topic": topic, "reader": reader}) for (reader, topic), v in list(_backlog.items())]


_meter.create_observable_gauge(
    "robo.consumer.backlog", callbacks=[_observe_backlog], unit="{message}",
    description="messages in a topic not yet read by the named reader",
)


def set_backlog(reader: str, topic: str, messages_count: int, consumed_offset: int | None) -> None:
    """Record a reader's backlog from the topic size and the last offset it consumed.

    With no offset yet (nothing consumed since the process started) the position is
    unknown, not zero, so nothing is recorded: an idle topic would otherwise read as
    entirely unread.
    """
    if consumed_offset is None:
        return
    _backlog[(reader, topic)] = max(0, messages_count - (consumed_offset + 1))


def seconds_since(iso: str | None, now: datetime | None = None) -> float | None:
    """Seconds from an ISO 8601 time to now (UTC), or None when it's missing or unreadable."""
    if not iso:
        return None
    try:
        t = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    except ValueError:
        return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=timezone.utc)
    return ((now or datetime.now(timezone.utc)) - t).total_seconds()


def setup(service_name: str | None = None) -> bool:
    """Start exporting metrics if OTEL_EXPORTER_OTLP_ENDPOINT is set; returns whether it did."""
    if not os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"):
        LOG.info("OTEL_EXPORTER_OTLP_ENDPOINT unset; metrics are not exported")
        return False
    from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
    from opentelemetry.sdk.metrics import MeterProvider
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
    from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View
    from opentelemetry.sdk.resources import Resource

    attributes = {"service.name": service_name} if service_name and not os.getenv("OTEL_SERVICE_NAME") else {}
    provider = MeterProvider(
        resource=Resource.create(attributes),
        metric_readers=[PeriodicExportingMetricReader(OTLPMetricExporter(), export_interval_millis=EXPORT_INTERVAL_MS)],
        views=[View(instrument_name="robo.*.lag", aggregation=ExplicitBucketHistogramAggregation(LAG_BUCKETS_S))],
    )
    metrics.set_meter_provider(provider)
    LOG.info("exporting metrics to %s every %ds", os.environ["OTEL_EXPORTER_OTLP_ENDPOINT"], EXPORT_INTERVAL_MS // 1000)
    return True
