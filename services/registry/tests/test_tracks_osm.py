"""Overpass is often busy: one retry on a gateway error."""

from types import SimpleNamespace

from app.services import osm


def test_overpass_gateway_error_is_retried_once(monkeypatch):
    calls = []

    def post(url, data, headers, timeout):
        calls.append(url)
        if len(calls) == 1:
            return SimpleNamespace(status_code=502, raise_for_status=lambda: None, json=lambda: {})
        return SimpleNamespace(status_code=200, raise_for_status=lambda: None, json=lambda: {"elements": [1]})

    monkeypatch.setattr(osm.requests, "post", post)
    monkeypatch.setattr(osm.time, "sleep", lambda s: None)
    assert osm._query(42.5, -83.0, 1000) == [1]
    assert len(calls) == 2
