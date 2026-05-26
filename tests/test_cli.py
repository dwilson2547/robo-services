from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from can_pub_sub_probe.cli import main
from can_pub_sub_probe.models import RawFrame
from can_pub_sub_probe.probe import build_probe_frame
from can_pub_sub_probe.replay import write_frame_log


class CliTests(unittest.TestCase):
    def test_inspect_probes_reports_probe_frames(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            log_path = Path(tempdir) / "frames.bin"
            write_frame_log(
                log_path,
                [
                    RawFrame(timestamp_ms=1_000, can_id=0x3E9, dlc=2, data=bytes([0x13, 0x88])),
                    build_probe_frame(
                        timestamp_ms=1_050,
                        tracer_fragment=bytes.fromhex("a1b2c3d4"),
                        flow_id=9,
                    ),
                ],
            )
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["inspect-probes", str(log_path)])

            payload = json.loads(stdout.getvalue())
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["total_frames"], 2)
            self.assertEqual(payload["probe_frame_count"], 1)
            self.assertEqual(payload["probe_frames"][0]["tracer_id"], "a1b2c3d4")

    def test_replay_run_emits_pipeline_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            log_path = Path(tempdir) / "replay.bin"
            write_frame_log(
                log_path,
                [
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
                ],
            )
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["replay-run", str(log_path)])

            payload = json.loads(stdout.getvalue())
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["frame_count"], 2)
            self.assertEqual(payload["validated_events"], 4)
            self.assertEqual(payload["aggregated_events"], 6)
            self.assertEqual(payload["sink_summary"]["raw_records_written"], 4)
            self.assertEqual(payload["sink_summary"]["aggregate_records_written"], 6)

    def test_run_fixture_uses_sidecar_metadata_and_output_dir(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            fixture_dir = Path(tempdir) / "fixture"
            fixture_dir.mkdir()
            write_frame_log(
                fixture_dir / "frames.bin",
                [
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
                ],
            )
            (fixture_dir / "session.json").write_text(
                json.dumps(
                    {
                        "session_id": "fixture-session-1",
                        "vehicle": "2008 Chevrolet Impala",
                        "capture_start_utc": "2026-01-01T00:00:00Z",
                        "bus": "HS-CAN",
                        "baud": 500000,
                        "frame_count": 2,
                        "duration_seconds": 1,
                    }
                ),
                encoding="utf-8",
            )
            output_dir = Path(tempdir) / "out"
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["run-fixture", str(fixture_dir), "--output-dir", str(output_dir)])

            payload = json.loads(stdout.getvalue())
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["source_session"], "fixture-session-1")
            self.assertEqual(payload["metadata"]["frame_count"], 2)
            self.assertTrue((output_dir / "validated-signals.jsonl").exists())
            self.assertTrue((output_dir / "aggregated-signals.jsonl").exists())

    def test_run_fixture_checks_expected_summary_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            fixture_dir = Path(tempdir) / "fixture"
            fixture_dir.mkdir()
            write_frame_log(
                fixture_dir / "frames.bin",
                [
                    RawFrame(
                        timestamp_ms=1_000,
                        can_id=0x3E9,
                        dlc=2,
                        data=bytes([0x13, 0x88]),
                    )
                ],
            )
            (fixture_dir / "session.json").write_text(
                json.dumps(
                    {
                        "session_id": "fixture-session-2",
                        "vehicle": "2008 Chevrolet Impala",
                        "capture_start_utc": "2026-01-01T00:00:00Z",
                        "bus": "HS-CAN",
                        "baud": 500000,
                        "frame_count": 1,
                        "duration_seconds": 1,
                    }
                ),
                encoding="utf-8",
            )
            (fixture_dir / "expected.json").write_text(
                json.dumps(
                    {
                        "frame_count": 1,
                        "validated_events": 1,
                        "diagnostics": {"count": 0},
                    }
                ),
                encoding="utf-8",
            )
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                exit_code = main(["run-fixture", str(fixture_dir)])

            payload = json.loads(stdout.getvalue())
            self.assertEqual(exit_code, 0)
            self.assertTrue(payload["expectation_check"]["passed"])
