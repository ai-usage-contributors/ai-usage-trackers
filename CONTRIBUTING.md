# Contributing

Read AGENTS.md before changing this project. Use JDK 25 and the checked-in
Gradle wrapper. Run from the repository root:

```powershell
python -m unittest discover -s tools/quota-capture -p 'test_*.py'
.\gradlew.bat test buildPlugin verifyPluginConfiguration verifyPlugin
```

Run IntelliJ inspections on changed supported source, tests, and build files.
CI does not replace those inspections. Performance measurements and native IDE
testing are separate and require the authorization described in AGENTS.md.

Use synthetic fixtures. Never include real sessions, prompts, credentials,
personal paths, usage databases, or agent logs in changes or issue attachments.
Describe behavior changes and validation in pull requests. Update README.md and
TECHNICAL.md when applicable, and increment the plugin version before packaging.

See [publishing preparation](docs/publishing.md) for repository setup and release gates.
