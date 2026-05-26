from __future__ import annotations

import unittest
from datetime import datetime, timezone

from can_pub_sub_probe.diagnostics import InMemoryDiagnosticSink
from can_pub_sub_probe.hops.aggregate import AggregationConfig, AggregationEngine
from can_pub_sub_probe.models import AggregationKind, DropReason, ProbeContext, SignalEvent
from can_pub_sub_probe.routing import RoutedSignal


def make_routed_signal(
    *,
    topic: str,
    signal_name: str,
    value: float,
    timestamp: str,
    unit: str = "count",
    probe: ProbeContext | None = None,
) -> RoutedSignal:
    event_time = datetime.fromisoformat(timestamp).astimezone(timezone.utc)
    return RoutedSignal(
        topic=topic,
        rule_id="test-rule",
        event=SignalEvent(
            signal_name=signal_name,
            value=value,
            unit=unit,
            raw_frame_id=0x100,
            captured_at=event_time,
            processed_at=event_time,
            source_session="aggregation-test",
            probe=probe,
        ),
    )


class AggregationTests(unittest.TestCase):
    def test_aggregation_computes_windowed_stats_and_derived_signal(self) -> None:
        sink = InMemoryDiagnosticSink()
        engine = AggregationEngine(
            AggregationConfig(
                default_window_seconds=5,
                min_samples={"EngineRPM": 2, "VehicleSpeed": 2},
            ),
            sink,
        )

        aggregated = engine.aggregate(
            [
                make_routed_signal(
                    topic="signals.powertrain",
                    signal_name="EngineRPM",
                    value=1000.0,
                    unit="rpm",
                    timestamp="2026-01-01T00:00:01+00:00",
                ),
                make_routed_signal(
                    topic="signals.powertrain",
                    signal_name="EngineRPM",
                    value=1200.0,
                    unit="rpm",
                    timestamp="2026-01-01T00:00:03+00:00",
                ),
                make_routed_signal(
                    topic="signals.powertrain",
                    signal_name="VehicleSpeed",
                    value=40.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:01+00:00",
                ),
                make_routed_signal(
                    topic="signals.powertrain",
                    signal_name="VehicleSpeed",
                    value=50.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:04+00:00",
                ),
            ]
        )

        by_name = {item.signal_name: item for item in aggregated}
        self.assertEqual(by_name["EngineRPM"].kind, AggregationKind.WINDOWED)
        self.assertEqual(by_name["EngineRPM"].sample_count, 2)
        self.assertEqual(by_name["EngineRPM"].min_value, 1000.0)
        self.assertEqual(by_name["EngineRPM"].max_value, 1200.0)
        self.assertEqual(by_name["EngineRPM"].mean_value, 1100.0)
        self.assertAlmostEqual(by_name["EngineRPM"].stddev_value, 100.0)
        self.assertEqual(by_name["PowertrainLoadIndex"].kind, AggregationKind.DERIVED)
        self.assertEqual(by_name["PowertrainLoadIndex"].derived_from, ("EngineRPM", "VehicleSpeed"))
        self.assertEqual(by_name["PowertrainLoadIndex"].mean_value, 49_500.0)
        self.assertEqual(sink.events, [])

    def test_probe_messages_pass_through_without_waiting_for_window_close(self) -> None:
        sink = InMemoryDiagnosticSink()
        engine = AggregationEngine(AggregationConfig(default_window_seconds=5), sink)
        probe = ProbeContext(
            tracer_id="abc12345",
            flow_id=9,
            injected_at=datetime.now(tz=timezone.utc),
        )

        aggregated = engine.aggregate(
            [
                make_routed_signal(
                    topic="signals.chassis",
                    signal_name="VehicleSpeed",
                    value=55.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:02+00:00",
                    probe=probe,
                )
            ]
        )

        self.assertEqual(len(aggregated), 1)
        self.assertEqual(aggregated[0].kind, AggregationKind.PROBE_PASSTHROUGH)
        self.assertEqual(aggregated[0].probe, probe)

    def test_insufficient_samples_emits_drop_event(self) -> None:
        sink = InMemoryDiagnosticSink()
        engine = AggregationEngine(
            AggregationConfig(default_window_seconds=5, min_samples={"VehicleSpeed": 2}),
            sink,
            sample_real_traffic=lambda _: True,
        )

        aggregated = engine.aggregate(
            [
                make_routed_signal(
                    topic="signals.chassis",
                    signal_name="VehicleSpeed",
                    value=30.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:01+00:00",
                )
            ]
        )

        self.assertEqual(aggregated, [])
        self.assertEqual(len(sink.events), 1)
        self.assertEqual(sink.events[0].reason_code, DropReason.INSUFFICIENT_SAMPLES)

    def test_window_overflow_emits_drop_event(self) -> None:
        sink = InMemoryDiagnosticSink()
        engine = AggregationEngine(
            AggregationConfig(default_window_seconds=5, max_samples={"VehicleSpeed": 2}),
            sink,
            sample_real_traffic=lambda _: True,
        )

        aggregated = engine.aggregate(
            [
                make_routed_signal(
                    topic="signals.chassis",
                    signal_name="VehicleSpeed",
                    value=30.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:01+00:00",
                ),
                make_routed_signal(
                    topic="signals.chassis",
                    signal_name="VehicleSpeed",
                    value=31.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:02+00:00",
                ),
                make_routed_signal(
                    topic="signals.chassis",
                    signal_name="VehicleSpeed",
                    value=32.0,
                    unit="mph",
                    timestamp="2026-01-01T00:00:03+00:00",
                ),
            ]
        )

        self.assertEqual(aggregated, [])
        self.assertEqual(len(sink.events), 1)
        self.assertEqual(sink.events[0].reason_code, DropReason.WINDOW_OVERFLOW)
