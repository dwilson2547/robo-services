"""Signal roles: a DBC's own signal names mapped onto fixed, vehicle-independent
roles in fixed units (docs/plans/2026-11-03-trip-pipelines.md §2).

A role's unit is in its name (`speed_kph`, `coolant_c`). Each entry maps either a
decoded signal, optionally converted (`scale` then `offset`, or a known `unit`
the signal is in), or a constant `value` (e.g. `cylinders`). `prndl` is the
selector position: its signal's codes map to letters (`map`, code -> P/R/N/D/L);
codes left out of the map read as unknown. Consumers treat a
role a DBC does not map as "not available".

The set grows when a consumer needs a new role; the vehicle-state service applies
the same conversions (keep the two in step).
"""

from __future__ import annotations

import re
from typing import Any

ROLES = {
    "speed_kph", "rpm", "throttle_pct", "brake_on", "brake_kpa", "gear", "steering_deg",
    "odometer_km", "coolant_c", "oil_temp_c", "injector_pw_us", "cylinders", "prndl",
}
SELECTOR = {"P", "R", "N", "D", "L"}

# unit a signal may be in -> the role unit it converts to
UNITS = {"mph": "kph", "degF": "c", "psi": "kpa", "mi": "km"}

_SIGNAL = re.compile(r"^\s*SG_\s+(\w+)", re.MULTILINE)


def dbc_signals(content: str) -> set[str]:
    return set(_SIGNAL.findall(content))


def validate_roles(roles: dict[str, Any], content: str) -> list[str]:
    """Problems with `roles` for a DBC with `content`; empty when valid."""
    problems: list[str] = []
    signals = dbc_signals(content)
    for role, spec in roles.items():
        if role not in ROLES:
            problems.append(f"{role}: not a known role ({', '.join(sorted(ROLES))})")
            continue
        if not isinstance(spec, dict):
            problems.append(f"{role}: must be an object")
            continue
        unknown = set(spec) - {"signal", "value", "scale", "offset", "unit", "map"}
        if unknown:
            problems.append(f"{role}: unknown keys {sorted(unknown)}")
        has_signal, has_value = "signal" in spec, "value" in spec
        if has_signal == has_value:
            problems.append(f"{role}: give exactly one of signal or value")
            continue
        if has_value:
            if not isinstance(spec["value"], (int, float)) or isinstance(spec["value"], bool):
                problems.append(f"{role}: value must be a number")
            if {"scale", "offset", "unit"} & set(spec):
                problems.append(f"{role}: a constant value takes no conversion")
            continue
        if spec["signal"] not in signals:
            problems.append(f"{role}: signal {spec['signal']!r} is not in this DBC")
        if role == "prndl":
            codes = spec.get("map")
            if not isinstance(codes, dict) or not codes:
                problems.append("prndl: needs a map of signal codes to P/R/N/D/L")
            elif any(not str(k).lstrip("-").isdigit() or v not in SELECTOR for k, v in codes.items()):
                problems.append("prndl: map keys are integer codes, values one of P/R/N/D/L")
            if {"scale", "offset", "unit"} & set(spec):
                problems.append("prndl: takes a map, not a conversion")
            continue
        if "map" in spec:
            problems.append(f"{role}: only prndl takes a map")
        for key in ("scale", "offset"):
            if key in spec and (not isinstance(spec[key], (int, float)) or isinstance(spec[key], bool)):
                problems.append(f"{role}: {key} must be a number")
        if "unit" in spec:
            if spec["unit"] not in UNITS:
                problems.append(f"{role}: unit must be one of {sorted(UNITS)}")
            elif not role.endswith("_" + UNITS[spec["unit"]]):
                problems.append(f"{role}: a {spec['unit']} signal does not convert to this role's unit")
            if {"scale", "offset"} & set(spec):
                problems.append(f"{role}: give unit or scale/offset, not both")
    return problems
