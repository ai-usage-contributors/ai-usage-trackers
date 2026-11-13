# GitHub and Marketplace preparation

Research checked on 2026-11-12. No remote repository or listing was created.

## GitHub setup

1. The owner selected MIT for the project's own code; LICENSE is included in the
   plugin resources. Preserve the separate upstream notice as well.
2. Create an empty repository under the intended owner. Review files and existing
   history before making them public. Do not include prompts, personal paths,
   credentials, real sessions, local plans/handoffs, agent logs or databases.
   Ignore rules do not remove files already tracked.
3. Include source/tests/resources, tools, notices, public documentation, Gradle
   scripts, both launchers, the wrapper JAR/properties and .github/workflows/ci.yml.
4. After an authorized commit/push, enable Actions and check the first CI run.
   Fork pull requests may need maintainer approval. No publishing secrets are needed.
   Download reports and the tested ZIP from the run's artifacts.
5. Configure branch protection to require `Tests and plugin verification` before
   merging. This is an owner account setting, not enabled by the workflow.

CI runs helper tests and the ordinary Gradle test suite, builds the ZIP and runs
configuration/compatibility checks on Windows with JDK 25 and Python 3.13.
Compatibility targets are the two downloadable IDEA versions in build.gradle.kts.
Use `-PverificationIdePath="<IDE directory>"` for an additional local IDE.
No native UI or performance tests run. Existing PerformanceBaselineTest remains
excluded from ordinary tests. CI is not proof of Linux/macOS runtime acceptance.
Hosted execution must be validated after upload; IntelliJ inspections remain separate.

## Marketplace preparation

The [official upload guide](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
describes login, vendor profile creation, Developer Agreement acceptance and
submission. A license/EULA is required; open-source listings need a source URL.
The ZIP limit is 400 MB. Select accurate tags and consider a custom channel or
hidden listing for controlled availability.

Before submission:

- MIT is selected. Choose the publishing identity, support contact and free/paid
  model; those owner decisions remain pending.
- Add actual repository/support URLs once available; preserve the current plugin ID.
- Prepare an icon, screenshots without personal data, description and release notes.
  Review branding and provider trademark usage.
- Run CONTRIBUTING.md checks and IntelliJ inspections. Explicitly verify an available
  263 IDE before advertising that part of the declared compatibility range.
  Review deferred native acceptance and confirmed limitations in TECHNICAL.md.
- Review dependency licensing, preserve third-party notices and include the selected
  project license in the distributed code/package.
- Manually upload the buildPlugin ZIP from build/distributions. No publishing
  workflow or credentials are configured here.

New plugins and updates undergo JetBrains review. Passing checks does not guarantee
approval. Consult the [approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
and [compatibility guide](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html).

## Signing and future automation

Follow the [signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
to prepare a private key and certificate chain before release. No keys were generated.
Keep signing keys and passwords outside Git. Future automated publishing requires a
Marketplace token, protected secrets and explicit release authorization. Ordinary
pushes and fork pull requests must never receive publishing secrets.

CI uses official [checkout](https://github.com/actions/checkout),
[Java setup](https://github.com/actions/setup-java),
[Python setup](https://github.com/actions/setup-python),
[Gradle setup](https://github.com/gradle/actions) and
[artifact upload](https://github.com/actions/upload-artifact) actions.
