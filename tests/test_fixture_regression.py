from __future__ import annotations

import io
import json
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from can_pub_sub_probe.cli import main


FIXTURES_DIR = Path(__file__).parent / "fixtures"


class FixtureRegressionTests(unittest.TestCase):
    def test_all_fixture_directories_match_expected_summaries(self) -> None:
        fixture_dirs = sorted(path for path in FIXTURES_DIR.iterdir() if path.is_dir())
        self.assertNotEqual(fixture_dirs, [])
        for fixture_dir in fixture_dirs:
            with self.subTest(fixture=fixture_dir.name):
                stdout = io.StringIO()
                with redirect_stdout(stdout):
                    exit_code = main(["run-fixture", str(fixture_dir)])
                payload = json.loads(stdout.getvalue())
                self.assertEqual(exit_code, 0, payload)
                self.assertEqual(payload["expectation_check"]["passed"], True, payload)
