from __future__ import annotations

import unittest
from datetime import timezone

from can_pub_sub_probe.diagnostics import InMemoryDiagnosticSink
from can_pub_sub_probe.hops.ingest import IngestNormalizer
from can_pub_sub_probe.hops.validate import ValidationConfig, ValidationEngine
from can_pub_sub_probe.models import DropReason, RawFrame
from can_pub_sub_probe.probe import build_probe_frame
from can_pub_sub_probe.profiles import build_impala_2008_can_profile


class IngestAndValidateTests(unittest.TestCase):
    def test_ingest_normalizes_impala_engine_frame(self) -> None:
        profile = build_impala_2008_can_profile()
        normalizer = IngestNormalizer(profile)
        frame = RawFrame(
            timestamp_ms=1_000,
            can_id=0x0C9,
            dlc=6,
            data=bytes([0x00, 0x0F, 0xA0, 0x00, 0x7F, 0x01]),
        )

        events = normalizer.normalize_frame(frame, source_session="session-1")

        self.assertEqual([event.signal_name for event in events], [
            "EngineRPM",
            "ThrottlePosition",
            "BrakePedalApplied",
        ])
        self.assertEqual(events[0].value, 1000.0)
        self.assertAlmostEqual(events[1].value, 49.8039215686)
        self.assertEqual(events[2].value, 1.0)

    def test_ingest_recognizes_probe_frame(self) -> None:
        profile = build_impala_2008_can_profile()
        normalizer = IngestNormalizer(profile)
        frame = build_probe_frame(
            timestamp_ms=2_000,
            tracer_fragment=b"\xAA\xBB\xCC\xDD",
            flow_id=7,
        )

        events = normalizer.normalize_frame(frame, source_session="session-2")

        self.assertEqual(len(events), 1)
        self.assertEqual(events[0].signal_name, "ProbeFrame")
        self.assertEqual(events[0].probe.tracer_id, "aabbccdd")
        self.assertEqual(events[0].probe.flow_id, 7)
        self.assertEqual(events[0].captured_at.tzinfo, timezone.utc)

    def test_validation_emits_diagnostics_for_probe_drop(self) -> None:
        profile = build_impala_2008_can_profile()
        normalizer = IngestNormalizer(profile)
        diagnostic_sink = InMemoryDiagnosticSink()
        engine = ValidationEngine(
            ValidationConfig(ranges={"ProbeFrame": (0.0, 0.0)}),
            diagnostic_sink,
        )
        events = normalizer.normalize_frame(
            build_probe_frame(
                timestamp_ms=2_000,
                tracer_fragment=b"\xAA\xBB\xCC\xDD",
                flow_id=7,
            ),
            source_session="session-3",
        )

        validated = engine.validate(events)

        self.assertEqual(validated, [])
        self.assertEqual(len(diagnostic_sink.events), 1)
        self.assertEqual(diagnostic_sink.events[0].reason_code, DropReason.RANGE_EXCEEDED)
