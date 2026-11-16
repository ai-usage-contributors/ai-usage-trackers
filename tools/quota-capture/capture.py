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
    result = {}
    if vendor == "claude":
        for field in ("used_percentage", "resets_at"):
            if field in value and value[field] is not None:
                result[field] = numeric(value[field], field, 100 if field == "used_percentage" else 253402300799,
                                        integer=field == "resets_at")
    else:
        for field in ("remaining_fraction", "remaining_amount", "reset_in_seconds"):
            if field in value and value[field] is not None:
                result[field] = numeric(value[field], field, 1 if field == "remaining_fraction" else 9223372036854775807)
        if value.get("disabled") is not None:
            if not isinstance(value["disabled"], bool):
                raise ValueError("disabled must be boolean")
            result["disabled"] = value["disabled"]
        if value.get("reset_time") is not None:
            text = value["reset_time"]
            if not isinstance(text, str) or len(text) > 64:
                raise ValueError("reset_time must be a timestamp")
            parsed = datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise ValueError("reset_time requires a time zone")
            result["reset_time"] = parsed.astimezone(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
    return result


def sanitize(vendor, report):
    if not isinstance(report, dict):
        raise ValueError("Expected a JSON object")
    if vendor == "claude":
        limits = report.get("rate_limits")
        if not isinstance(limits, dict):
            return None
        cleaned = {}
        for key in ("five_hour", "seven_day"):
            value = limits.get(key)
            if isinstance(value, dict):
                cleaned[key] = quota_fields(value, vendor)
        return {"rate_limits": cleaned} if cleaned else None
    if report.get("event") == "step_update":
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
    quotas = report.get("quota")
    if not isinstance(quotas, dict):
        return None
    return {"quota": {identity(key): quota_fields(value, vendor)
                      for key, value in quotas.items() if isinstance(value, dict)}}


def capture(vendor, directory, report, now=None):
    cleaned = sanitize(vendor, report)
    if cleaned is None:
        return None
    # DONE identities keep their first completion time. Quota acquisitions retain
    # their ordering; the ledger coalesces consecutive equal values without
    # confusing a later return to an earlier value with a cached repetition.
    canonical = json.dumps(cleaned, sort_keys=True, separators=(",", ":"), allow_nan=False)
    captured_at = now or datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
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
    parser.add_argument("--stream", action="store_true", help="Read NDJSON step events instead of one status-line JSON object")
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
