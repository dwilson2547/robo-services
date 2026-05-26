from __future__ import annotations

import unittest

from can_pub_sub_probe.hops.aggregate import AggregationConfig
from can_pub_sub_probe.hops.validate import ValidationConfig
from can_pub_sub_probe.models import RawFrame
from can_pub_sub_probe.pipeline import run_local_pipeline
from can_pub_sub_probe.profiles import build_impala_2008_can_profile
from can_pub_sub_probe.routing import build_default_routing_table
from can_pub_sub_probe.sinks import InMemorySinkBackend


class PipelineTests(unittest.TestCase):
    def test_run_local_pipeline_returns_validated_events(self) -> None:
        profile = build_impala_2008_can_profile()
        sink_backend = InMemorySinkBackend()
        frames = [
            RawFrame(
                timestamp_ms=1_000,
                can_id=0x0C9,
                dlc=6,
                data=bytes([0x00, 0x0F, 0xA0, 0x00, 0x7F, 0x01]),
            ),
            RawFrame(
                timestamp_ms=1_100,
                can_id=0x3E9,
                dlc=2,
                data=bytes([0x13, 0x88]),
            ),
        ]

        result = run_local_pipeline(
            frames,
            profile=profile,
            source_session="session-4",
            validation_config=ValidationConfig(
                ranges={
                    "EngineRPM": (0, 8_000),
                    "ThrottlePosition": (0, 100),
                    "BrakePedalApplied": (0, 1),
                    "VehicleSpeed": (0, 200),
                }
            ),
            routing_table=build_default_routing_table(),
            aggregation_config=AggregationConfig(default_window_seconds=5),
            sink_backend=sink_backend,
        )

        self.assertEqual([event.signal_name for event in result.validated_events], [
            "EngineRPM",
            "ThrottlePosition",
            "BrakePedalApplied",
            "VehicleSpeed",
        ])
        self.assertEqual(
            [(route.topic, route.event.signal_name) for route in result.routed_events],
            [
                ("signals.powertrain", "EngineRPM"),
                ("signals.powertrain", "ThrottlePosition"),
                ("signals.chassis", "BrakePedalApplied"),
                ("signals.powertrain", "VehicleSpeed"),
                ("signals.chassis", "VehicleSpeed"),
            ],
        )
        self.assertEqual(
            [(item.topic, item.signal_name, item.kind.value) for item in result.aggregated_events],
            [
                ("signals.chassis", "BrakePedalApplied", "WINDOWED"),
                ("signals.chassis", "VehicleSpeed", "WINDOWED"),
                ("signals.powertrain", "EngineRPM", "WINDOWED"),
                ("signals.powertrain", "PowertrainLoadIndex", "DERIVED"),
                ("signals.powertrain", "ThrottlePosition", "WINDOWED"),
                ("signals.powertrain", "VehicleSpeed", "WINDOWED"),
            ],
        )
        self.assertEqual(result.diagnostics.events, [])
        self.assertIsNotNone(result.sink_summary)
        self.assertEqual(result.sink_summary.raw_records_written, 4)
        self.assertEqual(result.sink_summary.aggregate_records_written, 6)
        self.assertEqual(result.sink_summary.terminal_events_recorded, 0)
        self.assertEqual(len(sink_backend.raw_records), 4)
        self.assertEqual(len(sink_backend.aggregate_records), 6)
