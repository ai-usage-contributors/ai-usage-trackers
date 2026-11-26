# Working instructions

- AI_NOTICE.md is maintained exclusively by the user. No agent may modify,
  replace, rename, move, or delete it.
- All new settings must have on-hover tooltips explaining their purpose and effect.
  Provide tooltips on both the setting label and its input/control, including nested
  editable controls, and provide equivalent accessible descriptions.
- Read IMPLEMENTATION_HANDOFF.txt before implementation.
- Work in this repository; keep the upstream Codex Usage checkout unchanged.
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
  Sort development history entries by numeric major, minor, then patch version in
  descending order, with the newest version first and the oldest version last.
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
- For every future release, maintain docs/native-ui-test-plan.txt and the
  versioned Given/When/Then acceptance record in docs/native-ui-testing.txt.
  Expand the tested scope for new or changed features, settings, integrations,
  fixes and lifecycle behavior; retain stable scenario IDs and previous results.
  For each execution, record plugin version, artifact SHA256 and source revision
  (or identified local snapshot), date, IDE version/build, OS, relevant display/input
  environment, fixtures, expected and observed behavior, status and evidence.
  Keep planned expectations separate from observations. Never carry a previous
  release's pass forward without rerunning it on the release candidate and its
  supported IDE targets, even when the plugin version number is unchanged.
  Native execution still requires the explicit authorization above. If unavailable,
  record pending/blocked scope and do not claim complete native release acceptance.
  Synthetic vendor UI passes do not establish real-subscription support; keep
  Claude and Antigravity experimental until the documented subscriber checks pass.
- Performance testing focuses on underlying usage collection and calculation,
  not native UI behavior. Defer performance measurements when the user requests it.
- Before reporting implementation complete, run the full Gradle test suite, buildPlugin,
  verifyPluginConfiguration, and required compatibility verification.
- Keep Marketplace What's New (`<change-notes>` in `src/main/resources/META-INF/plugin.xml`)
  limited to the latest minor release and any subsequent bugfix releases in that minor series.
  Remove the previous series when a new minor release begins. Link to the full development
  history in TECHNICAL.md.
- Do not publish to Marketplace, push, or create commits unless requested.
