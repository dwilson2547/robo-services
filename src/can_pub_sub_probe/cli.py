from __future__ import annotations

import argparse
import json
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .diagnostics import PubSubDiagnosticSink
from .event_codec import (
    decode_drop_event,
    decode_signal_event,
    encode_signal_event,
    probe_headers,
)
from .frame_codec import iter_frame_file
from .hop_runner import RouterHopRunner
from .iggy_backend import IggyBackendConfig, IggyPubSubBackend
from .hops.validate import ValidationConfig
from .models import ProbeContext, SignalEvent
from .pipeline import run_local_pipeline
from .profiles import build_impala_2008_can_profile
from .probe import detect_probe
from .replay import ReplayFrameSource, ReplaySessionMetadata, load_frame_log
from .routing import SignalRouter, build_default_routing_table
from .sinks import InMemorySinkBackend, JsonlFileSinkBackend


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="can-pub-sub-probe")
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect_parser = subparsers.add_parser("inspect-log", help="Print frames from a binary log")
    inspect_parser.add_argument("path", type=Path)
    inspect_parser.add_argument("--limit", type=int, default=10)

    probe_parser = subparsers.add_parser("inspect-probes", help="Inspect probe frames in a binary log")
    probe_parser.add_argument("path", type=Path)
    probe_parser.add_argument("--limit", type=int, default=20)

    profile_parser = subparsers.add_parser("show-profile", help="Print the default vehicle CAN profile")
    profile_parser.add_argument("--ids-only", action="store_true")

    replay_parser = subparsers.add_parser("replay-run", help="Run a local replay through the pipeline")
    replay_parser.add_argument("path", type=Path)
    replay_parser.add_argument("--source-session")
    replay_parser.add_argument("--metadata", type=Path)
    replay_parser.add_argument("--speed-multiplier", type=float, default=1.0)
    replay_parser.add_argument("--preserve-timing", action="store_true")
    replay_parser.add_argument("--output-dir", type=Path)

    fixture_parser = subparsers.add_parser(
        "run-fixture",
        help="Run a fixture directory containing frames.bin and session.json",
    )
    fixture_parser.add_argument("fixture_dir", type=Path)
    fixture_parser.add_argument("--speed-multiplier", type=float, default=1.0)
    fixture_parser.add_argument("--preserve-timing", action="store_true")
    fixture_parser.add_argument("--output-dir", type=Path)

    iggy_parser = subparsers.add_parser("iggy-router-smoke", help="Run an Iggy-backed router smoke test")
    iggy_parser.add_argument(
        "--connection-string",
        default="iggy+tcp://iggy:iggy@127.0.0.1:8090",
    )
    iggy_parser.add_argument("--stream", default="can-pub-sub-probe")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect-log":
            for index, frame in enumerate(iter_frame_file(args.path)):
                if index >= args.limit:
                    break
                print(
                    f"{frame.timestamp_ms} can_id=0x{frame.can_id:X} "
                    f"dlc={frame.dlc} extended={frame.is_extended} data={frame.data.hex()}"
                )
            return 0
        if args.command == "inspect-probes":
            return inspect_probes(path=args.path, limit=args.limit)
        if args.command == "show-profile":
            profile = build_impala_2008_can_profile()
            if args.ids_only:
                for can_id in sorted(profile.accepted_can_ids):
                    print(f"0x{can_id:X}")
                return 0
            print(f"name: {profile.name}")
            print(f"vehicle: {profile.vehicle}")
            print(f"bus: {profile.bus}")
            print(f"baud: {profile.baud}")
            print("accepted_can_ids:")
            for can_id in sorted(profile.accepted_can_ids):
                print(f"  - 0x{can_id:X}")
            return 0
        if args.command == "replay-run":
            return replay_run(
                path=args.path,
                source_session=args.source_session,
                metadata_path=args.metadata,
                speed_multiplier=args.speed_multiplier,
                preserve_timing=args.preserve_timing,
                output_dir=args.output_dir,
            )
        if args.command == "run-fixture":
            return run_fixture(
                fixture_dir=args.fixture_dir,
                speed_multiplier=args.speed_multiplier,
                preserve_timing=args.preserve_timing,
                output_dir=args.output_dir,
            )
        if args.command == "iggy-router-smoke":
            return run_iggy_router_smoke(
                connection_string=args.connection_string,
                stream=args.stream,
            )
        parser.error(f"unknown command {args.command}")
        return 2
    except (FileNotFoundError, ValueError) as exc:
        print(f"error: {exc}")
        return 1


