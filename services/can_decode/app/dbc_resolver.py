"""Which DBC to decode a session's CAN with (docs/plans/2026-11-03-recording-sessions.md, 4b;
docs/plans/2026-11-04-dbc-discovery.md, 1).

The registry knows each session's vehicle and each vehicle's DBC
(GET /api/sessions/{device}/{session}/dbc). Answers are cached per session, and
each DBC is compiled once per content hash, so the registry sees about one request
per session every POSITIVE_TTL, not one per frame.

for_session() answers one of:

- a Dbc: decode with it (the vehicle's, or the default for records with no session);
- NoDbc(reason): the session's vehicle has no DBC ("session has no vehicle",
  "vehicle has no DBC", or its DBC doesn't parse). Its CAN goes to the discovery
  topic undecoded, never through a guessed DBC. Asked again after NEGATIVE_TTL,
  since a vehicle or DBC can be assigned mid-session.
- PENDING: the registry hasn't seen the session's start yet. The message is
  dropped (recording starts a few seconds before driving); asked again after
  PENDING_TTL.

A registry that can't be reached is retried, with backoff, until it answers: the
message waits rather than being dropped or decoded with the wrong DBC. The consumer
watchdog restarts the pod if that goes on, and it resumes from the committed offset.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from cantools.database.can import Message

from .decode import load_dbc

LOG = logging.getLogger("can-decode.dbc")
POSITIVE_TTL = 300.0
NEGATIVE_TTL = 15.0
PENDING_TTL = 1.0
RETRY_MAX_S = 30.0
NOT_FOUND = "session not found"  # the registry's 404 detail before session_start is processed


@dataclass(frozen=True)
class Dbc:
    name: str
    messages: dict[int, Message]


@dataclass(frozen=True)
class NoDbc:
    reason: str


@dataclass(frozen=True)
class Missing:
    """The registry's 404: no DBC for the session, and why (its `detail`)."""

    reason: str


PENDING = NoDbc(NOT_FOUND)
Answer = Dbc | NoDbc


def fetch_from_registry(registry_url: str) -> Callable[[str, str], dict | Missing]:
    """Blocking GET of a session's DBC: the JSON body, or Missing(detail) on a 404."""

    def fetch(device_id: str, source_session: str) -> dict | Missing:
        url = "{}/api/sessions/{}/{}/dbc".format(
            registry_url.rstrip("/"), urllib.parse.quote(device_id, safe=""), urllib.parse.quote(source_session, safe="")
        )
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                try:
                    detail = json.loads(exc.read()).get("detail")
                except (ValueError, AttributeError):
                    detail = None
                return Missing(str(detail or "vehicle has no DBC"))
            raise

    return fetch


class DbcResolver:
    def __init__(self, default: Dbc, fetch: Callable[[str, str], dict | Missing] | None, clock=time.monotonic,
                 sleep: Callable[[float], Awaitable[None]] = asyncio.sleep):
        self.default = default
        self._fetch = fetch
        self._clock = clock
        self._sleep = sleep
        self._sessions: dict[tuple[str, str], tuple[float, Answer | str]] = {}  # -> (expires, NoDbc or sha)
        self._compiled: dict[str, Dbc] = {}

    async def for_session(self, device_id: str, source_session: str) -> Answer:
        if self._fetch is None or not source_session or source_session == "proto-live":
            return self.default
        key = (device_id, source_session)
        cached = self._sessions.get(key)
        now = self._clock()
        if cached is not None and cached[0] > now:
            return self._answer(cached[1])
        found = await self._resolve(device_id, source_session)
        if isinstance(found, str):
            ttl = POSITIVE_TTL
        else:
            ttl = PENDING_TTL if found == PENDING else NEGATIVE_TTL
            if cached is None or cached[1] != found:
                LOG.info("session %s:%s: %s", source_session, device_id,
                         "not registered yet; dropping its CAN" if found == PENDING
                         else f"{found.reason}; its CAN goes to discovery")
        self._sessions[key] = (now + ttl, found)
        return self._answer(found)

    def _answer(self, found: Answer | str) -> Answer:
        return self._compiled[found] if isinstance(found, str) else found

    async def _resolve(self, device_id: str, source_session: str) -> NoDbc | str:
        delay = 1.0
        while True:
            try:
                body = await asyncio.to_thread(self._fetch, device_id, source_session)
                break
            except Exception:
                LOG.warning("registry lookup failed for %s:%s; retrying in %.0fs", source_session, device_id, delay,
                            exc_info=True)
                await self._sleep(delay)
                delay = min(delay * 2, RETRY_MAX_S)
        if isinstance(body, Missing):
            return PENDING if body.reason == NOT_FOUND else NoDbc(body.reason)
        sha = body["sha256"]
        if sha not in self._compiled:
            try:
                self._compiled[sha] = Dbc(body["name"], load_dbc(body["content"]))
            except Exception:
                LOG.warning("DBC %r (%s) does not parse", body.get("name"), sha[:12], exc_info=True)
                return NoDbc(f"DBC {body.get('name')!r} does not parse")
            LOG.info("session %s:%s -> DBC %r (%d messages)", source_session, device_id, body["name"],
                     len(self._compiled[sha].messages))
        return sha
