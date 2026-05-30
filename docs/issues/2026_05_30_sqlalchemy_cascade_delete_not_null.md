# Issue: SQLAlchemy DELETE 500 — NOT NULL violation on child FK

**Date:** 2026-05-30  
**Service:** robo-services-registry  
**Severity:** Medium — all other endpoints worked; delete was broken

---

## Symptom

`DELETE /api/devices/{device_id}` returned HTTP 500:

```
sqlalchemy.exc.IntegrityError: (psycopg2.errors.NotNullViolation)
null value in column "device_id" of relation "device_profiles" violates not-null constraint
```

Device had associated `DeviceProfile` rows. All other CRUD endpoints worked normally.

## Root Cause

SQLAlchemy's default relationship behavior on parent delete is to emit an `UPDATE` that sets
the child FK column to `NULL`. The `device_profiles.device_id` column has a `NOT NULL`
constraint, so this update fails.

The `Device.profiles` relationship was defined without cascade options:
```python
# Missing cascade — SQLAlchemy tries to null the FK
profiles = relationship("DeviceProfile", back_populates="device")
```

## Resolution

Added `cascade="all, delete-orphan"` to the `Device.profiles` relationship in `models.py`:
```python
profiles = relationship("DeviceProfile", back_populates="device",
                        cascade="all, delete-orphan")
```

This tells SQLAlchemy to `DELETE` child rows when the parent is deleted, rather than nulling
the FK. Rebuilt and deployed as `20260530-registry-v3`.

## Prevention

Any relationship where the child has a non-nullable FK back to the parent requires
`cascade="all, delete-orphan"`. Apply this proactively when designing parent-child ORM
models with NOT NULL FK constraints.
