# Issue: pydantic EmailStr CrashLoopBackOff — missing email-validator

**Date:** 2026-05-30  
**Service:** robo-services-registry  
**Severity:** High — pod never became Ready

---

## Symptom

Registry pod entered `CrashLoopBackOff` immediately after first deploy. Logs showed:

```
ImportError: email-validator is not installed, run `pip install pydantic[email]`
```

The Docker image built successfully with zero errors. The crash only occurred at runtime when
FastAPI imported the schemas module containing `EmailStr`.

## Root Cause

`pydantic.EmailStr` is defined in pydantic's core but requires the `email-validator` package
at runtime to perform actual validation. The registry `pyproject.toml` and Dockerfile dep list
did not include it. The build passes because the import of `EmailStr` itself does not trigger
validation — only schema instantiation or the `EmailStr` type annotation being evaluated does.

## Resolution

Added `email-validator>=2.1` to:
1. `services/registry/pyproject.toml` under `[project].dependencies`
2. The inline `pip install` list in `services/registry/Dockerfile`

Rebuilt image as `20260530-registry-v2`, pushed, bumped tag in `values.yaml`.

## Prevention

Any pydantic schema using `EmailStr` requires `email-validator` in the dep list.
Alternatively, declare `pydantic[email]` as the dependency which pulls it transitively.
Add a smoke test that imports all schema modules as part of the CI build step to catch
this class of error before deploy.
