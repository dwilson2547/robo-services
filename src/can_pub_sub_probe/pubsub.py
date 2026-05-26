from __future__ import annotations

from collections import deque
from collections.abc import Iterator
from dataclasses import dataclass, field


@dataclass(slots=True)
class InMemoryMessage:
    payload: bytes
    headers: dict[str, str]
    acknowledged: bool = False
    rejected: bool = False

    def ack(self) -> None:
        self.acknowledged = True

    def nack(self) -> None:
        self.rejected = True


@dataclass(slots=True)
class InMemoryPubSubBackend:
    _topics: dict[str, deque[InMemoryMessage]] = field(default_factory=dict)

    def publish(self, topic: str, payload: bytes, headers: dict[str, str]) -> None:
        queue = self._topics.setdefault(topic, deque())
        queue.append(InMemoryMessage(payload=payload, headers=dict(headers)))

    def subscribe(self, topic: str) -> Iterator[InMemoryMessage]:
        queue = self._topics.setdefault(topic, deque())
        while queue:
            yield queue.popleft()

    def pending(self, topic: str) -> int:
        return len(self._topics.get(topic, ()))
