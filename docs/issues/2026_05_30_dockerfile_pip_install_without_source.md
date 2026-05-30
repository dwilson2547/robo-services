# Issue: Dockerfile `pip install .` fails without source tree

**Date:** 2026-05-30  
**Service:** robo-services-registry  
**Severity:** Medium — blocked Docker build

---

## Symptom

`docker build` failed during the `pip install -e .` step:

```
ERROR: Could not build wheels for robo-services-registry, which is required to install
pyproject.toml-based projects
Backend operation failed: setuptools.backends.legacy:build_wheel
```

Only `pyproject.toml` had been copied into the image at that stage (standard layer-caching
practice). The source files (`app/`) had not been copied yet.

## Root Cause

`pip install .` with a `pyproject.toml` build backend requires the full source tree to be
present — it needs to find the package source to build a wheel. Copying only `pyproject.toml`
before running pip install is not sufficient.

## Resolution

Switched to listing dependencies inline in the Dockerfile `RUN pip install` step rather than
installing the package itself. This is preferred for services that are not published to PyPI
and don't need editable installs:

```dockerfile
COPY pyproject.toml .
RUN pip install --no-cache-dir \
    fastapi \
    uvicorn[standard] \
    sqlalchemy \
    alembic \
    psycopg2-binary \
    pydantic-settings \
    email-validator>=2.1

COPY app/ app/
COPY migrations/ migrations/
COPY alembic.ini .
```

## Prevention

For Docker builds: either copy the full source tree before `pip install .`, or list deps
explicitly. The inline approach is better for layer caching — the pip layer only rebuilds
when the `RUN pip install` line changes, not when any source file changes. Keep the inline
list in sync with `pyproject.toml`.
