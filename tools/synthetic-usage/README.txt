SYNTHETIC USAGE GENERATOR

Claude and Antigravity support is experimental. This generator and its passing
synthetic tests do not verify real subscription support. Contributors with
subscriptions must test actual collection and quota behavior before verified
support can be claimed; see TECHNICAL.md section 5.

Requires Python 3.10 or newer. No packages, credentials or vendor programs.
Run from the repository root:

  python tools/synthetic-usage/generate.py

Edit tools/synthetic-usage/config.json, then run again. The command prints three
source folders. Each run creates a NEW folder under agent-logs/synthetic-usage.
It never overwrites a previous dataset or changes IDE settings.

THE FEW VALUES TO CHANGE

Each vendor has requests and quotas. Add/remove request objects as needed.
- age_minutes: minutes before generation time; decimals work (0.5 = 30 seconds).
- input_tokens / output_tokens: nonnegative whole token counts. Total is their sum.
- cached_input_tokens: part of input_tokens, NOT additional tokens (optional).
- reasoning_output_tokens: part of output_tokens (optional; Claude does not retain
  this separate counter in its supported assistant-message source format).
- used_percent: quota used, 0-100. Set null for a reset-only report.
- reset_in_minutes: minutes AFTER generation time, not after the report's age.
  Use a negative number for an already-passed reset, or null for percentage only.
- quota age_minutes: how old the quota report/capture is. 20 gives a 20-minute-old
  report; request ages are independent.
- Remove a quota entry (or set it to null) to omit that window. Use "quotas": {}
  for no quotas; use "requests": [] for no request usage.
- now_utc: leave null for current time. For repeatable dates use an ISO timestamp
  with Z or an offset, for example "2026-11-13T12:00:00Z".

Codex/Claude quota names: weekly and five_hour. Antigravity allows arbitrary pool
IDs such as demo-pool. Naming an Antigravity pool weekly does NOT establish weekly
boundary semantics or unlock weekly tracking.

USING THE FILES IN THE PLUGIN

Use the isolated test IDE, with separate NEW demo accounts for each generated
vendor. Do not attach these sources to real usage accounts.

1. Settings > AI Usage Trackers > Advanced > Accounts and usage sources.
2. Select Codex and Add account, give it a demo alias, then set its source folder
   to the printed codex folder (the folder ABOVE sessions, not sessions itself).
   Use a new account: the original default Codex account auto-discovers real logs.
3. Repeat for Claude and Antigravity, using their printed folders as source paths.
   All required capture envelopes are already generated; no capture helper needed.
4. Confirm source ownership and create default trackers for each demo account.
5. In General, enable Track usage and select the demo account for each vendor via
   Advanced. Apply/OK, then use Refresh Now. Choose Displayed vendor or carousel.
6. For quotas select that demo's Quota account and the appropriate supported
   scheme in Quota, then enable the percentage/reset displays you want.

Output layout:
  codex/sessions/rollout-demo.jsonl   Codex home containing a synthetic session
  claude/demo.jsonl                 Assistant usage plus quota capture envelopes
  antigravity/demo.jsonl            Captured DONE steps plus generic quota pools

IMPORTANT WHEN EDITING AND REGENERATING

The plugin RETAINS imported history. Deleting/replacing a file or changing a
source folder does not clear a ledger. Use NEW demo accounts when switching to a
newly generated dataset if you want its totals alone. The generator prints a fresh
folder every run so previous datasets stay separate. It does not reset databases.

Codex report age supports controlled fresh/stale quota scenarios. Claude and
Antigravity capture formats do not establish a provider observation timestamp;
the plugin correctly treats their quota freshness as unknown. Synthetic capture
time does not bypass that rule. Generic Antigravity pools do not unlock verified
weekly quota capabilities. These files demonstrate local parsing, not live
subscriber balances or unverified provider interfaces.

OPTIONAL COMMANDS

  python tools/synthetic-usage/generate.py --config path/to/my-config.json
  python tools/synthetic-usage/generate.py --output agent-logs/my-new-demo

A supplied output folder MUST NOT already exist. Invalid config is rejected
before output creation. All config is read before writing; no live file watcher.
Test with:
  python -m unittest discover -s tools/synthetic-usage -p "test_*.py"
