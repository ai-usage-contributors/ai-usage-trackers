package local.aiusagetrackers

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

object AnalyticsPresentation {
    fun isLastKnown(snapshot: UsageSnapshot): Boolean {
        val request = snapshot.analytics.lastRequest ?: return true
        return snapshot.analytics.latestActivity?.isAfter(request.time) == true ||
                snapshot.issues.isNotEmpty() || snapshot.collection.issues.isNotEmpty() || snapshot.analytics.issues.isNotEmpty()
    }

    fun describe(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences,
        zone: ZoneId,
        options: AnalyticsDisplayOptions = AnalyticsDisplayOptions()
    ): String = buildString {
        val format = DisplayFormats(preferences)
        snapshot.analytics.lastRequest?.usage?.evidence?.let { append("• Token evidence: $it\n") }
        appendOtherPools(snapshot, options)
        appendRequest(snapshot, format, zone)
        for (duration in listOf(10080, 300)) appendWindow(snapshot, options, format, zone, duration)
        snapshot.analytics.operationalIssues.sorted().forEach { append("• $it\n") }
    }


    private fun StringBuilder.appendOtherPools(snapshot: UsageSnapshot, options: AnalyticsDisplayOptions) {
        snapshot.analytics.quotaObservations.filter { it.usage.windowMinutes == 0 }.forEach { observation ->
            val reading = QuotaSelection(observation, snapshot.collection.lastCompleted, false, 0, snapshot.evaluatedAt)
            append(
                "• Reported pool ${observation.usage.bucket}: ${reading.percentage(options.quotaPercentMode) ?: "unavailable"}; reset ${
                    observation.usage.resetsAt?.let(
                        Instant::ofEpochSecond
                    ) ?: "unknown"
                }\n"
            )
            append("• Boundary policy: unverified; ${reading.warning(false) ?: "provider observation available"}\n")
        }
    }

    private fun StringBuilder.appendRequest(snapshot: UsageSnapshot, format: DisplayFormats, zone: ZoneId) {
        val request = snapshot.analytics.lastRequest
        if (request == null) append("• Last request: unavailable; no verifiable request usage collected\n")
        else {
            append("• ${if (isLastKnown(snapshot)) "Last known request" else "Last request"}: ${tokens(request.usage.counters.total)}\n")
            append("• Request reported: ${format.dateTime(request.time, zone, true)} (${zone.id})\n")
            append("• Request evidence: ${request.usage.evidence}\n")
            val counters = request.usage.counters
            append("• Request input: ${tokens(counters.input)}\n")
            append("• Request cache-read tokens (overlap follows provider contract): ${counters.cached?.let(::tokens) ?: "unavailable"}\n")
            append("• Request output: ${tokens(counters.output)}\n")
            append("• Request reasoning output (included in output): ${counters.reasoning?.let(::tokens) ?: "unavailable"}\n")
            if (snapshot.analytics.simultaneousRequests) append("• Multiple request observations share this timestamp; their order or overlap is unknown\n")
        }
    }

    private fun StringBuilder.appendWindow(
        snapshot: UsageSnapshot,
        options: AnalyticsDisplayOptions,
        format: DisplayFormats,
        zone: ZoneId,
        duration: Int
    ) {
        if (!QuotaEligibility.window(options.capabilities(), duration)) return
        val label = if (duration == 10080) "Weekly" else "5h"
        val selection = QuotaPresentation.select(snapshot, options, duration)
        val quota = selection.reading
        if (quota == null) append("• $label quota: unavailable; no provider-reported window for the selected scope\n")
        else {
            val mode = if (options.quotaPercentMode == QuotaPercentMode.USED) "used" else "remaining"
            append(
                "• $label quota: ${
                    selection.percentage(options.quotaPercentMode)?.let { "$it $mode" } ?: "unavailable"
                }\n")
            val reset = quota.usage.resetsAt?.let(Instant::ofEpochSecond)
            append(
                "• $label reset: ${
                    reset?.let {
                        format.dateTime(
                            it,
                            zone,
                            true
                        ) + " (${zone.id})"
                    } ?: "unknown"
                }; in ${selection.resetDuration() ?: "unavailable"} (at last collection)\n")
            val state = when {
                selection.expired -> "expired; awaiting a new provider observation"
                quota.usage.freshnessUnknown -> "provider observation age unknown; callback capture is not a backend refresh"
                selection.stale -> "stale; last observed values"
                else -> "observed within the last 15 minutes"
            }
            append("• Quota observed: ${format.dateTime(quota.time, zone, true)} (${zone.id}); $state\n")
            append("• Quota scope: ${QuotaPresentation.scope(quota)}; account unidentified${if (selection.multipleScopes) "; multiple scopes available in Settings; values are not combined" else ""}\n")
            appendEstimate(snapshot, options, format, zone, duration, selection, quota)
        }
    }

    private fun StringBuilder.appendEstimate(
        snapshot: UsageSnapshot,
        options: AnalyticsDisplayOptions,
        format: DisplayFormats,
        zone: ZoneId,
        duration: Int,
        selection: QuotaSelection,
        quota: QuotaObservation
    ) {
        if (duration == 10080 && options.estimateBudget && QuotaEligibility.budget(options.capabilities())) {
            val estimate = snapshot.budgetEstimates[QuotaPresentation.scope(quota)]
            if (!selection.expired && estimate?.total != null) {
                append(
                    "• Estimated weekly token budget: ~${WidgetText.compactDecimal(estimate.total)} tokens; estimated remaining ~${
                        WidgetText.compactDecimal(
                            estimate.remaining!!
                        )
                    } tokens\n"
                )
                append(
                    "• Estimate inputs: ${estimate.tokens} local tokens and ${estimate.usedPercent}% used, through ${
                        format.dateTime(
                            estimate.observedAt,
                            zone,
                            true
                        )
                    }. Formula: tokens × 100 / percent used.\n"
                )
            } else append("• Estimated weekly token budget: unavailable\n")
            append("• ${estimate?.explanation ?: "Waiting for paired local usage and a weekly observation with at least 1% used"}\n")
        }
    }

    private fun tokens(value: Long): String = "$value (${
        BigDecimal.valueOf(value).movePointLeft(6).setScale(1, RoundingMode.HALF_UP).toPlainString()
    }M) tokens"
}
