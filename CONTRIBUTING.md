# Contributing

Read AGENTS.md before changing this project. Use JDK 25 and the checked-in
Gradle wrapper.

Claude and Antigravity support is experimental. Contributors with subscriptions
are needed to validate the theoretically implemented adapters against real client
logs and quota readings. Passing synthetic tests alone does not establish verified
support; follow the acceptance scope in TECHNICAL.md section 5 without sharing credentials.

Run from the repository root:

```powershell
python -m unittest discover -s tools/quota-capture -p 'test_*.py'
.\gradlew.bat test buildPlugin verifyPluginConfiguration verifyPlugin
```

Run IntelliJ inspections on changed supported source, tests, and build files.
CI does not replace those inspections. Performance measurements and native IDE
testing are separate and require the authorization described in AGENTS.md.

For each release, follow [the native UI plan](docs/native-ui-test-plan.txt) and
record Given/When/Then observations, plugin/IDE versions and build identity in
[the native UI testing record](docs/native-ui-testing.txt). Add scenarios for new
features and changed behavior. Historical passes do not certify a new candidate.

Use synthetic fixtures. Never include real sessions, prompts, credentials,
personal paths, usage databases, or agent logs in changes or issue attachments.
Describe behavior changes and validation in pull requests. Update README.md and
TECHNICAL.md when applicable, and increment the plugin version before packaging.

See [publishing preparation](docs/publishing.md) for repository setup and release gates.
