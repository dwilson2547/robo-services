"""Session facts from the registry: vehicle, account, and the DBC's signal roles."""

from __future__ import annotations

import asyncio
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field

TTL_S = 300.0


@dataclass
class SessionInfo:
    vehicle_id: int | None = None
    user_sub: str | None = None
    roles: dict[str, dict] = field(default_factory=dict)


def _get(url: str) -> dict | None:
    """JSON at url, None on 404; raises on anything else."""
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise


def fetch_from_registry(base_url: str) -> Callable[[str, str], SessionInfo | None]:
    def fetch(device_id: str, source_session: str) -> SessionInfo | None:
        path = f"{base_url.rstrip('/')}/api/sessions/{urllib.parse.quote(device_id)}/{urllib.parse.quote(source_session)}"
        session = _get(path)
        if session is None:
            return None  # session_start not seen yet
        dbc = _get(path + "/dbc")  # 404: no vehicle, or a vehicle without a DBC
        return SessionInfo(session.get("vehicle_id"), session.get("user_sub"), (dbc or {}).get("roles") or {})

    return fetch


class Registry:
    """Cached per session; an unknown session is asked again on every call."""

    def __init__(self, fetch: Callable[[str, str], SessionInfo | None] | None,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._fetch, self._clock = fetch, clock
        self._cache: dict[tuple[str, str], tuple[float, SessionInfo]] = {}

    async def info(self, device_id: str, source_session: str) -> SessionInfo | None:
        if self._fetch is None:
            return SessionInfo()
        key = (device_id, source_session)
        cached = self._cache.get(key)
        if cached and cached[0] > self._clock():
            return cached[1]
        info = await asyncio.to_thread(self._fetch, device_id, source_session)
        if info is not None:
            self._cache[key] = (self._clock() + TTL_S, info)
        return info
