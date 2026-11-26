#!/usr/bin/env python3
"""Experimental Claude/Antigravity capture; subscription validation pending. Never starts an agent."""
import argparse
import datetime
import hashlib
import json
import math
import os
import pathlib
import sys
import tempfile

MAX_BYTES = 1024 * 1024
UTC_OFFSET = "+00:00"


def utc_stamp(value):
    return value.astimezone(datetime.timezone.utc).isoformat().replace(UTC_OFFSET, "Z")


def numeric(value, name, maximum=None, integer=False):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be numeric")
    if not math.isfinite(value) or value < 0 or (maximum is not None and value > maximum):
        raise ValueError(f"Invalid {name}")
    if integer and not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def identity(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 512 or any(ord(c) < 32 or 127 <= ord(c) < 160 for c in value):
        raise ValueError("Expected a printable identifier of 1-512 characters")
    return value


def quota_fields(value, vendor):
    if vendor == "claude":
        fields = (("used_percentage", 100, False), ("resets_at", 253402300799, True))
    else:
        fields = (("remaining_fraction", 1, False), ("remaining_amount", 9223372036854775807, False),
                  ("reset_in_seconds", 9223372036854775807, False))
    result = {field: numeric(value[field], field, maximum, integer=integer)
              for field, maximum, integer in fields if value.get(field) is not None}
    if vendor != "claude":
        copy_quota_status(value, result)
    return result


def copy_quota_status(value, result):
    disabled = value.get("disabled")
    if disabled is not None:
        if not isinstance(disabled, bool):
            raise ValueError("disabled must be boolean")
        result["disabled"] = disabled
    text = value.get("reset_time")
    if text is not None:
        if not isinstance(text, str) or len(text) > 64:
            raise ValueError("reset_time must be a timestamp")
        parsed = datetime.datetime.fromisoformat(text.replace("Z", UTC_OFFSET))
        if parsed.tzinfo is None:
            raise ValueError("reset_time requires a time zone")
        result["reset_time"] = utc_stamp(parsed)


def sanitize(vendor, report):
    if not isinstance(report, dict):
        raise ValueError("Expected a JSON object")
    if vendor == "claude":
        return sanitize_claude(report)
    if report.get("event") == "step_update":
        return sanitize_step(report)
    quotas = report.get("quota")
    if not isinstance(quotas, dict):
        return None
    return {"quota": {identity(key): quota_fields(value, vendor)
                      for key, value in quotas.items() if isinstance(value, dict)}}


def sanitize_claude(report):
    limits = report.get("rate_limits")
    if not isinstance(limits, dict):
        return None
    cleaned = {key: quota_fields(limits[key], "claude") for key in ("five_hour", "seven_day")
               if isinstance(limits.get(key), dict)}
    return {"rate_limits": cleaned} if cleaned else None


def sanitize_step(report):
    step = report.get("step_update", {})
    if not isinstance(step, dict):
        raise ValueError("Expected a step object")
    if step.get("state") != "DONE" or not isinstance(step.get("usage"), dict):
        return None
    clean_step = {"conversation_id": identity(step.get("conversation_id")),
                  "step_index": numeric(step.get("step_index"), "step_index", 9223372036854775807, integer=True),
                  "state": "DONE"}
    clean_step["usage"] = {field: numeric(step["usage"][field], field, 9223372036854775807, integer=True) for field in
                          ("input_tokens", "output_tokens", "thinking_tokens", "cache_read_tokens", "total_tokens")
                          if field in step["usage"]}
    return {"event": "step_update", "step_update": clean_step}


def capture(vendor, directory, report, now=None):
    cleaned = sanitize(vendor, report)
    if cleaned is None:
        return None
    # DONE identities keep their first completion time. Quota acquisitions retain
    # their ordering; the ledger coalesces consecutive equal values without
    # confusing a later return to an earlier value with a cached repetition.
    canonical = json.dumps(cleaned, sort_keys=True, separators=(",", ":"), allow_nan=False)
    captured_at = now or utc_stamp(datetime.datetime.now(datetime.timezone.utc))
    acquisition = "" if cleaned.get("event") == "step_update" else captured_at
    digest = hashlib.sha256((vendor + canonical + acquisition).encode("utf-8")).hexdigest()
    target = pathlib.Path(directory) / (digest + ".jsonl")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return target
    envelope = {"format": "usage-capture-v1", "vendor": vendor,
                "captured_at": captured_at,
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
    parser.add_argument("--stream", action="store_true", help="Read one task event per line instead of a single status report.")
    args = parser.parse_args()
    if args.stream:
        while True:
            line = sys.stdin.buffer.readline(MAX_BYTES + 1)
            if not line:
                break
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
