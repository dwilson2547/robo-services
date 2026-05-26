from __future__ import annotations

import unittest
from datetime import datetime, timezone

from can_pub_sub_probe.diagnostics import InMemoryDiagnosticSink
from can_pub_sub_probe.models import DropReason, SignalEvent
from can_pub_sub_probe.routing import RouteRule, RoutingTable, SignalRouter, build_default_routing_table


def make_event(signal_name: str) -> SignalEvent:
    now = datetime.now(tz=timezone.utc)
    return SignalEvent(
        signal_name=signal_name,
        value=1.0,
        unit="count",
        raw_frame_id=0x123,
        captured_at=now,
        processed_at=now,
        source_session="test-session",
    )


class RoutingTests(unittest.TestCase):
    def test_default_routing_table_supports_multi_domain_fan_out(self) -> None:
        router = SignalRouter(
            build_default_routing_table(),
            InMemoryDiagnosticSink(),
            signal_versions={"VehicleSpeed": 1},
        )

        routed = router.route([make_event("VehicleSpeed")])

        self.assertEqual(
            [(item.topic, item.rule_id) for item in routed],
            [
                ("signals.powertrain", "route-speed-to-powertrain"),
                ("signals.chassis", "route-chassis-primary"),
            ],
        )

    def test_router_emits_no_route_diagnostic(self) -> None:
        sink = InMemoryDiagnosticSink()
        router = SignalRouter(
            build_default_routing_table(),
            sink,
            signal_versions={"UnknownSignal": 1},
            sample_real_traffic=lambda _: True,
        )

        routed = router.route([make_event("UnknownSignal")])

        self.assertEqual(routed, [])
        self.assertEqual(len(sink.events), 1)
        self.assertEqual(sink.events[0].reason_code, DropReason.NO_ROUTE)

    def test_router_emits_version_mismatch_diagnostic(self) -> None:
        sink = InMemoryDiagnosticSink()
        router = SignalRouter(
            RoutingTable(
                name="stale-routing-table",
                version=0,
                rules=(
                    RouteRule(
                        id="route-engine",
                        signal_patterns=("EngineRPM",),
                        topics=("signals.powertrain",),
                    ),
                ),
            ),
            sink,
            signal_versions={"EngineRPM": 1},
            sample_real_traffic=lambda _: True,
        )

        routed = router.route([make_event("EngineRPM")])

        self.assertEqual(routed, [])
        self.assertEqual(len(sink.events), 1)
        self.assertEqual(
            sink.events[0].reason_code,
            DropReason.ROUTING_TABLE_VERSION_MISMATCH,
        )
