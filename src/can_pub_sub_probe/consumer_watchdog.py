"""Exit a consumer-group process whose consumer has silently stopped.

apache-iggy 0.8's consume_messages() does not recover when the Iggy server
restarts: the client reconnects, but the server has dropped its group membership
and session, and the consumer then polls nothing, forever, without raising. A
restarted process rejoins the group and resumes from the committed offset, so the
fix is to notice and exit non-zero for Kubernetes to restart the pod.

The SDK cannot report group membership, so this watches progress instead: the
topic's message count against the offset the process last consumed.

- The topic grew, and the consumed offset has not moved for `stall_after`
  seconds since: stalled. An idle topic never trips it.
- Reading the topic failed or timed out `max_failures` times in a row: the
  connection is gone.

A callback stuck retrying (database or publish down) also counts as stalled; a
restart is a fine answer to that too, since it resumes from the committed offset.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Awaitable, Callable

LOG = logging.getLogger("consumer-watchdog")


class ProgressWatchdog:
    """Pure stall detection; feed it observations, it says when to give up."""

    def __init__(self, stall_after: float, max_failures: int) -> None:
        self.stall_after = stall_after
        self.max_failures = max_failures
        self._offset: int | None = None
        self._count_at_progress: int | None = None
        self._behind_since: float | None = None
        self._failures = 0

    def observe(self, messages_count: int, consumed_offset: int | None, now: float) -> str | None:
        """Record one reading; returns why the consumer is stalled, or None."""
        self._failures = 0
        if self._count_at_progress is None or consumed_offset != self._offset:
            self._offset = consumed_offset
            self._count_at_progress = messages_count
            self._behind_since = None
            return None
        if messages_count <= self._count_at_progress:
            return None
        if self._behind_since is None:
            self._behind_since = now
        if now - self._behind_since >= self.stall_after:
            return (
                f"topic grew from {self._count_at_progress} to {messages_count} messages "
                f"but the consumed offset stayed at {consumed_offset} for {now - self._behind_since:.0f}s"
            )
        return None

    def failed(self) -> str | None:
        """Record a failed reading; returns why the consumer is stalled, or None."""
        self._failures += 1
        if self._failures >= self.max_failures:
            return f"reading the topic failed {self._failures} times in a row"
        return None


async def watch(
    read_count: Callable[[], Awaitable[int]],
    consumed_offset: Callable[[], int | None],
    on_stall: Callable[[str], None],
    stop: asyncio.Event,
    interval: float = 30.0,
    stall_after: float = 120.0,
    max_failures: int = 4,
    clock: Callable[[], float] = time.monotonic,
    on_reading: Callable[[int, int | None], None] | None = None,
) -> None:
    """Check every `interval` seconds until `stop` is set; call `on_stall` once and return on a stall.

    `on_reading(messages_count, consumed_offset)` sees each successful reading (backlog metrics).
    """
    dog = ProgressWatchdog(stall_after, max_failures)
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), interval)
            return
        except asyncio.TimeoutError:
            pass
        try:
            count = await asyncio.wait_for(read_count(), timeout=max(interval / 2, 1.0))
        except Exception:
            LOG.warning("could not read the topic's message count", exc_info=True)
            reason = dog.failed()
        else:
            offset = consumed_offset()
            if on_reading is not None:
                on_reading(count, offset)
            reason = dog.observe(count, offset, clock())
        if reason:
            on_stall(reason)
            return


def exit_for_restart(reason: str) -> None:
    """Default on_stall: log and exit hard; the Rust runtime may not shut down cleanly."""
    LOG.error("consumer stalled (%s); exiting for a restart from the committed offset", reason)
    logging.shutdown()
    os._exit(1)


def topic_count_reader(client, stream: str, topic: str) -> Callable[[], Awaitable[int]]:
    """read_count for an IggyClient topic (messages_count is a property in apache-iggy 0.8)."""

    async def read() -> int:
        details = await client.get_topic(stream, topic)
        if details is None:
            raise RuntimeError(f"topic {stream}/{topic} not found")
        return int(details.messages_count)

    return read