def inspect_probes(*, path: Path, limit: int) -> int:
    probe_rows: list[dict[str, object]] = []
    total_frames = 0
    total_probe_frames = 0
    for frame in iter_frame_file(path):
        total_frames += 1
        probe = detect_probe(frame)
        if probe is None:
            continue
        total_probe_frames += 1
        if len(probe_rows) < limit:
            probe_rows.append(
                {
                    "timestamp_ms": frame.timestamp_ms,
                    "tracer_id": probe.tracer_id,
                    "flow_id": probe.flow_id,
                    "suppress_egress": probe.suppress_egress,
                    "can_id": f"0x{frame.can_id:X}",
                }
            )
    _print_json(
        {
            "path": str(path),
            "total_frames": total_frames,
            "probe_frame_count": total_probe_frames,
            "probe_frames": probe_rows,
        }
    )
    return 0


def replay_run(
    *,
    path: Path,
    source_session: str | None,
    metadata_path: Path | None,
    speed_multiplier: float,
    preserve_timing: bool,
    output_dir: Path | None,
    fixture_dir: Path | None = None,
) -> int:
    summary = build_replay_summary(
        path=path,
        source_session=source_session,
        metadata_path=metadata_path,
        speed_multiplier=speed_multiplier,
        preserve_timing=preserve_timing,
        output_dir=output_dir,
        fixture_dir=fixture_dir,
    )
    _print_json(summary)
    return 0


def build_replay_summary(
    *,
    path: Path,
    source_session: str | None,
    metadata_path: Path | None,
    speed_multiplier: float,
    preserve_timing: bool,
    output_dir: Path | None,
    fixture_dir: Path | None = None,
) -> dict[str, Any]:
    frames = load_frame_log(path)
    metadata = ReplaySessionMetadata.from_path(metadata_path) if metadata_path is not None else None
    effective_session = source_session or (metadata.session_id if metadata is not None else path.stem)
    frame_source = (
        ReplayFrameSource(frames=frames, speed_multiplier=speed_multiplier)
        if preserve_timing
        else frames
    )
    sink_backend = _sink_backend(output_dir)
    result = run_local_pipeline(
        frame_source,
        profile=build_impala_2008_can_profile(),
        source_session=effective_session,
        validation_config=_default_validation_config(),
        validate_sample_real_traffic=lambda _: True,
        routing_table=build_default_routing_table(),
        route_sample_real_traffic=lambda _: True,
        aggregation_config=_default_aggregation_config(),
        aggregate_sample_real_traffic=lambda _: True,
        sink_backend=sink_backend,
        ingest_sample_real_traffic=lambda _: True,
    )
    return {
        "command": "run-fixture" if fixture_dir is not None else "replay-run",
        "path": str(path),
        "fixture_dir": str(fixture_dir) if fixture_dir is not None else None,
        "source_session": effective_session,
        "metadata": _metadata_payload(metadata),
        "frame_count": len(frames),
        "speed_multiplier": speed_multiplier,
        "preserve_timing": preserve_timing,
        "validated_events": len(result.validated_events),
        "routed_events": len(result.routed_events),
        "aggregated_events": len(result.aggregated_events),
        "diagnostics": {
            "count": len(result.diagnostics.events),
            "reasons": dict(Counter(event.reason_code.value for event in result.diagnostics.events)),
        },
        "sink_summary": {
            "raw_records_written": result.sink_summary.raw_records_written if result.sink_summary else 0,
            "aggregate_records_written": (
                result.sink_summary.aggregate_records_written if result.sink_summary else 0
            ),
            "terminal_events_recorded": (
                result.sink_summary.terminal_events_recorded if result.sink_summary else 0
            ),
        },
        "output_dir": str(output_dir) if output_dir is not None else None,
    }


