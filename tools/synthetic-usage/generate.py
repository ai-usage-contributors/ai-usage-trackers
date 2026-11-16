"""Generate Codex and experimental Claude/Antigravity demo JSONL. Python 3.10+, no packages."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid

VENDORS = ("codex", "claude", "antigravity")


def stamp(value):
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def number(value, name, minimum=0, maximum=10**12):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{name} must be a number")
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def counts(request):
    result = {}
    for key in ("input_tokens", "output_tokens", "cached_input_tokens", "reasoning_output_tokens"):
        value = number(request.get(key, 0), key)
        if not isinstance(value, int):
            raise ValueError(f"{key} must be an integer")
        result[key] = value
    if result["cached_input_tokens"] > result["input_tokens"]:
        raise ValueError("cached_input_tokens cannot exceed input_tokens")
    if result["reasoning_output_tokens"] > result["output_tokens"]:
        raise ValueError("reasoning_output_tokens cannot exceed output_tokens")
    result["total_tokens"] = result["input_tokens"] + result["output_tokens"]
    return result


def envelope(vendor, at, report):
    return {"format": "usage-capture-v1", "vendor": vendor,
            "captured_at": stamp(at), "report": report}


def quota_rows(vendor, quotas, now):
    rows = []
    for pool, values in quotas.items():
        if not values:
            continue
        if set(values) - {"used_percent", "reset_in_minutes", "age_minutes"}:
            raise ValueError(f"Unknown quota field for {vendor}/{pool}")
        age = number(values.get("age_minutes", 0), "quota age_minutes", maximum=10**8)
        at = now - timedelta(minutes=age)
        used = values.get("used_percent")
        reset = values.get("reset_in_minutes")
        if used is None and reset is None:
            raise ValueError(f"{pool}: supply used_percent or reset_in_minutes")
        if used is not None:
            number(used, "used_percent", maximum=100)
        reset_at = None if reset is None else now + timedelta(
            minutes=number(reset, "reset_in_minutes", minimum=-10**8, maximum=10**8))
        if vendor == "codex":
            if pool not in ("weekly", "five_hour"):
                raise ValueError("Codex pools must be weekly or five_hour")
            window = {"window_minutes": 10080 if pool == "weekly" else 300}
            if used is not None:
                window["used_percent"] = used
            if reset_at is not None:
                window["resets_at"] = int(reset_at.timestamp())
            slot = "secondary" if pool == "weekly" else "primary"
            rows.append({"type": "event_msg", "timestamp": stamp(at), "payload": {
                "type": "token_count", "info": None,
                "rate_limits": {"limit_id": "synthetic", slot: window}}})
        elif vendor == "claude":
            if pool not in ("weekly", "five_hour"):
                raise ValueError("Claude pools must be weekly or five_hour")
            window = {}
            if used is not None:
                window["used_percentage"] = used
            if reset_at is not None:
                window["resets_at"] = int(reset_at.timestamp())
            slot = "seven_day" if pool == "weekly" else "five_hour"
            rows.append(envelope(vendor, at, {"rate_limits": {slot: window}}))
        else:
            if not pool or len(pool) > 512 or any(ord(char) < 32 for char in pool):
                raise ValueError("Antigravity pool IDs must be 1-512 printable characters")
            window = {}
            if used is not None:
                window["remaining_fraction"] = (100 - used) / 100
            if reset_at is not None:
                window["reset_time"] = stamp(reset_at)
            rows.append(envelope(vendor, at, {"quota": {pool: window}}))
    return rows


def generate(config, now, dataset_id):
    """Pure generation with an injected UTC clock and ID for reproducible tests."""
    if now.tzinfo is None:
        raise ValueError("now_utc must include Z or an explicit UTC offset")
    if set(config) - {"now_utc", *VENDORS}:
        raise ValueError("Unknown top-level config field")
    result = {}
    for vendor in VENDORS:
        settings = config[vendor]
        if set(settings) - {"requests", "quotas"}:
            raise ValueError(f"Unknown setting for {vendor}")
        requests = settings.get("requests", [])
        if len(requests) > 1000:
            raise ValueError("Use at most 1000 requests per vendor")
        rows = []
        cumulative = dict.fromkeys(("input_tokens", "output_tokens", "cached_input_tokens",
                                    "reasoning_output_tokens", "total_tokens"), 0)
        if vendor == "codex":
            rows.append({"type": "session_meta", "timestamp": stamp(now),
                         "payload": {"id": dataset_id}})
        # Codex cumulative counters must advance from the oldest request to newest.
        requests = sorted(requests, key=lambda row: number(
            row["age_minutes"], "request age_minutes", maximum=10**8), reverse=True)
        for index, request in enumerate(requests):
            if set(request) - {"age_minutes", *cumulative}:
                raise ValueError(f"Unknown request field for {vendor}")
            if "total_tokens" in request:
                raise ValueError("total_tokens is calculated; set input_tokens and output_tokens")
            at = now - timedelta(minutes=request["age_minutes"])
            usage = counts(request)
            identity = f"{dataset_id}-{vendor}-{index}"
            if vendor == "codex":
                cumulative = {key: cumulative[key] + usage[key] for key in cumulative}
                rows.extend([
                    {"type": "token_usage_record", "timestamp": stamp(at), "payload": {
                        "response_id": identity, "usage": usage,
                        "thread_token_usage": cumulative}},
                    {"type": "event_msg", "timestamp": stamp(at), "payload": {
                        "type": "token_count", "info": {
                            "total_token_usage": cumulative, "last_token_usage": usage}}}])
            elif vendor == "claude":
                rows.append({"type": "assistant", "timestamp": stamp(at),
                             "sessionId": dataset_id, "message": {"id": identity, "usage": {
                                 "input_tokens": usage["input_tokens"] - usage["cached_input_tokens"],
                                 "cache_read_input_tokens": usage["cached_input_tokens"],
                                 "output_tokens": usage["output_tokens"]}}})
            else:
                rows.append(envelope(vendor, at, {"event": "step_update", "step_update": {
                    "conversation_id": dataset_id, "step_index": index, "state": "DONE",
                    "usage": {"input_tokens": usage["input_tokens"],
                              "output_tokens": usage["output_tokens"],
                              "total_tokens": usage["total_tokens"],
                              "cache_read_tokens": usage["cached_input_tokens"],
                              "thinking_tokens": usage["reasoning_output_tokens"]}}}))
        rows.extend(quota_rows(vendor, settings.get("quotas", {}), now))
        path = "codex/sessions/rollout-demo.jsonl" if vendor == "codex" else f"{vendor}/demo.jsonl"
        result[path] = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)
    return result


def write_dataset(output, files):
    # Refuse existing directories, including real vendor homes. No files are deleted.
    output.mkdir(parents=True, exist_ok=False)
    for relative, contents in files.items():
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents, encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("config.json"))
    parser.add_argument("--output", type=Path, help="New directory; must not already exist")
    args = parser.parse_args()
    try:
        config = json.loads(args.config.read_text(encoding="utf-8-sig"))
        now_text = config.get("now_utc")
        now = datetime.fromisoformat(now_text.replace("Z", "+00:00")) if now_text else datetime.now(timezone.utc)
        dataset_id = "demo-" + uuid.uuid4().hex
        files = generate(config, now, dataset_id)
        output = (args.output or Path(__file__).resolve().parents[2] / "agent-logs" /
                  "synthetic-usage" / dataset_id).resolve()
        write_dataset(output, files)
    except (ValueError, KeyError, TypeError, OSError, OverflowError) as error:
        parser.exit(1, f"Error: {error}\n")
    print(f"Synthetic demo generated at {stamp(now)}")
    for vendor in VENDORS:
        print(f"{vendor}: {output / vendor}")
    print("Use NEW test accounts for this dataset. Imported history is retained by the plugin.")


if __name__ == "__main__":
    main()
