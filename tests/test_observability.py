from datetime import datetime, timezone

from opentelemetry import metrics
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View

from can_pub_sub_probe import observability as obs

NOW = datetime(2026, 11, 3, 22, 50, 10, tzinfo=timezone.utc)


def test_seconds_since_reads_both_iso_forms():
    assert obs.seconds_since("2026-11-03T22:50:00.103Z", NOW) == 9.897
    assert obs.seconds_since("2026-11-03T22:50:00+00:00", NOW) == 10.0
    assert obs.seconds_since("2026-11-03T22:50:00", NOW) == 10.0  # naive = UTC
    assert obs.seconds_since(None, NOW) is None
    assert obs.seconds_since("not a time", NOW) is None


def test_instruments_created_before_the_provider_still_export():
    # the module's instruments exist from import; a provider set later must receive them
    reader = InMemoryMetricReader()
    metrics.set_meter_provider(MeterProvider(
        metric_readers=[reader],
        views=[View(instrument_name="robo.*.lag", aggregation=ExplicitBucketHistogramAggregation(obs.LAG_BUCKETS_S))],
    ))
    obs.upload_lag.record(3.2)
    obs.pipeline_lag.record(0.4, {"stage": "can-decode"})
    obs.frames.add(1, {"status": "complete"})
    obs.set_backlog("vehicle-state", "telemetry.raw.gps", 100, 89)
    obs.set_backlog("can-decode", "telemetry.raw.can", 5, None)  # position unknown: not recorded
    obs.set_backlog("session-tracker", "telemetry.session", 5, 4)

    data = {m.name: m for rm in reader.get_metrics_data().resource_metrics
            for sm in rm.scope_metrics for m in sm.metrics}
    lag = data["robo.pipeline.lag"].data.data_points[0]
    assert dict(lag.attributes) == {"stage": "can-decode"} and lag.count == 1
    assert tuple(lag.explicit_bounds) == obs.LAG_BUCKETS_S
    assert data["robo.upload.lag"].data.data_points[0].sum == 3.2
    assert data["robo.frames"].data.data_points[0].value == 1
    backlog = {p.attributes["reader"]: p.value for p in data["robo.consumer.backlog"].data.data_points}
    assert backlog == {"vehicle-state": 10, "session-tracker": 0}


def test_setup_without_an_endpoint_does_nothing(monkeypatch):
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    assert obs.setup("x") is False
