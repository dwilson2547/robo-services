# Overpass API Usage Reference

Notes on the public Overpass API as used in this project.

---

## Endpoint

```
https://overpass-api.de/api/interpreter
```

Always use `POST`, not `GET`, for queries longer than ~200 chars.

---

## Required headers

```python
headers = {"User-Agent": "track-poly-poc/0.1.0"}
```

**The public endpoint rejects requests with no User-Agent (HTTP 406).** This bit us early on.
`overpy` does not set a User-Agent — use `requests.post()` directly.

---

## Rate limits

The public endpoint enforces rate limits:
- Sustained querying hits **429 Too Many Requests** after ~5–6 sequential queries
- Recovers quickly — a 10–15 second sleep between batches is sufficient
- Timeout errors (504) can occur during high load; retry after a short pause

For batch runs (e.g. `python main.py` with many tracks), add `time.sleep(2)` between
queries if hitting limits. The current `main.py` does not do this automatically.

---

## Useful query patterns

### Fetch a relation by numeric ID (bypasses name/type search)

```
[out:json][timeout:60];
relation(188240);
out body;
>;
out skel qt;
```

### Fetch specific ways by ID

```
[out:json][timeout:60];
way(id:476963551,343896481,343896482);
out geom;
```

### Bounding-box investigation query

```
[out:json][timeout:30];
(
  way[highway](40.58,-112.39,40.59,-112.38);
  way[leisure](40.58,-112.39,40.59,-112.38);
  relation[type="circuit"](40.58,-112.39,40.59,-112.38);
  relation[type="route"](40.58,-112.39,40.59,-112.38);
);
out body;
>;
out skel qt;
```

### Named circuit with `out geom` (inline node coordinates, no second query needed)

```
[out:json][timeout:60];
way["highway"="raceway"]["name"~"^Indianapolis Motor Speedway"];
out geom;
```

---

## Output formats

| Suffix | Meaning |
|---|---|
| `out body` | Way/relation tags + member IDs (no coordinates) |
| `out geom` | Way geometry inline (coordinates embedded per way) |
| `>; out skel qt` | Recursively resolve referenced nodes — needed with `out body` |

For relations, use `out body; >; out skel qt;` because relation members reference node IDs
that need to be resolved separately. For standalone ways, `out geom;` is simpler.
