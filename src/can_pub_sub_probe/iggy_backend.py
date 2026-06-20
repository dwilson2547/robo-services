from __future__ import annotations

import asyncio
import base64
import json
import threading
from dataclasses import dataclass, field
from typing import Any

from apache_iggy import IggyClient, PollingStrategy, SendMessage


@dataclass(frozen=True, slots=True)
class IggyBackendConfig:
    connection_string: str = "iggy+tcp://iggy:iggy@127.0.0.1:8090"
    stream: str = "can-pub-sub-probe"
    partitions_count: int = 1
    replication_factor: int = 1
    poll_count: int = 100
    partition_id: int = 0


@dataclass(slots=True)
class IggyMessage:
    _payload: bytes
    headers: dict[str, str]
    offset: int | None = None
    acknowledged: bool = False
    rejected: bool = False

    @property
    def payload(self) -> bytes:
        return self._payload

    def ack(self) -> None:
        self.acknowledged = True

    def nack(self) -> None:
        self.rejected = True


@dataclass(slots=True)
class IggyPubSubBackend:
    config: IggyBackendConfig
    _client: Any = field(init=False, repr=False)
    _runtime: "_IggyLoopRuntime" = field(init=False, repr=False)
    _connected: bool = field(default=False, init=False, repr=False)
    _stream_ready: bool = field(default=False, init=False, repr=False)
    _known_topics: set[str] = field(default_factory=set, init=False, repr=False)

    def __post_init__(self) -> None:
        self._runtime = _IggyLoopRuntime()
        self._client = self._runtime.run(self._create_client())

    def ping(self) -> None:
        self._run_client("connect")
        self._connected = True
        self._run_client("ping")

    def publish(self, topic: str, payload: bytes, headers: dict[str, str]) -> None:
        self.ensure_topic(topic)
        self._run_client(
            "send_messages",
            stream=self.config.stream,
            topic=topic,
            partitioning=self.config.partition_id,
            messages=[SendMessage(_encode_message(payload, headers))],
        )

    def subscribe(self, topic: str, *, polling_strategy: Any | None = None):
        self.ensure_topic(topic)
        polled_messages = self._run_client(
            "poll_messages",
            stream=self.config.stream,
            topic=topic,
            partition_id=self.config.partition_id,
            polling_strategy=polling_strategy or PollingStrategy.Next(),
            count=self.config.poll_count,
            auto_commit=True,
        )
        for message in polled_messages:
            payload, headers = _decode_message(message.payload())
            yield IggyMessage(
                _payload=payload,
                headers=headers,
                offset=int(message.offset()) if hasattr(message, "offset") else None,
            )

    def latest_offset(self, topic: str) -> int | None:
        self.ensure_topic(topic)
        polled_messages = self._run_client(
            "poll_messages",
            stream=self.config.stream,
            topic=topic,
            partition_id=self.config.partition_id,
            polling_strategy=PollingStrategy.Last(),
            count=1,
            auto_commit=False,
        )
        if not polled_messages:
            return None
        message = polled_messages[-1]
        return int(message.offset()) if hasattr(message, "offset") else None

    def ensure_topic(self, topic: str) -> None:
        self._ensure_stream()
        if topic in self._known_topics:
            return
        existing_topic = self._run_client(
            "get_topic",
            self.config.stream,
            topic,
        )
        if existing_topic is None:
            self._run_client(
                "create_topic",
                stream=self.config.stream,
                name=topic,
                partitions_count=self.config.partitions_count,
                replication_factor=self.config.replication_factor,
            )
        self._known_topics.add(topic)

    def _ensure_stream(self) -> None:
        self._ensure_connected()
        if self._stream_ready:
            return
        existing_stream = self._run_client("get_stream", self.config.stream)
        if existing_stream is None:
            self._run_client("create_stream", name=self.config.stream)
        self._stream_ready = True

    def _ensure_connected(self) -> None:
        if self._connected:
            return
        self._run_client("connect")
        self._connected = True

    async def _create_client(self):
        return IggyClient.from_connection_string(self.config.connection_string)

    async def _invoke_client(self, method_name: str, *args, **kwargs):
        method = getattr(self._client, method_name)
        return await method(*args, **kwargs)

    def _run_client(self, method_name: str, *args, **kwargs):
        return self._runtime.run(self._invoke_client(method_name, *args, **kwargs))

    def _run(self, awaitable):
        return self._runtime.run(awaitable)


@dataclass(slots=True)
class _IggyLoopRuntime:
    _loop: asyncio.AbstractEventLoop = field(init=False, repr=False)
    _thread: threading.Thread = field(init=False, repr=False)

    def __post_init__(self) -> None:
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_forever, daemon=True)
        self._thread.start()

    def run(self, awaitable):
        future = asyncio.run_coroutine_threadsafe(awaitable, self._loop)
        return future.result()

    def _run_forever(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()


def _encode_message(payload: bytes, headers: dict[str, str]) -> bytes:
    envelope = {
        "payload_b64": base64.b64encode(payload).decode("ascii"),
        "headers": headers,
    }
    return json.dumps(envelope, separators=(",", ":"), sort_keys=True).encode("utf-8")


def _decode_message(payload: bytes) -> tuple[bytes, dict[str, str]]:
    try:
        envelope = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return payload, {}
    encoded_payload = envelope.get("payload_b64")
    headers = envelope.get("headers")
    if not isinstance(encoded_payload, str) or not isinstance(headers, dict):
        return payload, {}
    decoded_headers = {str(key): str(value) for key, value in headers.items()}
    return base64.b64decode(encoded_payload), decoded_headers
