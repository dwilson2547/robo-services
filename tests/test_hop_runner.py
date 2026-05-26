from __future__ import annotations

import unittest
from datetime import datetime, timezone

from can_pub_sub_probe.diagnostics import PubSubDiagnosticSink
from can_pub_sub_probe.event_codec import decode_drop_event, decode_signal_event, encode_signal_event
from can_pub_sub_probe.hop_runner import RouterHopRunner
from can_pub_sub_probe.models import ProbeContext, SignalEvent
from can_pub_sub_probe.pubsub import InMemoryPubSubBackend
from can_pub_sub_probe.routing import SignalRouter, build_default_routing_table


class HopRunnerTests(unittest.TestCase):
    def test_router_hop_runner_republishes_to_routed_topics(self) -> None:
        backend = InMemoryPubSubBackend()
        event = SignalEvent(
            signal_name="VehicleSpeed",
            value=45.0,
            unit="mph",
            raw_frame_id=0x3E9,
            captured_at=datetime.now(tz=timezone.utc),
            processed_at=datetime.now(tz=timezone.utc),
            source_session="session-2",
            probe=ProbeContext(
                tracer_id="deadbeef",
                flow_id=3,
                injected_at=datetime.now(tz=timezone.utc),
            ),
        )
        backend.publish(
            "signals.validated",
            encode_signal_event(event),
            {"x-test-id": "runner-1", "x-probe": "true"},
        )
        router = SignalRouter(
            build_default_routing_table(),
            PubSubDiagnosticSink(backend),
            signal_versions={"VehicleSpeed": 1},
        )

        processed = RouterHopRunner(backend=backend, router=router).run_once()

        self.assertEqual(processed, 1)
        routed_messages = list(backend.subscribe("signals.powertrain"))
        self.assertEqual(len(routed_messages), 1)
        self.assertEqual(routed_messages[0].headers["x-route-rule-id"], "route-speed-to-powertrain")
        self.assertEqual(routed_messages[0].headers["x-probe"], "true")
        self.assertEqual(routed_messages[0].headers["x-probe-id"], "deadbeef")
        self.assertEqual(decode_signal_event(routed_messages[0].payload, headers=routed_messages[0].headers), event)

    def test_router_hop_runner_publishes_diagnostics_for_probe_drop(self) -> None:
        backend = InMemoryPubSubBackend()
        dropped_event = SignalEvent(
            signal_name="UnknownSignal",
            value=1.0,
            unit="count",
            raw_frame_id=0x321,
            captured_at=datetime.now(tz=timezone.utc),
            processed_at=datetime.now(tz=timezone.utc),
            source_session="session-3",
            probe=ProbeContext(
                tracer_id="feedface",
                flow_id=5,
                injected_at=datetime.now(tz=timezone.utc),
            ),
        )
        backend.publish(
            "signals.validated",
            encode_signal_event(dropped_event),
            {"x-test-id": "runner-2"},
        )
        router = SignalRouter(
            build_default_routing_table(),
            PubSubDiagnosticSink(backend),
            signal_versions={"UnknownSignal": 1},
            sample_real_traffic=lambda _: True,
        )

        processed = RouterHopRunner(backend=backend, router=router).run_once()

        self.assertEqual(processed, 1)
        diagnostics = list(backend.subscribe("diagnostics.drop_events"))
        self.assertEqual(len(diagnostics), 1)
        self.assertEqual(diagnostics[0].headers["x-probe-id"], "feedface")
        self.assertEqual(decode_drop_event(diagnostics[0].payload).signal, "UnknownSignal")
