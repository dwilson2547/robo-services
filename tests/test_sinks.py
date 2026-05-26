from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from can_pub_sub_probe.models import AggregatedSignal, AggregationKind, ProbeContext, SignalEvent
from can_pub_sub_probe.sinks import InMemorySinkBackend, JsonlFileSinkBackend, SinkEngine


def make_signal_event(*, signal_name: str, value: float, probe: ProbeContext | None = None) -> SignalEvent:
    timestamp = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return SignalEvent(
        signal_name=signal_name,
        value=value,
        unit="mph",
        raw_frame_id=0x321,
        captured_at=timestamp,
        processed_at=timestamp,
        source_session="sink-test",
        probe=probe,
    )


def make_aggregated_signal(
    *,
    signal_name: str,
    value: float,
    probe: ProbeContext | None = None,
) -> AggregatedSignal:
    timestamp = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return AggregatedSignal(
        signal_name=signal_name,
        topic="signals.powertrain",
        unit="mph",
        window_start=timestamp,
        window_end=timestamp,
        sample_count=1,
        min_value=value,
        max_value=value,
        mean_value=value,
        stddev_value=0.0,
        source_session="sink-test",
        kind=AggregationKind.WINDOWED,
        probe=probe,
    )


class SinkTests(unittest.TestCase):
    def test_sink_engine_writes_raw_and_aggregate_records(self) -> None:
        backend = InMemorySinkBackend()
        summary = SinkEngine(backend).write(
            validated_events=[make_signal_event(signal_name="VehicleSpeed", value=34.0)],
            aggregated_events=[make_aggregated_signal(signal_name="VehicleSpeed", value=34.5)],
        )

        self.assertEqual(summary.raw_records_written, 1)
        self.assertEqual(summary.aggregate_records_written, 1)
        self.assertEqual(summary.terminal_events_recorded, 0)
        self.assertEqual(backend.raw_records[0].source_topic, "signals.validated")
        self.assertEqual(backend.aggregate_records[0].measurement, "VehicleSpeed")
        self.assertEqual(backend.aggregate_records[0].domain, "powertrain")

    def test_sink_engine_suppresses_probe_egress_and_records_terminal_events(self) -> None:
        probe = ProbeContext(
            tracer_id="probe1234",
            flow_id=7,
            injected_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
        )
        backend = InMemorySinkBackend()
        summary = SinkEngine(backend).write(
            validated_events=[make_signal_event(signal_name="VehicleSpeed", value=34.0, probe=probe)],
            aggregated_events=[make_aggregated_signal(signal_name="VehicleSpeed", value=34.5, probe=probe)],
        )

        self.assertEqual(summary.raw_records_written, 0)
        self.assertEqual(summary.aggregate_records_written, 0)
        self.assertEqual(summary.terminal_events_recorded, 2)
        self.assertEqual(backend.raw_records, [])
        self.assertEqual(backend.aggregate_records, [])
        self.assertEqual(
            [(item.record_kind, item.egress_suppressed) for item in backend.terminal_events],
            [("raw", True), ("aggregate", True)],
        )
        self.assertEqual(
            [item.outcome for item in backend.terminal_events],
            ["forwarded_to_sink", "forwarded_to_sink"],
        )

    def test_jsonl_sink_backend_writes_files(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            backend = JsonlFileSinkBackend(
                raw_path=tempdir_path / "raw.jsonl",
                aggregate_path=tempdir_path / "aggregate.jsonl",
                terminal_path=tempdir_path / "terminal.jsonl",
            )
            probe = ProbeContext(
                tracer_id="probe1234",
                flow_id=7,
                injected_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
                suppress_egress=False,
            )
            SinkEngine(backend).write(
                validated_events=[make_signal_event(signal_name="VehicleSpeed", value=34.0, probe=probe)],
                aggregated_events=[make_aggregated_signal(signal_name="VehicleSpeed", value=34.5)],
            )

            raw_lines = (tempdir_path / "raw.jsonl").read_text(encoding="utf-8").strip().splitlines()
            aggregate_lines = (tempdir_path / "aggregate.jsonl").read_text(encoding="utf-8").strip().splitlines()
            terminal_lines = (tempdir_path / "terminal.jsonl").read_text(encoding="utf-8").strip().splitlines()

            raw_record = json.loads(raw_lines[0])
            aggregate_record = json.loads(aggregate_lines[0])
            terminal_record = json.loads(terminal_lines[0])

            self.assertEqual(raw_record["signal_name"], "VehicleSpeed")
            self.assertEqual(aggregate_record["measurement"], "VehicleSpeed")
            self.assertEqual(aggregate_record["domain"], "powertrain")
            self.assertEqual(terminal_record["outcome"], "forwarded_to_sink")
            self.assertFalse(terminal_record["egress_suppressed"])