def run_fixture(
    *,
    fixture_dir: Path,
    speed_multiplier: float,
    preserve_timing: bool,
    output_dir: Path | None,
) -> int:
    frame_path, metadata_path, expected_path = resolve_fixture_paths(fixture_dir)
    summary = build_replay_summary(
        path=frame_path,
        source_session=None,
        metadata_path=metadata_path,
        speed_multiplier=speed_multiplier,
        preserve_timing=preserve_timing,
        output_dir=output_dir,
        fixture_dir=fixture_dir,
    )
    if expected_path is not None:
        expected = json.loads(expected_path.read_text(encoding="utf-8"))
        expectation_ok, message = compare_expected_summary(summary, expected)
        summary["expectation_check"] = {
            "passed": expectation_ok,
            "expected_path": str(expected_path),
            "message": message,
        }
        _print_json(summary)
        return 0 if expectation_ok else 1
    _print_json(summary)
    return 0


def resolve_fixture_paths(fixture_dir: Path) -> tuple[Path, Path | None, Path | None]:
    if not fixture_dir.is_dir():
        raise FileNotFoundError(f"fixture directory not found: {fixture_dir}")
    frame_candidates = ("frames.bin", "frame-log.bin")
    metadata_candidates = ("session.json", "metadata.json")
    frame_path = _first_existing_path(fixture_dir, frame_candidates)
    if frame_path is None:
        raise FileNotFoundError(
            f"fixture directory {fixture_dir} does not contain one of {frame_candidates}"
        )
    metadata_path = _first_existing_path(fixture_dir, metadata_candidates)
    expected_path = _first_existing_path(fixture_dir, ("expected.json",))
    return frame_path, metadata_path, expected_path


def _first_existing_path(parent: Path, names: tuple[str, ...]) -> Path | None:
    for name in names:
        candidate = parent / name
        if candidate.exists():
            return candidate
    return None


def _sink_backend(output_dir: Path | None) -> InMemorySinkBackend | JsonlFileSinkBackend:
    if output_dir is None:
        return InMemorySinkBackend()
    return JsonlFileSinkBackend(
        raw_path=output_dir / "validated-signals.jsonl",
        aggregate_path=output_dir / "aggregated-signals.jsonl",
        terminal_path=output_dir / "probe-terminal-events.jsonl",
    )


def _default_aggregation_config():
    from .hops.aggregate import AggregationConfig

    return AggregationConfig(default_window_seconds=5)


def _default_validation_config() -> ValidationConfig:
    return ValidationConfig(
        ranges={
            "EngineRPM": (0.0, 8_000.0),
            "ThrottlePosition": (0.0, 100.0),
            "BrakePedalApplied": (0.0, 1.0),
            "VehicleSpeed": (0.0, 200.0),
            "CoolantTemp": (-40.0, 180.0),
            "IntakeAirTemp": (-40.0, 120.0),
            "AmbientTemp": (-60.0, 80.0),
            "EngineOilTemp": (-40.0, 200.0),
            "MassAirFlow": (0.0, 500.0),
            "TransmissionGear": (0.0, 8.0),
            "ProbeFrame": (0.0, 1.0),
        }
    )


def _metadata_payload(metadata: ReplaySessionMetadata | None) -> dict[str, object] | None:
    if metadata is None:
        return None
    return {
        "session_id": metadata.session_id,
        "vehicle": metadata.vehicle,
        "capture_start_utc": metadata.capture_start_utc,
        "bus": metadata.bus,
        "baud": metadata.baud,
        "frame_count": metadata.frame_count,
        "duration_seconds": metadata.duration_seconds,
    }


def compare_expected_summary(
    actual: dict[str, Any],
    expected: dict[str, Any],
) -> tuple[bool, str]:
    mismatches: list[str] = []
    _collect_mismatches(actual=actual, expected=expected, path="", mismatches=mismatches)
    if mismatches:
        return False, "; ".join(mismatches)
    return True, "matched expected summary"


