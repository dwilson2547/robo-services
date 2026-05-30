# Registry Service: Patterns and Pitfalls

**Project:** robo-services  
**Last Updated:** 2026-05-30

Reference for building and deploying the `services/registry` FastAPI + React + Postgres service.
Read this before making changes to the registry or building a similar service in this stack.

---

## 1. Dockerfile: Don't `pip install .` Without Copying Source First

**Problem:** Using `pip install -e .` or `pip install .` in a Dockerfile that only copies
`pyproject.toml` (not the source tree) fails because `setuptools.backends.legacy` cannot find
the package source.

**Fix — list deps inline in the Dockerfile:**
```dockerfile
RUN pip install --no-cache-dir \
    fastapi \
    uvicorn[standard] \
    sqlalchemy \
    alembic \
    psycopg2-binary \
    pydantic-settings \
    email-validator
```

Keep this list in sync with `pyproject.toml`. This also gives better Docker layer caching since
a `pyproject.toml` change won't bust the pip layer unless the dep list actually changes.

---

## 2. pydantic `EmailStr` Requires `email-validator`

**Problem:** `from pydantic import EmailStr` imports fine, but using it in a schema raises an
`ImportError` at startup:
```
ImportError: email-validator is not installed, run `pip install pydantic[email]`
```
This causes `CrashLoopBackOff` in the cluster. The error only surfaces at runtime — the image
builds clean.

**Fix:** Add `email-validator>=2.1` to both `pyproject.toml` and the Dockerfile dep list.
Alternatively use `pydantic[email]` as a dep which pulls it transitively.

See: [docs/issues/2026_05_30_pydantic_emailstr_missing_email_validator.md](issues/2026_05_30_pydantic_emailstr_missing_email_validator.md)

---

## 3. SQLAlchemy Cascade Delete — Non-nullable FK Children

**Problem:** Deleting a `Device` that has `DeviceProfile` children fails with a 500:
```
sqlalchemy.exc.IntegrityError: NOT NULL constraint violation on device_profiles.device_id
```
SQLAlchemy's default on delete is to SET NULL on the FK — which violates a NOT NULL constraint.

**Fix:** Add `cascade="all, delete-orphan"` to the parent relationship:
```python
profiles = relationship("DeviceProfile", back_populates="device",
                        cascade="all, delete-orphan")
```

Any relationship where children have a non-nullable FK back to the parent needs this.

See: [docs/issues/2026_05_30_sqlalchemy_cascade_delete_not_null.md](issues/2026_05_30_sqlalchemy_cascade_delete_not_null.md)

---

## 4. react-leaflet 4.x Requires React ^18 (Not React 19)

**Problem:** `npm install` with React 19 and `react-leaflet@4` fails with a peer dependency
conflict. react-leaflet 4.x declares `peerDependencies: { react: "^18" }` — it does not
support React 19.

**Fix:** Pin React to `18.3.1` in `package.json`:
```json
"react": "^18.3.1",
"react-dom": "^18.3.1",
"@types/react": "^18.3.1",
"@types/react-dom": "^18.3.1"
```

See: [docs/issues/2026_05_30_react_leaflet_react19_incompatibility.md](issues/2026_05_30_react_leaflet_react19_incompatibility.md)

---

## 5. SPA Served from FastAPI — Mount Order Matters

The registry serves the Vite-built React app from FastAPI's `StaticFiles`. Route order is critical:

```python
# API routers must be registered BEFORE static files
app.include_router(users.router, prefix="/api/users")
app.include_router(devices.router, prefix="/api/devices")
app.include_router(tracks.router, prefix="/api/tracks")

# Mount SPA last — catches everything not already matched
app.mount("/", StaticFiles(directory="app/static", html=True), name="static")
```

If `StaticFiles` is mounted first, all API requests resolve to `index.html` and return 200 HTML
instead of JSON. In development, Vite's proxy (`/api` → `localhost:8000`) handles this
transparently — the bug only appears in the built Docker image.

---

## 6. Postgres 15+: Schema Permissions Must Be Granted Explicitly

**Problem:** Alembic migrations fail with a permission denied error on the `public` schema
even though the role was granted database-level privileges.

**Fix:** Postgres 15 revoked `CREATE` on the `public` schema from non-superusers by default.
The init SQL must explicitly grant it:
```sql
GRANT ALL ON SCHEMA public TO roboservices_dev;
```

The init scripts live in `cluster_config/postgres/init-<service>-dev.sql` and are applied
manually via `kubectl exec` into the postgres pod. See the existing scripts for the full
pattern (create role → create database → connect → grant schema).

---

## 7. Device Profiles Are Versioned, One Active per Device

The `DeviceProfile` model supports multiple versions per device. The invariant is:
- Only one profile per device has `active=True` at any time
- Creating a new profile via `POST /api/devices/{device_id}/profiles` auto-deactivates all
  previous versions for that device

The Flink-facing endpoint is `GET /api/devices/{device_id}/profile` (singular) — returns the
currently active profile including the `profile_json` JSONB field.

---

## 8. Flink ProfileResolver: Non-serializable Fields Must Be `transient`

`ProfileResolver` implements `java.io.Serializable` (Flink requires this for all operator
state). Fields that are not serializable — `HttpClient` and the `Map` cache — must be declared
`transient` and rebuilt lazily after deserialization:

```java
private transient HttpClient http;
private transient Map<String, CachedProfile> cache;

private void ensureInitialized() {
    if (http == null) http = HttpClient.newHttpClient();
    if (cache == null) cache = new HashMap<>();
}
```

Call `ensureInitialized()` at the top of any method that uses these fields. Failing to do this
causes a `NullPointerException` when Flink restores the operator after a checkpoint or pod restart.

See also: [flink_patterns_and_pitfalls.md](flink_patterns_and_pitfalls.md)

---

## 9. Track GeoJSON Import

Tracks are stored as raw GeoJSON (JSONB column). The UI supports direct file import — users
select a `.geojson` file and the geometry is stored as-is. No PostGIS required.

The `track-poly-poc/` directory contains geopolygons for 7 tracks (Monaco, Interlagos, IMS,
Las Vegas, Nürburgring, Snaefell, Utah Motorsports) that can be imported directly through the
Tracks UI.

Start/finish lines are stored as a separate GeoJSON `Point` feature (also imported from file).
