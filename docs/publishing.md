# GitHub and Marketplace preparation

Research checked on 2026-11-12. The project is now public at
[GitHub](https://github.com/ai-usage-contributors/ai-usage-trackers).
Marketplace account and listing state have not been verified here.

Claude and Antigravity support must be labelled experimental in the listing,
release notes and screenshots/captions. Theoretical implementations and passing
synthetic tests do not establish real-subscription support. Contributors with
subscriptions must validate the scope described in TECHNICAL.md section 5 before
verified support can be claimed.

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
Compatibility targets IDEA 2026.3 only (build 263 through 263.*).
Use `-PverificationIdePath="<IDE directory>"` to verify an installed 2026.3 IDE instead.
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

- Link the [Privacy Policy](../PRIVACY.md) from the Marketplace listing using its
  public repository URL after publication. It describes local-only usage processing,
  storage, retention, exports and deletion. A published policy does not replace
  any required in-plugin consent under the approval guidelines.

- MIT is selected. The plugin metadata retains AI Usage Trackers contributors as
  vendor, with public email ai.usage.contributors@gmail.com.
  Confirm the Marketplace vendor profile and free/paid model.
- Version 1.0.0 includes public repository/support/documentation/license URLs,
  an expanded description and release notes; the plugin ID is unchanged.
- Light/dark chart logos and Lucide license notices are included. The supplied widget screenshots are in [screenshots](screenshots/); review branding and provider trademark usage.
- Run CONTRIBUTING.md checks and IntelliJ inspections. Verify IDEA 2026.3 before publishing.
  Complete and record the authorized native release scope using the
  [test plan](native-ui-test-plan.txt) and [versioned testing record](native-ui-testing.txt).
  Expand that scope for new features each release. Keep pending, blocked and
  experimental subscriber acceptance explicit; do not present historical passes
  as passes for the release candidate. See TECHNICAL.md for coverage limitations.
- Review dependency licensing, preserve third-party notices and include the selected
  project license in the distributed code/package.
- Manually upload the buildPlugin ZIP from build/distributions. No publishing
  workflow or credentials are configured here.

New plugins and updates undergo JetBrains review. Passing checks does not guarantee
approval. Consult the [approval guidelines](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)
and [compatibility guide](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html).

## Signing and future automation

Follow the [signing guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
to prepare a private key and certificate chain before release. The existing IntelliJ
Platform Gradle plugin provides `signPlugin` and `verifyPluginSignature`; the build
declares Marketplace ZIP Signer **0.1.45**. Keep signing credentials outside the repository.

Set `PRIVATE_KEY` to the PEM private-key contents, `CERTIFICATE_CHAIN` to the X509
certificate-chain contents, and `PRIVATE_KEY_PASSWORD` to the key password in the
local process environment or protected CI secrets. Keep their values out of command
lines and repository files. With credentials available, run:

```powershell
.\gradlew.bat signPlugin verifyPluginSignature
```

The signed output is `build/distributions/ai-usage-trackers-1.0.0-signed.zip`;
`buildPlugin` continues producing the unsigned ZIP. Signing-tool setup alone does
not establish a signed or verified release; sign and verify each release artifact.

Keep signing keys and passwords outside Git. Future automated publishing requires a
Marketplace token, protected secrets and explicit release authorization. Ordinary
pushes and fork pull requests must never receive publishing secrets.

CI uses official [checkout](https://github.com/actions/checkout),
[Java setup](https://github.com/actions/setup-java),
[Python setup](https://github.com/actions/setup-python),
[Gradle setup](https://github.com/gradle/actions) and
[artifact upload](https://github.com/actions/upload-artifact) actions.
