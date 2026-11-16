OPTIONAL VENDOR CAPTURE - CONTRACT v1

EXPERIMENTAL: Claude and Antigravity support is theoretically implemented and
passes synthetic tests. Contributors with subscriptions must validate real logs,
capture setup, totals and quota/reset readings before verified support can be
claimed. See TECHNICAL.md section 5. Never share subscription credentials.

Prerequisite: Python 3.10+ available as python3 (or python on Windows). This helper
does not launch an agent, invoke a model, read authentication files or access a
network endpoint. The plugin only reads its sanitized .jsonl output. Choose a
separate capture directory for each local account and configure the same path in
Advanced -> Accounts and usage sources. Do not put directories containing
different billing accounts together. The group's help icon shows vendor-specific
instructions and path examples in the third column beside each relevant field.

Claude token history: point Claude usage log files or folders at the appropriate account's
Claude projects directory, including nested subagent JSONL files. Assistant message
usage is deduplicated by message ID. Cache-read and cache-creation input are counted
once with uncached input and output. Status-line context occupancy is never usage.

Claude quota: configure the documented statusLine command in Claude settings only
after reviewing your existing command. Do not use /statusline to ask a model to
install this helper. A shell wrapper receives the vendor's JSON on stdin. Store
that input in a shell variable or temporary pipe and supply it BOTH to your
existing renderer and to:
    python3 /absolute/path/capture.py claude /absolute/account/captures
Preserve the existing renderer's stdout exactly; the helper emits no status text.
For a status line without an existing renderer, use this command directly and
accept that it displays no terminal text. Use your platform's shell quoting for
paths; do not concatenate untrusted callback fields into shell commands. Back up
the existing settings before manually composing commands. The plugin never edits
vendor configuration. Callback execution remains activity-driven; it is not an
idle quota poll. A callback refreshInterval does not prove backend freshness.

Antigravity quota: the same reviewed statusLine composition runs:
    python3 /absolute/path/capture.py antigravity /absolute/account/captures
Configure it in that CLI's settings, preserving any existing renderer. Quota pool
names do not establish weekly anchoring. Generic reported pools can be displayed
with their last captured percentages/reset timestamps; fixed-week calculations
remain disabled until boundary evidence exists. Relative reset-only and amount-
only records cannot establish absolute reset times or percentages.

Antigravity token steps: for an ALREADY INTENDED headless task, capture the
documented --output-format stream-json output with a wrapper/tee and forward the
same NDJSON to:
    python3 /absolute/path/capture.py antigravity /absolute/account/captures --stream
Do not run a task merely to create usage data. Only DONE step usage is retained;
partial steps and cumulative result totals are excluded to prevent double counts.
The completion timestamp is the helper's arrival time, not a provider request
timestamp. Request details disclose this precision. Headless-only capture does
not cover ordinary interactive/desktop/ACP token traffic. Native step transcripts
are not parsed speculatively: their schema differs across CLI versions.

Files contain only typed quota fields or validated step IDs/counters and capture time.
No prompt, response text, working directory, email, access token or settings file
is retained. Publication is atomic on filesystems supporting local hard links;
errors leave existing captures unchanged. Repeated DONE steps retain their first
capture time. Quota acquisitions preserve their ordering, including a return to
an earlier value after an intervening change. The plugin coalesces consecutive
identical quota values and retains the first acquisition time of each run.
Keep captures on a local filesystem with hard-link support. After upgrading the
plugin, export the helper again and replace your existing capture.py to use its
updated validation and acquisition handling. Imported history remains in the
plugin database if old capture files are removed.
Interrupted writes leave only ignored .tmp files, which can be removed manually.

Quota reports without a documented provider observation time ALWAYS show unknown
age. Freshness cannot be inferred from file mtime, helper execution or a Refresh
Now click. The plugin makes no promise of a live subscriber balance. Inspect
Usage Info and compare with the vendor's own /usage screen for later acceptance.

JetBrains Air: a terminal running the same CLI may use these explicitly configured
paths. Check effective home/configuration and account mapping. Air chat/ACP,
JetBrains AI billing, remote agents and desktop-only sessions are separate coverage
cases. No internal JetBrains APIs or credentials are used to discover them.

Contracts checked 2026-11-11:
https://code.claude.com/docs/en/statusline
https://code.claude.com/docs/en/hooks
https://antigravity.google/docs/cli/statusline/
https://antigravity.google/docs/cli/headless/
https://www.jetbrains.com/help/air/air-in-ides-authorization.html
