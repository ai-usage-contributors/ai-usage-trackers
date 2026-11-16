"""Generator contract and clock tests; no credentials, network or vendor process."""
import copy
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("synthetic_generator", Path(__file__).with_name("generate.py"))
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)
NOW = datetime(2026, 11, 13, 12, tzinfo=timezone.utc)


class GeneratorTests(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(Path(__file__).with_name("config.json").read_text())

    def test_default_output_matches_fixtures_consumed_by_real_kotlin_parsers(self):
        files = generator.generate(self.config, NOW, "synthetic-fixture")
        self.assertEqual(3, len(files))
        for name, text in files.items():
            expected = ROOT / "src/test/resources/synthetic-usage" / name
            self.assertEqual(expected.read_text(), text, name)

    def test_midnight_fractional_ages_offsets_and_expired_reset(self):
        now = datetime.fromisoformat("2026-11-14T01:00:00+01:00")
        self.config["claude"]["requests"] = [
            {"age_minutes": 0, "input_tokens": 2},
            {"age_minutes": 0.5, "input_tokens": 3}]
        self.config["claude"]["quotas"] = {
            "weekly": {"used_percent": None, "reset_in_minutes": -1, "age_minutes": 20}}
        text = generator.generate(self.config, now, "boundary")["claude/demo.jsonl"]
        rows = [json.loads(line) for line in text.splitlines()]
        self.assertEqual("2026-11-13T23:59:30Z", rows[0]["timestamp"])
        self.assertEqual("2026-11-14T00:00:00Z", rows[1]["timestamp"])
        self.assertEqual("2026-11-13T23:40:00Z", rows[2]["captured_at"])
        window = rows[2]["report"]["rate_limits"]["seven_day"]
        self.assertNotIn("used_percentage", window)
        self.assertEqual(int(now.timestamp()) - 60, window["resets_at"])

    def test_bad_values_are_rejected_before_writing(self):
        for values in ({"input_tokens": -1}, {"input_tokens": True},
                       {"input_tokens": 1.5}, {"input_tokens": 1, "cached_input_tokens": 2},
                       {"output_tokens": 1, "reasoning_output_tokens": 2},
                       {"age_minutes": -1}, {"total_tokens": 100}, {"typo": 2}):
            config = copy.deepcopy(self.config)
            config["codex"]["requests"] = [{"age_minutes": 0, **values}]
            with self.subTest(values=values), self.assertRaises(ValueError):
                generator.generate(config, NOW, "invalid")
        self.config["codex"]["quotas"]["weekly"]["used_percent"] = float("nan")
        with self.assertRaises(ValueError):
            generator.generate(self.config, NOW, "invalid")
        with self.assertRaises(ValueError):
            generator.generate({}, NOW.replace(tzinfo=None), "invalid")

    def test_empty_inputs_and_omitted_quota_windows_are_supported(self):
        for vendor in generator.VENDORS:
            self.config[vendor] = {"requests": [], "quotas": {}}
        files = generator.generate(self.config, NOW, "empty")
        self.assertEqual(1, len(files["codex/sessions/rollout-demo.jsonl"].splitlines()))
        self.assertEqual("", files["claude/demo.jsonl"])
        self.assertEqual("", files["antigravity/demo.jsonl"])

    def test_existing_directory_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            existing = output / "keep.txt"
            existing.write_text("keep")
            with self.assertRaises(FileExistsError):
                generator.write_dataset(output, {"keep.txt": "replace"})
            self.assertEqual("keep", existing.read_text())

    def test_cli_generates_all_sources_in_requested_new_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "demo"
            completed = subprocess.run([sys.executable, str(Path(__file__).with_name("generate.py")),
                                        "--output", str(output)], capture_output=True, text=True, check=True)
            self.assertEqual(3, len(list(output.rglob("*.jsonl"))))
            for vendor in generator.VENDORS:
                self.assertIn(str(output.resolve() / vendor), completed.stdout)


if __name__ == "__main__":
    unittest.main()
