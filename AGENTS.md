# Working instructions

- Communicate in English.
- All new settings must have on-hover tooltips explaining their purpose and effect.
  Provide tooltips on both the setting label and its input/control, including nested
  editable controls, and provide equivalent accessible descriptions.
- Read IMPLEMENTATION_HANDOFF.txt before implementation.
- Every update must yield a new plugin version number; increment it before building
  the updated package. Very small incremental fixes and bugfixes increment the
  patch version. New features increment the minor version and reset the patch
  version to zero. Remain below 1.0 until the user explicitly decides the project
  is ready for 1.0; do not advance to 1.0 automatically.
- Work in this repository; keep the upstream Codex Usage checkout unchanged.
- Verify existing code references with tool output; verify line numbers before citing them.
- Keep README.md and TECHNICAL.md current when behavior, requirements, settings,
  limitations, or versions change. Reconcile existing text; do not append repeated
  explanations or leave superseded behavior presented as current.
- README.md is for users: use simple, easy-to-understand language and include only
  useful, factual user guidance. Avoid repetition, rambling, release/development
  history, design rationale, implementation details, and technical explanations of
  data availability. Keep its link to TECHNICAL.md for technical details.
- TECHNICAL.md is the technical reference: start with the high-level goal and
  architecture, then cover requirements/versions, implemented functionality,
  design choices, limitations, settings elements/composition, widget elements and
  interaction/animation details (including exact timings), and database semantics.
  Keep numbered sections, an up-to-date table of contents, a Definitions section,
  and a concise development history listing produced versions and their changes.
  Highlight technical/domain terms (such as agent, plan, account, and usage) with
  backticks or equivalent styling, define them, and link authoritative external
  sources in definitions where applicable. Avoid rambling and repetition; use
  cross-references rather than duplicate explanations.
- Verify documentation claims against current code, configuration, tests, or
  recorded validation; use authoritative external sources for external claims.
  Remove or correct unsupported claims. Raise confirmed bugs to the user rather
  than documenting intended behavior as implemented. Distinguish implemented,
  historical, proposed, and unverified behavior, and do not present deferred
  validation as completed. Documentation-only edits do not require a plugin
  version increment or package checks.
- Do not commit session logs, prompts, authentication data, personal paths, or generated usage databases.
- Do not add model or assistant authorship trailers or claims to commits or source files.
- Use supported IntelliJ APIs. Collect usage from local Codex JSONL logs without invoking a model.
- Keep all file scanning and parsing off the UI thread.
- Reset-aligned weekly usage and quota features include every day, including
  weekends and holidays. Do not infer exclusions from "workweek" or local workday
  calendars. Apply weekday filtering only when explicitly selected for a tracker.
- Tests must cover meaningful parser and time-boundary behavior; use a controllable clock and zone provider.
- Run native/live-plugin or UI testing only when the user explicitly requests it.
- Performance testing focuses on underlying usage collection and calculation,
  not native UI behavior. Defer performance measurements when the user requests it.
- Before reporting implementation complete: run the full Gradle test suite, buildPlugin,
  verifyPluginConfiguration, required compatibility verification, and IntelliJ inspections on changed
  supported source/tests/build files. Resolve task-related findings.
- Report missing validation or unresolved task-related issues prominently as
  WARNING: Outstanding concerns.
- State whether additional local testing is needed. If needed, use LOCAL TESTING REQUIRED and
  list prerequisites, exact commands or manual steps, and expected results.
- Do not publish to Marketplace, push, or create commits unless requested.
