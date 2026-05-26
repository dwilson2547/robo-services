from __future__ import annotations

import unittest
from datetime import datetime, timezone

from can_pub_sub_probe.event_codec import (
    decode_drop_event,
    decode_signal_event,
    encode_drop_event,
    encode_signal_event,
    probe_from_headers,
    probe_headers,
)
from can_pub_sub_probe.models import DropEvent, DropReason, ProbeContext, SignalEvent


class EventCodecTests(unittest.TestCase):
    def test_signal_event_round_trip_preserves_probe_context(self) -> None:
        now = datetime.now(tz=timezone.utc)
        event = SignalEvent(
            signal_name="VehicleSpeed",
            value=42.5,
            unit="mph",
            raw_frame_id=0x3E9,
            captured_at=now,
            processed_at=now,
            source_session="session-1",
            probe=ProbeContext(
                tracer_id="abc12345",
                flow_id=7,
                injected_at=now,
            ),
        )

        decoded = decode_signal_event(encode_signal_event(event))

        self.assertEqual(decoded, event)

    def test_probe_headers_round_trip_to_probe_context(self) -> None:
        now = datetime.now(tz=timezone.utc)
        probe = ProbeContext(
            tracer_id="abc12345",
            flow_id=7,
            injected_at=now,
        )

        decoded = probe_from_headers(probe_headers(probe))

        self.assertEqual(decoded, probe)

    def test_drop_event_round_trip(self) -> None:
        event = DropEvent(
            tracer_id="abc12345",
            hop="router",
            outcome="dropped",
            reason_code=DropReason.NO_ROUTE,
            reason_detail="no route",
            signal="UnknownSignal",
            timestamp=datetime.now(tz=timezone.utc),
        )

        decoded = decode_drop_event(encode_drop_event(event))

        self.assertEqual(decoded, event)
