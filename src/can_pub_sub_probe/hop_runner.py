from __future__ import annotations

from dataclasses import dataclass

from .event_codec import decode_signal_event, encode_signal_event, probe_headers
from .interfaces import PubSubBackend
from .routing import SignalRouter


@dataclass(slots=True)
class RouterHopRunner:
    backend: PubSubBackend
    router: SignalRouter
    input_topic: str = "signals.validated"

    def run_once(self) -> int:
        processed = 0
        for message in self.backend.subscribe(self.input_topic):
            try:
                event = decode_signal_event(message.payload, headers=message.headers)
                for routed_signal in self.router.route([event]):
                    headers = dict(message.headers)
                    headers["x-route-rule-id"] = routed_signal.rule_id
                    headers.update(probe_headers(routed_signal.event.probe))
                    self.backend.publish(
                        routed_signal.topic,
                        encode_signal_event(routed_signal.event),
                        headers,
                    )
                message.ack()
                processed += 1
            except Exception:
                message.nack()
                raise
        return processed
