package local.aiusagetrackers

import java.time.ZoneId

data class UsageDetailPages(val usage: String, val warnings: String, val integrity: String,
    val warningIssues: Set<String> = emptySet(), val integrityFindings: Set<String> = emptySet()) {
    companion object {
        fun capture(snapshot: UsageSnapshot, preferences: DisplayPreferences, zone: ZoneId,
            options: AnalyticsDisplayOptions, showLastRequest: Boolean = true): UsageDetailPages {
            val warnings = WidgetWarnings.messages(snapshot.copy(acknowledgedWarnings = emptySet()), WidgetText.values(snapshot, preferences, showLastRequest, options))
            val integrity = linkedSetOf<String>().apply {
                addAll(snapshot.integrityIssues); addAll(snapshot.collection.integrityIssues); addAll(snapshot.analytics.integrityIssues)
                snapshot.collection.sourceProblems.forEach { problem ->
                    val name = problem.fileName.map { if (it.isISOControl()) ' ' else it }.joinToString("")
                    val date = problem.date?.let(DisplayFormats(preferences)::date) ?: "date unknown"
                    problem.issues.forEach { add("$name — $date — $it") }
                }
            }
            return UsageDetailPages(
                UsageInfoText.describe(snapshot, preferences, zone, options),
                warnings.joinToString("\n") { "• ${if (it in snapshot.acknowledgedWarnings) "[Acknowledged] " else ""}$it" }.ifEmpty { "No operational warnings." },
                integrity.joinToString("\n") { "• ${if (it in snapshot.acknowledgedIntegrity) "[Acknowledged] " else ""}$it" }.ifEmpty { "No log integrity findings." },
                warningIssues = warnings.filterNot { it in snapshot.acknowledgedWarnings }.toSet(),
                integrityFindings = integrity.filterNot { it in snapshot.acknowledgedIntegrity }.toSet(),
            )
        }
    }
}
