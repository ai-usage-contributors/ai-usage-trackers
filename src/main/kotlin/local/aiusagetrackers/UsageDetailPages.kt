package local.aiusagetrackers

import java.time.ZoneId

data class UsageDetailPages(val usage: String, val warnings: String, val integrity: String,
    val warningIssues: Set<String> = emptySet(), val integrityFindings: Set<String> = emptySet()) {
    companion object {
        // Keep stored issue strings as keys for existing reports and acknowledgements.
        private fun integrityText(issue: String): String = when (issue) {
            "A log import failed or changed during reading; previous history retained; retry pending" ->
                "Couldn’t import a log. Previously imported usage is saved. The plugin will try again."
            "Forked session history excluded; coverage incomplete" ->
                "Skipped the log from a branched session. Its usage may be missing from totals."
            "Counter reset or mixed reporting; coverage incomplete" ->
                "The token count restarted or the log changed how it reports usage. Totals may be incomplete."
            "Request records are not fully reconciled with period totals; period coverage incomplete" ->
                "The recorded requests don’t fully account for the period total. Some usage may be missing."
            "Request or quota metadata without a valid timestamp excluded" ->
                "Skipped request or quota records with missing or invalid times."
            "Source identity changed; previous history retained" ->
                "This file now points to a different session. Previously imported usage is saved."
            "Session identity missing; ambiguous copy excluded" ->
                "Skipped a possible duplicate because its session ID was missing."
            "Truncated session; previously imported usage retained" ->
                "This log is shorter than before. Previously imported usage is saved."
            "Invalid request metadata excluded" -> "Skipped invalid request details."
            "Invalid quota metadata excluded", "Invalid quota window metadata excluded" -> "Skipped invalid quota details."
            "Invalid request or quota metadata excluded" -> "Skipped invalid request or quota details."
            "Conflicting session history excluded; previous history retained" ->
                "Skipped a log that conflicts with saved session history. Previously imported usage is saved."
            "Conflicting identities in one log" -> "This log contains conflicting session IDs."
            else -> issue
        }

        fun capture(snapshot: UsageSnapshot, preferences: DisplayPreferences, zone: ZoneId,
            options: AnalyticsDisplayOptions, showLastRequest: Boolean = true): UsageDetailPages {
            val warnings = WidgetWarnings.messages(snapshot.copy(acknowledgedWarnings = emptySet()), WidgetText.values(snapshot, preferences, showLastRequest, options))
            val integrity = linkedMapOf<String, String>().apply {
                sequenceOf(snapshot.integrityIssues, snapshot.collection.integrityIssues, snapshot.analytics.integrityIssues)
                    .flatten().forEach { put(it, integrityText(it)) }
                snapshot.collection.sourceProblems.forEach { problem ->
                    val name = problem.fileName.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                    val date = problem.date?.let(DisplayFormats(preferences)::date) ?: "date unknown"
                    problem.issues.forEach { issue ->
                        val prefix = "$name — $date — "
                        put(prefix + issue, prefix + integrityText(issue))
                    }
                }
            }
            return UsageDetailPages(
                UsageInfoText.describe(snapshot, preferences, zone, options),
                warnings.joinToString("\n") { "• ${if (it in snapshot.acknowledgedWarnings) "[Acknowledged] " else ""}$it" }.ifEmpty { "No operational warnings." },
                integrity.entries.joinToString("\n") { (key, message) ->
                    "• ${if (key in snapshot.acknowledgedIntegrity) "[Acknowledged] " else ""}$message"
                }.ifEmpty { "No log integrity findings." },
                warningIssues = warnings.filterNot { it in snapshot.acknowledgedWarnings }.toSet(),
                integrityFindings = integrity.keys.filterNot { it in snapshot.acknowledgedIntegrity }.toSet(),
            )
        }
    }
}
