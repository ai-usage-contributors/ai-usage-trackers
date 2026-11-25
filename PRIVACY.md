# Privacy Policy

Effective date: November 16, 2026

This policy describes how AI Usage Trackers handles data when you use the plugin.

## Local processing and storage

AI Usage Trackers calculates its metrics from data already available on your
computer. It reads supported local usage logs and, when configured, local quota
or usage capture files. It processes these files locally and stores its usage
history and settings locally.

**The plugin does not send this data to any servers, including servers belonging
to ai-usage-contributors or any third party.** It has no telemetry, analytics
upload, advertising, or remote usage-reporting service. It does not call AI models
or contact AI providers to obtain your usage or quota information.

## Data used by the plugin

Processing starts only after you explicitly agree. The **Review usage agreement**
status-bar action opens the disclosure in **Settings > Tools > AI Usage Trackers >
General**, where this policy is linked. You can grant or withdraw agreement there
by changing **I agree to local usage processing** and clicking **Apply**
or **OK**. Withdrawal stops processing without erasing stored history.
The agreement revision is saved locally in `aiUsageTrackersConsent.xml`, with
settings sync disabled for this record. It is excluded from configuration exports.

Depending on the available source data and enabled features, the plugin reads
and stores:

- Token counts, request and activity timestamps, quota observations, and reset times.
- Session, request, or step identifiers used to distinguish records and avoid
  counting the same usage twice.
- Source file paths, sizes, modification times, checksums, and processing status
  or warnings needed to maintain usage history.
- Your settings, including tracker definitions, display preferences, account
  aliases, selected quota schemes, and configured source locations.

Account aliases and source locations are local configuration. They do not sign
you into an AI provider or verify your subscription.

Source logs may contain conversation text or other information written by the
AI tools that created them. The plugin parses those files for usage metadata;
it does not retain prompt text, response text, or source code in its usage database.
It does not require AI-provider passwords, API keys, or access tokens.

The optional capture helper also works locally. It writes supported usage or
quota fields and timestamps to local files, without retaining prompt or response
text or sending data over the network.

## Storage and retention

Usage history is stored in SQLite databases in the `codexUsage` folder within
your IDE configuration directory. Plugin settings are stored through the IDE's
settings system in `codexDailyTokens.xml`.

Imported history is retained even when the original source files disappear or
an account is removed from the plugin. Turning tracking off stops further
collection; it does not erase existing history. The plugin does not automatically
expire stored history.

To remove the stored history, close the IDE and delete the plugin's `codexUsage`
folder, including its database sidecar files. To remove saved settings as well,
delete `codexDailyTokens.xml` from the IDE configuration's `options` folder while
the IDE is closed. Delete `aiUsageTrackersConsent.xml` there as well to remove
the saved agreement. Source logs, capture files, and exported files are separate
and must be removed separately if you want to delete them. Re-enabling collection
can import data again from source files that remain available.

## Actions you control and other software

You can export tracker configuration to a file or copy a Usage Info page to the
clipboard. Configuration exports exclude usage history and source locations,
but can contain account aliases and labels you entered. Review exported or
copied information before sharing it. The plugin does not upload it for you.

This policy covers AI Usage Trackers and its capture helper. JetBrains IDE and
Marketplace services, AI tools, operating-system clipboard features, backups,
and any settings-sync or file-sync services you use have their own behavior and
privacy policies. They may handle files or settings independently of the plugin.

## Contact and policy updates

For privacy questions, contact **ai.usage.contributors@gmail.com**. Do not include
private logs, credentials, or sensitive data in public issue reports.

This policy will be updated when the plugin's data handling changes. The effective
date above identifies this revision.
