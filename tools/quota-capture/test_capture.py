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

    def test_atomic_quota_acquisitions_preserve_order_under_concurrency(self):
        with tempfile.TemporaryDirectory() as directory:
            report = {"quota": {"synthetic-pool": {"remaining_fraction": 0.7, "reset_time": "2026-11-12T12:00:00Z"}}}
            target = capture.capture("antigravity", directory, report, "2026-11-11T12:00:00Z")
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                results = list(pool.map(lambda _: capture.capture("antigravity", directory, report, "2026-11-11T13:00:00Z"), range(20)))
            self.assertTrue(all(path == results[0] for path in results))
            self.assertNotEqual(target, results[0])
            self.assertEqual("2026-11-11T12:00:00Z", json.loads(target.read_text())["captured_at"])
            self.assertEqual(2, len(list(pathlib.Path(directory).iterdir())))

    def test_wrong_types_cannot_smuggle_private_objects_through_allowlisted_fields(self):
        for field in ("used_percentage", "resets_at"):
            for value in ({"prompt": "PRIVATE_MARKER"}, ["PRIVATE_MARKER"], "PRIVATE_MARKER", True):
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    capture.sanitize("claude", {"rate_limits": {"seven_day": {field: value}}})
        for field in ("remaining_fraction", "remaining_amount", "reset_time", "reset_in_seconds", "disabled"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                capture.sanitize("antigravity", {"quota": {"pool": {field: {"prompt": "PRIVATE_MARKER"}}}})
        for value in (float("inf"), float("nan"), -1, 101):
            with self.assertRaises(ValueError):
                capture.sanitize("claude", {"rate_limits": {"seven_day": {"used_percentage": value}}})

    def test_stream_reads_are_bounded_before_allocating_a_full_record(self):
        from unittest.mock import patch
        import io
        class BoundedInput(io.BytesIO):
            def readline(self, size=-1):
                self.assertion(size)
                return super().readline(size)
        source = BoundedInput(b"x" * (capture.MAX_BYTES + 10))
        source.assertion = lambda size: self.assertEqual(capture.MAX_BYTES + 1, size)
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(sys, "argv", ["capture.py", "antigravity", directory, "--stream"]), \
                    patch.object(sys, "stdin", type("Input", (), {"buffer": source})()), self.assertRaises(ValueError):
                capture.main()
            self.assertEqual(capture.MAX_BYTES + 1, source.tell())
            self.assertEqual([], list(pathlib.Path(directory).iterdir()))

    def test_done_replays_keep_the_original_completion_time(self):
        step = {"event": "step_update", "step_update": {"conversation_id": "synthetic", "step_index": 1,
                "state": "DONE", "usage": {"input_tokens": 100, "output_tokens": 20, "total_tokens": 120}}}
        with tempfile.TemporaryDirectory() as directory:
            first = capture.capture("antigravity", directory, step, "2026-11-11T12:00:00Z")
            again = capture.capture("antigravity", directory, step, "2026-11-11T13:00:00Z")
            self.assertEqual(first, again)
            self.assertEqual("2026-11-11T12:00:00Z", json.loads(first.read_text())["captured_at"])

    def test_optional_null_quota_fields_remain_unavailable(self):
        self.assertEqual({"rate_limits": {"seven_day": {}}}, capture.sanitize("claude", {
            "rate_limits": {"seven_day": {"used_percentage": None, "resets_at": None}}}))
        self.assertEqual({"quota": {"pool": {}}}, capture.sanitize("antigravity", {
            "quota": {"pool": {"remaining_fraction": None, "remaining_amount": None,
                               "reset_time": None, "reset_in_seconds": None, "disabled": None}}}))

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