def _collect_mismatches(
    *,
    actual: Any,
    expected: Any,
    path: str,
    mismatches: list[str],
) -> None:
    label = path or "summary"
    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            mismatches.append(f"{label}: expected object, got {type(actual).__name__}")
            return
        for key, value in expected.items():
            if key not in actual:
                mismatches.append(f"{label}.{key}: missing")
                continue
            child_path = f"{label}.{key}" if path else key
            _collect_mismatches(actual=actual[key], expected=value, path=child_path, mismatches=mismatches)
        return
    if isinstance(expected, list):
        if actual != expected:
            mismatches.append(f"{label}: expected {expected!r}, got {actual!r}")
        return
    if actual != expected:
        mismatches.append(f"{label}: expected {expected!r}, got {actual!r}")


def _print_json(payload: dict[str, object]) -> None:
    print(json.dumps(payload, indent=2, sort_keys=True))


def run_iggy_router_smoke(*, connection_string: str, stream: str) -> int:
    backend = IggyPubSubBackend(
        IggyBackendConfig(connection_string=connection_string, stream=stream)
    )
    backend.ping()
    diagnostic_sink = PubSubDiagnosticSink(backend=backend)
    router = SignalRouter(
        build_default_routing_table(),
        diagnostic_sink,
        signal_versions=build_impala_2008_can_profile().signal_versions,
        sample_real_traffic=lambda _: True,
    )
    runner = RouterHopRunner(backend=backend, router=router)
    test_id = str(uuid.uuid4())
    event = SignalEvent(
        signal_name="VehicleSpeed",
        value=48.5,
        unit="mph",
        raw_frame_id=0x3E9,
        captured_at=datetime.now(tz=timezone.utc),
        processed_at=datetime.now(tz=timezone.utc),
        source_session="iggy-smoke-test",
        probe=ProbeContext(
            tracer_id=test_id.replace("-", "")[:8],
            flow_id=7,
            injected_at=datetime.now(tz=timezone.utc),
        ),
    )
    drop_event = SignalEvent(
        signal_name="UnknownSignal",
        value=1.0,
        unit="count",
        raw_frame_id=0x7FF,
        captured_at=datetime.now(tz=timezone.utc),
        processed_at=datetime.now(tz=timezone.utc),
        source_session="iggy-smoke-test",
        probe=ProbeContext(
            tracer_id=test_id.replace("-", "")[:8],
            flow_id=8,
            injected_at=datetime.now(tz=timezone.utc),
        ),
    )
    backend.publish(
        "signals.validated",
        encode_signal_event(event),
        {"x-test-id": test_id, **probe_headers(event.probe)},
    )
    backend.publish(
        "signals.validated",
        encode_signal_event(drop_event),
        {"x-test-id": test_id, **probe_headers(drop_event.probe)},
    )
    processed = runner.run_once()
    powertrain_events = _collect_topic_events(backend, "signals.powertrain", test_id)
    chassis_events = _collect_topic_events(backend, "signals.chassis", test_id)
    diagnostic_events = _collect_drop_events(backend, "diagnostics.drop_events", test_id)
    if (
        processed != 2
        or len(powertrain_events) != 1
        or len(chassis_events) != 1
        or len(diagnostic_events) != 1
    ):
        print(
            "router smoke failed:",
            {
                "processed": processed,
                "powertrain_events": len(powertrain_events),
                "chassis_events": len(chassis_events),
                "diagnostics": len(diagnostic_events),
            },
        )
        return 1
    for message in backend.subscribe("signals.powertrain"):
        if message.headers.get("x-test-id") == test_id and message.headers.get("x-probe") != "true":
            print("router smoke failed: missing probe headers on routed message")
            return 1
    print("router smoke passed")
    return 0


def _collect_topic_events(
    backend: IggyPubSubBackend,
    topic: str,
    test_id: str,
) -> list[SignalEvent]:
    events: list[SignalEvent] = []
    for message in backend.subscribe(topic):
        if message.headers.get("x-test-id") != test_id:
            continue
        events.append(decode_signal_event(message.payload, headers=message.headers))
    return events


def _collect_drop_events(
    backend: IggyPubSubBackend,
    topic: str,
    test_id: str,
) -> list:
    events = []
    for message in backend.subscribe(topic):
        if message.headers.get("x-probe-id") != test_id.replace("-", "")[:8]:
            continue
        events.append(decode_drop_event(message.payload))
    return events
