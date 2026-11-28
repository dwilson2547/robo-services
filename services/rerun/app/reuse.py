"""The live services' own code, so a re-run cannot drift from what they do.

Every service names its package `app`, so each is loaded here under its own alias.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

SERVICES = Path(__file__).resolve().parents[2]


def _package(alias: str, service: str) -> None:
    root = SERVICES / service / "app"
    spec = importlib.util.spec_from_file_location(alias, root / "__init__.py", submodule_search_locations=[str(root)])
    module = importlib.util.module_from_spec(spec)
    sys.modules[alias] = module
    spec.loader.exec_module(module)


for _alias, _service in (("live_decode", "can_decode"), ("live_state", "vehicle_state"),
                         ("live_summary", "drive_summary")):
    if _alias not in sys.modules:
        _package(_alias, _service)

from live_decode.decode import Decoded, decode_record, load_dbc  # noqa: E402
from live_state.frames import FrameTable  # noqa: E402
from live_state.snapshot import build_rows  # noqa: E402
from live_state.store import COLUMNS, JSON_COLUMNS, Store  # noqa: E402
from live_summary.main import UPSERT, completeness, session_rows  # noqa: E402
from live_summary.metrics import VERSION, one_line, summarise  # noqa: E402

__all__ = ["Decoded", "decode_record", "load_dbc", "FrameTable", "build_rows", "COLUMNS", "JSON_COLUMNS", "Store",
           "UPSERT", "completeness", "session_rows", "VERSION", "one_line", "summarise"]
