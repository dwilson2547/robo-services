"""Apply a DBC's signal roles (registry dbcs.roles) to decoded signal values.

The roles and their validation live in the registry (services/registry/app/services/roles.py);
the conversions here must match the units it accepts.
"""

from __future__ import annotations

from typing import Any

ROLE_COLUMNS = (
    "speed_kph", "rpm", "throttle_pct", "brake_on", "brake_kpa", "gear", "steering_deg",
    "odometer_km", "coolant_c", "oil_temp_c", "prndl",
)

_UNITS = {
    "mph": lambda v: v * 1.609344,
    "mi": lambda v: v * 1.609344,
    "degF": lambda v: (v - 32.0) * 5.0 / 9.0,
    "psi": lambda v: v * 6.894757,
}


def role_value(spec: dict[str, Any], signals: dict[str, float]) -> float | None:
    if "value" in spec:
        return float(spec["value"])
    raw = signals.get(spec.get("signal"))
    if raw is None:
        return None
    value = float(raw)
    if "unit" in spec:
        return _UNITS[spec["unit"]](value)
    return value * float(spec.get("scale", 1.0)) + float(spec.get("offset", 0.0))


def selector(spec: dict[str, Any], signals: dict[str, float]) -> str | None:
    """prndl: the signal's code through the role's map (P/R/N/D/L); None if unmapped."""
    raw = signals.get(spec.get("signal"))
    if raw is None:
        return None
    return (spec.get("map") or {}).get(str(int(raw)))


def apply_roles(roles: dict[str, dict], signals: dict[str, float]) -> dict[str, Any]:
    """Role columns for one snapshot; None where the DBC maps nothing or the signal is absent."""
    out: dict[str, Any] = {}
    for column in ROLE_COLUMNS:
        spec = roles.get(column)
        if column == "prndl":
            out[column] = selector(spec, signals) if spec else None
            continue
        value = role_value(spec, signals) if spec else None
        if column == "brake_on" and value is not None:
            value = value != 0
        out[column] = value
    return out
