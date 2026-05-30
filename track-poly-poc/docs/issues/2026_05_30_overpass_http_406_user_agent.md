# overpy HTTP 406 rejection causes all Overpass queries to fail silently

**Date:** 2026-05-30  
**Component:** `main.py` — initial Overpass query implementation  
**Severity:** High — blocks all OSM data retrieval; no fallback

---

## Observed symptom

All Overpass API queries via `overpy` returned HTTP 406 Not Acceptable. No data was returned
and the error was not immediately obvious since `overpy` raises a generic exception rather than
surfacing the HTTP status clearly.

---

## Root cause

### overpy sends no User-Agent header

`overpy`'s `API.query()` uses Python's `urllib.request.urlopen()` with no `User-Agent` header.
The public `overpass-api.de` endpoint rejects requests that omit a User-Agent with HTTP 406.
This policy was added to the public endpoint to block automated scrapers.

```python
# overpy internals — no User-Agent set
response = urllib.request.urlopen(request)  # 406 if no User-Agent
```

---

## Troubleshooting steps taken

1. **Ran first query with overpy** — received HTTP 406, confirmed by checking raw response in a
   browser with no User-Agent (curl --user-agent "").
2. **Checked overpy source** — confirmed `urlopen` call with no header customization.
3. **Tested `requests.post()` with custom User-Agent** — returned 200 and valid JSON.

---

## Fix

### `main.py` — replace overpy with raw requests.post()

Dropped `overpy` entirely. All queries now use `requests.post()` with a `User-Agent` header.

```python
HEADERS = {"User-Agent": "track-poly-poc/0.1.0"}

def _post(query: str) -> requests.Response:
    response = requests.post(
        OVERPASS_ENDPOINT,
        data=query.encode("utf-8"),
        headers=HEADERS,
        timeout=90,
    )
    response.raise_for_status()
    return response
```

---

## Files changed

- `main.py` — `_post`, removed overpy dependency
- `pyproject.toml` — removed `overpy` dep, kept `requests`
