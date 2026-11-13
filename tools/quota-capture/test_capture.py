"""Synthetic contract tests; no vendor process, credentials or network."""
import concurrent.futures
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("capture", pathlib.Path(__file__).with_name("capture.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class CaptureTests(unittest.TestCase):
    def test_claude_removes_private_fields_and_context(self):
        report = {"email": "synthetic@example.invalid", "context_window": {"used_percentage": 99},
                  "rate_limits": {"seven_day": {"used_percentage": 20, "resets_at": 1794561600, "secret": "excluded"}}}
        self.assertEqual({"rate_limits": {"seven_day": {"used_percentage": 20, "resets_at": 1794561600}}},
                         capture.sanitize("claude", report))

    def test_steps_exclude_partial_and_aggregate_totals_and_response_text(self):
        step = {"event": "step_update", "step_update": {"conversation_id": "synthetic", "step_index": 3,
                "state": "ACTIVE", "text_delta": "private response", "usage": {"input_tokens": 100,
                "output_tokens": 20, "thinking_tokens": 10, "cache_read_tokens": 40, "total_tokens": 120, "private": "excluded"}}}
        self.assertIsNone(capture.sanitize("antigravity", step))
        step["step_update"]["state"] = "DONE"
        result = capture.sanitize("antigravity", step)
        self.assertEqual(120, result["step_update"]["usage"]["total_tokens"])
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn("text_delta", result["step_update"])
        self.assertIsNone(capture.sanitize("antigravity", {"event": "result", "result": {"usage": {"total_tokens": 999}}}))

    def test_atomic_cached_reports_keep_first_capture_time_under_concurrency(self):
        with tempfile.TemporaryDirectory() as directory:
            report = {"quota": {"synthetic-pool": {"remaining_fraction": 0.7, "reset_time": "2026-11-12T12:00:00Z"}}}
            target = capture.capture("antigravity", directory, report, "2026-11-11T12:00:00Z")
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                results = list(pool.map(lambda _: capture.capture("antigravity", directory, report, "2026-11-11T13:00:00Z"), range(20)))
            self.assertTrue(all(path == target for path in results))
            self.assertEqual("2026-11-11T12:00:00Z", json.loads(target.read_text())["captured_at"])
            self.assertEqual([target], list(pathlib.Path(directory).iterdir()))

    def test_cli_stream_writes_only_sanitized_done_record(self):
        with tempfile.TemporaryDirectory() as directory:
            step = {"event": "step_update", "step_update": {"conversation_id": "synthetic", "step_index": 1,
                    "state": "DONE", "usage": {"input_tokens": 100, "output_tokens": 20, "total_tokens": 120}}}
            data = json.dumps({"event": "init", "init": {"cwd": "private"}}) + "\n" + json.dumps(step) + "\n"
            result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name("capture.py")), "antigravity", directory, "--stream"], input=data, text=True, capture_output=True, check=True)
            self.assertEqual("", result.stdout)
            files = list(pathlib.Path(directory).glob("*.jsonl"))
            self.assertEqual(1, len(files))
            self.assertNotIn("private", files[0].read_text())


if __name__ == "__main__":
    unittest.main()
