from __future__ import annotations

from collections.abc import Iterator, Sequence
from typing import Protocol

from .models import DecodedSignal, DropEvent, RawFrame


class FrameSource(Protocol):
    def __iter__(self) -> Iterator[RawFrame]: ...


class Message(Protocol):
    @property
    def payload(self) -> bytes: ...

    @property
    def headers(self) -> dict[str, str]: ...

    def ack(self) -> None: ...

    def nack(self) -> None: ...


class PubSubBackend(Protocol):
    def publish(self, topic: str, payload: bytes, headers: dict[str, str]) -> None: ...

    def subscribe(self, topic: str) -> Iterator[Message]: ...


class DiagnosticSink(Protocol):
    def publish(self, event: DropEvent) -> None: ...


class FrameDecoder(Protocol):
    def decode(self, frame: RawFrame) -> Sequence[DecodedSignal]: ...
