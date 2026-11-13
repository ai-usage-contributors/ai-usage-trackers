#!/usr/bin/env python3
"""Sanitize documented vendor callback/stream records; never starts an agent."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import sys
import tempfile

MAX_BYTES = 1024 * 1024


def sanitize(vendor, report):
    if vendor == "claude":
        limits = report.get("rate_limits")
        if not isinstance(limits, dict):
            return None
        cleaned = {}
        for key in ("five_hour", "seven_day"):
            value = limits.get(key)
            if isinstance(value, dict):
                cleaned[key] = {field: value[field] for field in ("used_percentage", "resets_at") if field in value}
        return {"rate_limits": cleaned} if cleaned else None
    if report.get("event") == "step_update":
        step = report.get("step_update", {})
        if step.get("state") != "DONE" or not isinstance(step.get("usage"), dict):
            return None
        clean_step = {field: step[field] for field in ("conversation_id", "step_index", "state") if field in step}
        clean_step["usage"] = {field: step["usage"][field] for field in
                              ("input_tokens", "output_tokens", "thinking_tokens", "cache_read_tokens", "total_tokens")
                              if field in step["usage"]}
        return {"event": "step_update", "step_update": clean_step}
    quotas = report.get("quota")
    if not isinstance(quotas, dict):
        return None
    fields = ("remaining_fraction", "remaining_amount", "reset_time", "reset_in_seconds", "disabled")
    return {"quota": {key: {f: value[f] for f in fields if f in value}
                      for key, value in quotas.items() if isinstance(value, dict)}}


def capture(vendor, directory, report, now=None):
    cleaned = sanitize(vendor, report)
    if cleaned is None:
        return None
    # Content addressing keeps a repeated cached callback's original capture time.
    canonical = json.dumps(cleaned, sort_keys=True, separators=(",", ":"), allow_nan=False)
    identity = hashlib.sha256((vendor + canonical).encode("utf-8")).hexdigest()
    target = pathlib.Path(directory) / (identity + ".jsonl")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return target
    envelope = {"format": "usage-capture-v1", "vendor": vendor,
                "captured_at": now or datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
                "report": cleaned}
    data = (json.dumps(envelope, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ValueError("Sanitized report exceeds 1 MiB")
    fd, temporary = tempfile.mkstemp(prefix=".capture-", suffix=".tmp", dir=target.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        # Hard-link publication is atomic and cannot replace a concurrent earlier capture.
        try:
            os.link(temporary, target)
        except FileExistsError:
            pass
    finally:
        os.unlink(temporary)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vendor", choices=("claude", "antigravity"))
    parser.add_argument("directory")
    parser.add_argument("--stream", action="store_true", help="Read NDJSON step events instead of one status-line JSON object")
    args = parser.parse_args()
    if args.stream:
        for line in sys.stdin.buffer:
            if len(line) > MAX_BYTES:
                raise ValueError("Input record exceeds 1 MiB")
            capture(args.vendor, args.directory, json.loads(line))
    else:
        data = sys.stdin.buffer.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError("Input exceeds 1 MiB")
        capture(args.vendor, args.directory, json.loads(data))


if __name__ == "__main__":
    main()
