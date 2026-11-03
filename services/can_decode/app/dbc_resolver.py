"""Which DBC to decode a session's CAN with (docs/plans/2026-11-03-recording-sessions.md, 4b).

The registry knows each session's vehicle and each vehicle's DBC
(GET /api/sessions/{device}/{session}/dbc). Answers are cached per session, and
each DBC is compiled once per content hash, so the registry sees about one request
per session every POSITIVE_TTL, not one per frame. A session with no answer yet (its
session_start not processed, no vehicle, vehicle without a DBC), the registry
being down, or a DBC that does not parse all fall back to the default DBC, and are
asked again after NEGATIVE_TTL.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass

from cantools.database.can import Message

from .decode import load_dbc

LOG = logging.getLogger("can-decode.dbc")
POSITIVE_TTL = 300.0
NEGATIVE_TTL = 15.0


@dataclass(frozen=True)
class Dbc:
    name: str
    messages: dict[int, Message]


def fetch_from_registry(registry_url: str) -> Callable[[str, str], dict | None]:
    """Blocking GET of a session's DBC: the JSON body, or None when there is none (404)."""

    def fetch(device_id: str, source_session: str) -> dict | None:
        url = "{}/api/sessions/{}/{}/dbc".format(
            registry_url.rstrip("/"), urllib.parse.quote(device_id, safe=""), urllib.parse.quote(source_session, safe="")
        )
        try:
            with urllib.request.urlopen(url, timeout=5) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            raise

    return fetch


class DbcResolver:
    def __init__(self, default: Dbc, fetch: Callable[[str, str], dict | None] | None, clock=time.monotonic):
        self.default = default
        self._fetch = fetch
        self._clock = clock
        self._sessions: dict[tuple[str, str], tuple[float, str | None]] = {}  # -> (expires, sha)
        self._compiled: dict[str, Dbc] = {}

    async def for_session(self, device_id: str, source_session: str) -> Dbc:
        if self._fetch is None or not source_session or source_session == "proto-live":
            return self.default
        key = (device_id, source_session)
        cached = self._sessions.get(key)
        now = self._clock()
        if cached is not None and cached[0] > now:
            return self._compiled.get(cached[1], self.default) if cached[1] else self.default
        sha = await self._resolve(device_id, source_session)
        self._sessions[key] = (now + (POSITIVE_TTL if sha else NEGATIVE_TTL), sha)
        return self._compiled[sha] if sha else self.default

    async def _resolve(self, device_id: str, source_session: str) -> str | None:
        try:
            body = await asyncio.to_thread(self._fetch, device_id, source_session)
        except Exception:
            LOG.warning("registry lookup failed for %s:%s; default DBC for now", source_session, device_id, exc_info=True)
            return None
        if body is None:
            return None
        sha = body["sha256"]
        if sha not in self._compiled:
            try:
                self._compiled[sha] = Dbc(body["name"], load_dbc(body["content"]))
            except Exception:
                LOG.warning("DBC %r (%s) does not parse; default DBC", body.get("name"), sha[:12], exc_info=True)
                return None
            LOG.info("session %s:%s -> DBC %r (%d messages)", source_session, device_id, body["name"], len(self._compiled[sha].messages))
        return sha
