package local.aiusagetrackers

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

object WidgetText {
    fun text(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences = DisplayPreferences(),
        showLastRequest: Boolean = false,
        analyticsDisplay: AnalyticsDisplayOptions = AnalyticsDisplayOptions(),
        planLabel: String = "Codex"
    ): String {
        return "$planLabel: " + values(
            snapshot,
            preferences,
            showLastRequest,
            analyticsDisplay
        ).joinToString(" · ") { it.displayText() }
    }

    fun values(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences = DisplayPreferences(),
        showLastRequest: Boolean = false,
        options: AnalyticsDisplayOptions = AnalyticsDisplayOptions()
    ): List<StatusValue> = buildList {
        if (snapshot.agreementRequired) {
            add(StatusValue("state", "", "agreement required", state = StatusValueState.UNAVAILABLE))
            return@buildList
        }
        if (options.showReportedPools) addReportedPools(snapshot, options)
        if (snapshot.loading) {
            add(StatusValue("state", "", "loading", state = StatusValueState.LOADING)); return@buildList
        }
        addTrackerValues(snapshot, preferences, options)
        if (showLastRequest) add(lastRequestValue(snapshot, preferences, options))
        for (duration in listOf(10080, 300)) addWindow(snapshot, options, duration)
    }.map { styledValue(it, preferences, options) }

    private fun MutableList<StatusValue>.addTrackerValues(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences,
        options: AnalyticsDisplayOptions,
    ) {
        val visible = snapshot.trackers.filter { it.definition.visible }
        when {
            snapshot.collection.homes == 0 && snapshot.storedEvents == 0L -> add(
                StatusValue(
                    "state",
                    "",
                    "unavailable",
                    state = StatusValueState.UNAVAILABLE
                )
            )

            visible.isEmpty() -> add(StatusValue("state", "", "no visible trackers"))
            else -> visible.forEach {
                add(
                    trackerValue(
                        it,
                        snapshot.evaluatedAt,
                        preferences,
                        QuotaMetricPresentation.forTracker(snapshot, options, it.definition),
                        if (it.definition.showFiveHourPercentUsed && it.definition.supportsFiveHourShare())
                            QuotaMetricPresentation.forTracker(snapshot, options, it.definition, 300) else null,
                    )
                )
            }
        }
    }

    private fun lastRequestValue(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences,
        options: AnalyticsDisplayOptions,
    ): StatusValue {
        val total = snapshot.analytics.lastRequest?.usage?.counters?.total
        return QuotaMetricPresentation.select(snapshot, options).apply(
            StatusValue(
                "request",
                options.lastRequestLabel,
                total?.let(::compact) ?: "unavailable",
                total?.toBigDecimal(),
                if (total == null) StatusValueState.UNAVAILABLE else StatusValueState.AVAILABLE,
                options.lastRequestStyle
            ),
            preferences.allTokenPercentUsed || options.lastRequestPercentUsed,
            layout = options.lastRequestMetricLayout(),
            fiveHour = if (options.lastRequestFiveHourPercentUsed)
                QuotaMetricPresentation.select(snapshot, options, 300) else null,
        )
    }


    private fun MutableList<StatusValue>.addReportedPools(snapshot: UsageSnapshot, options: AnalyticsDisplayOptions) {
        snapshot.analytics.quotaObservations.filter { it.usage.windowMinutes == 0 }.forEach { observation ->
            val reading = QuotaSelection(observation, snapshot.collection.lastCompleted, false, 0, snapshot.evaluatedAt)
            val percent = reading.percentage(options.quotaPercentMode)
            val reset = observation.usage.resetsAt?.let { Instant.ofEpochSecond(it).toString() }
            add(
                StatusValue(
                    "pool:${observation.usage.bucket}", observation.usage.bucket,
                    if (observation.usage.disabled) "disabled" else listOfNotNull(
                        percent,
                        reset?.let { "reset $it" }).joinToString("; ").ifEmpty { "unavailable" },
                    state = when {
                        observation.usage.disabled -> StatusValueState.UNAVAILABLE
                        reading.expired -> StatusValueState.EXPIRED
                        reading.stale -> StatusValueState.STALE
                        else -> StatusValueState.AVAILABLE
                    },
                    warnings = listOfNotNull(reading.warning(false))
                )
            )
        }
    }

    private fun MutableList<StatusValue>.addWindow(
        snapshot: UsageSnapshot,
        options: AnalyticsDisplayOptions,
        duration: Int
    ) {
        if (!QuotaEligibility.window(options.capabilities(), duration)) return
        val reading = QuotaPresentation.select(snapshot, options, duration)
        val state = when {
            reading.expired -> StatusValueState.EXPIRED
            reading.reading == null -> StatusValueState.UNAVAILABLE
            reading.stale -> StatusValueState.STALE
            else -> StatusValueState.AVAILABLE
        }

        addPercentage(snapshot, options, duration, reading, state)
        addReset(options, duration, reading, state)
    }

    private fun MutableList<StatusValue>.addPercentage(
        snapshot: UsageSnapshot,
        options: AnalyticsDisplayOptions,
        duration: Int,
        reading: QuotaSelection,
        state: StatusValueState
    ) {
        val weekly = duration == 10080
        val shown = if (weekly) options.showQuota else options.showFiveHourQuota
        if (!shown || !QuotaEligibility.metric(options.capabilities(), duration, reset = false).enabled) return
        val mode = options.percentMode(duration)
        val percent = reading.percentage(mode)
        val used = reading.reading?.usage?.usedPercent?.let(BigDecimal::valueOf)
        val pacingGoal = if (!weekly && percent != null) reading.fiveHourGoalPercent() else null
        val showEstimate = options.showsBudgetEstimate(duration)
        val estimate = reading.matchingEstimate(snapshot)
        val amount = if (showEstimate) {
            estimate?.takeIf { it.total != null }?.amount(mode)?.let { "|~${compactDecimal(it)}" } ?: "|estimate unavailable"
        } else ""
        val percentText = percent ?: "unavailable"
        add(
            StatusValue(
                "quota:$duration",
                if (weekly) options.weeklyQuotaLabel else options.fiveHourQuotaLabel,
                percentText + amount,
                used,
                if (percent == null) StatusValueState.UNAVAILABLE else state,
                if (weekly) options.weeklyQuotaStyle else options.fiveHourQuotaStyle,
                listOfNotNull(reading.warning(false), estimate?.explanation).joinToString(" "),
                quotaGoalRatio = pacingGoal?.let { used?.divide(it, MathContext.DECIMAL128) },
                warnings = listOfNotNull(
                    reading.warning(false),
                    if (showEstimate && estimate?.total == null)
                        estimate?.explanation ?: "${if (weekly) "Weekly" else "Five-hour"} token budget estimate unavailable" else null
                ),
                neutralRanges = if (amount.isEmpty()) emptyList() else listOf(percentText.length..percentText.length)
            )
        )
    }

    private fun MutableList<StatusValue>.addReset(
        options: AnalyticsDisplayOptions,
        duration: Int,
        reading: QuotaSelection,
        state: StatusValueState
    ) {
        val weekly = duration == 10080
        if ((if (weekly) options.showReset else options.showFiveHourReset) && QuotaEligibility.metric(
                options.capabilities(),
                duration,
                reset = true
            ).enabled
        ) {
            val reset = reading.resetDuration()
            val seconds =
                reading.reading?.usage?.resetsAt?.let { end -> reading.refreshedAt?.let { end - it.epochSecond } }
            add(
                StatusValue(
                    "reset:$duration",
                    if (weekly) options.weeklyResetLabel else options.fiveHourResetLabel,
                    reset ?: "unavailable",
                    seconds?.toBigDecimal(),
                    if (reset == null) StatusValueState.UNAVAILABLE else state,
                    if (weekly) options.weeklyResetStyle else options.fiveHourResetStyle,
                    reading.warning(true).orEmpty()
                )
            )
        }
    }

    private fun styledValue(
        value: StatusValue,
        preferences: DisplayPreferences,
        options: AnalyticsDisplayOptions
    ): StatusValue {
        val neutralStyle = if (StatusValueStyle.forDisplay(value.style).hideLabel) "|true|" else ""
        return if (value.id.startsWith("quota:")) value.copy(
            style = neutralStyle,
            palette = if (preferences.coloringEnabled) preferences.palette else null,
            aboveQuotaMultiplier = preferences.aboveQuotaMultiplier,
            warningQuotaMultiplier = preferences.warningQuotaMultiplier,
            quotaColorEnabled = preferences.coloringEnabled && (value.id != "quota:10080" || options.colorWeeklyQuota),
            quotaGoalRatio = if (value.id == "quota:300") value.quotaGoalRatio else value.raw?.divide(BigDecimal(100))?.let { used ->
                if (value.id == "quota:10080" && options.quotaPercentMode == QuotaPercentMode.REMAINING)
                    used.multiply(preferences.warningQuotaMultiplier.toBigDecimal()) else used
            })
        else value.copy(
            style = neutralStyle,
            palette = if (preferences.coloringEnabled) preferences.palette else null,
            aboveQuotaMultiplier = preferences.aboveQuotaMultiplier,
            warningQuotaMultiplier = preferences.warningQuotaMultiplier
        )
    }

    fun trackerValue(
        result: TrackerResult,
        now: Instant,
        preferences: DisplayPreferences = DisplayPreferences(),
        quotaMetric: QuotaMetricPresentation = QuotaMetricPresentation(
            null,
            "No estimated weekly token budget available."
        ),
        fiveHourMetric: QuotaMetricPresentation? = null,
    ): StatusValue {
        val range =
            if (result.definition.unit in setOf(SpanUnit.MINUTE, SpanUnit.HOUR) && result.definition.showPeriodStart)
                hourlyRange(result, now, DisplayFormats(preferences))?.let { "$it: " }.orEmpty() else ""
        val value = StatusValue(
            result.definition.id,
            result.definition.name,
            result.total?.let(::compact) ?: "unavailable",
            result.total?.toBigDecimal(),
            if (result.total == null) StatusValueState.UNAVAILABLE else StatusValueState.AVAILABLE,
            if (result.definition.hideLabel || StatusValueStyle.forDisplay(result.definition.style).hideLabel) "|true|" else "",
            result.issues.joinToString("; ")
        )
        val arranged = quotaMetric.tracker(value, result.definition, preferences, fiveHourMetric)
        return arranged.copy(
            text = range + arranged.text,
            fiveHourColor = arranged.fiveHourColor?.let { it.copy(start = it.start + range.length) },
            neutralRanges = if (range.isEmpty()) arranged.neutralRanges else
                listOf(range.indices) + arranged.neutralRanges.map { (it.first + range.length)..(it.last + range.length) },
            quotaColorEnabled = preferences.coloringEnabled && arranged.quotaColorEnabled,
            palette = if (preferences.coloringEnabled) preferences.palette else null,
            aboveQuotaMultiplier = preferences.aboveQuotaMultiplier,
            warningQuotaMultiplier = preferences.warningQuotaMultiplier,
        )
    }

    fun trackerText(
        result: TrackerResult,
        now: Instant,
        preferences: DisplayPreferences = DisplayPreferences()
    ): String =
        trackerValue(result, now, preferences).displayText()

    fun tooltip(snapshot: UsageSnapshot): String =
        if (snapshot.operationalIssues.isNotEmpty() || snapshot.collection.operationalIssues.isNotEmpty() || snapshot.trackers.any { it.issues.isNotEmpty() })
            "Open usage details to review current operational warnings and available tracker values."
        else "Open usage actions, settings, and detailed information for the displayed trackers."

    private fun hourlyRange(result: TrackerResult, now: Instant, format: DisplayFormats): String? {
        val interval = result.period.intervals.singleOrNull() ?: return null
        val zone = result.period.zone
        val end = PeriodPresentation.displayEnd(interval, now)
        val start = interval.start.atZone(zone)
        val finish = end.atZone(zone)
        val showOffsets = start.offset != finish.offset ||
                zone.rules.getValidOffsets(start.toLocalDateTime()).size > 1 ||
                zone.rules.getValidOffsets(finish.toLocalDateTime()).size > 1
        val startText = format.shortEndpoint(interval.start, now, zone, showOffsets)
        val endText = if (PeriodPresentation.endsNow(result, interval, now)) "now"
        else format.shortEndpoint(end, now, zone, showOffsets)
        return "$startText - $endText"
    }

    fun compact(total: Long): String {
        val divisor = when {
            total >= 1_000_000_000 -> 1_000_000_000L to "B"
            total >= 1_000_000 -> 1_000_000L to "M"
            total >= 1_000 -> 1_000L to "K"
            else -> return total.toString()
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(divisor.first), 1, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString() + divisor.second
    }

    fun compactDecimal(value: BigDecimal): String {
        val (divisor, suffix) = when {
            value >= BigDecimal("1000000000") -> BigDecimal("1000000000") to "B"
            value >= BigDecimal("1000000") -> BigDecimal("1000000") to "M"
            value >= BigDecimal("1000") -> BigDecimal("1000") to "K"
            else -> BigDecimal.ONE to ""
        }
        return value.divide(divisor, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + suffix
    }
}

object PeriodPresentation {
    fun displayEnd(interval: UsageInterval, now: Instant): Instant =
        if (interval.start > now) interval.end else maxOf(interval.start, minOf(interval.end, now))

    fun endsNow(result: TrackerResult, interval: UsageInterval, now: Instant): Boolean =
        interval.start <= now && interval.start != interval.end && when (result.definition.mode) {
            PeriodMode.ELAPSED -> result.definition.endAnchor == EndAnchor.NOW && interval.end == now
            PeriodMode.CALENDAR, PeriodMode.REPEATING -> result.definition.includeCurrent && interval.end > now
            PeriodMode.FIXED -> interval.end > now
            PeriodMode.PROVIDER_WEEK, PeriodMode.PROVIDER_FIVE_HOUR -> interval.end > now
        }

    fun describe(
        result: TrackerResult,
        now: Instant,
        preferences: DisplayPreferences = DisplayPreferences(),
        limit: Int = 20,
        includeMillions: Boolean = false,
    ): String = buildString {
        val format = DisplayFormats(preferences)
        val zone = result.period.zone
        val totalText = result.total?.let {
            val millions = if (includeMillions) " (${
                BigDecimal.valueOf(it).movePointLeft(6).setScale(1, RoundingMode.HALF_UP).toPlainString()
            }M)" else ""
            "$it$millions tokens"
        } ?: "unavailable"
        append("• ${result.definition.name}: $totalText\n")
        if (!result.definition.visible) append("• Hidden from status bar\n")
        result.period.intervals.take(limit).forEach { interval ->
            append("• Period: ${format.dateTime(interval.start, zone, true)} → ")
            append(format.dateTime(displayEnd(interval, now), zone, true))
            if (interval.start > now) append(" (future period)")
            append('\n')
        }
        if (result.period.intervals.size > limit) append("• ${result.period.intervals.size} intervals selected; first $limit shown.\n")
        if (result.period.intervals.isEmpty()) append("• No intervals selected\n")
        result.issues.sorted().forEach { append("• $it\n") }
    }.trimEnd()
}

object UsageInfoText {
    fun describe(
        snapshot: UsageSnapshot,
        preferences: DisplayPreferences,
        systemZone: ZoneId,
        analyticsDisplay: AnalyticsDisplayOptions = AnalyticsDisplayOptions()
    ): String = buildString {
        val format = DisplayFormats(preferences)
        val quotaMetric = QuotaMetricPresentation.select(snapshot, analyticsDisplay)
        if (snapshot.loading) append("• Loading usage\n")
        if (snapshot.trackers.isEmpty()) append("• No configured tracker results available\n")
        snapshot.trackers.forEach {
            append(
                PeriodPresentation.describe(
                    it,
                    snapshot.evaluatedAt,
                    preferences,
                    Int.MAX_VALUE,
                    includeMillions = true
                )
            ).append("\n\n")
            if (it.definition.colorByQuotaGoal && quotaMetric.applicable) {
                QuotaMetricPresentation.goal(it.definition)?.let { goal ->
                    append(
                        "• Color allowance: ${
                            goal.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        }% of the illustrative weekly budget\n"
                    )
                }
                append(
                    "• ${
                        WidgetText.trackerValue(
                            it,
                            snapshot.evaluatedAt,
                            preferences,
                            QuotaMetricPresentation.forTracker(snapshot, analyticsDisplay, it.definition)
                        ).description
                    }\n\n"
                )
            }
        }
        append(AnalyticsPresentation.describe(snapshot, preferences, systemZone, analyticsDisplay)).append('\n')
        append(
            "• Last completed collection: ${
                snapshot.collection.lastCompleted?.let {
                    format.dateTime(
                        it,
                        systemZone
                    )
                } ?: "none"
            }\n"
        )
        append(
            "• Last warning-free collection: ${
                snapshot.collection.lastSuccess?.let {
                    format.dateTime(
                        it,
                        systemZone
                    )
                } ?: "none"
            }\n")
        append(
            "• Totals calculated: ${
                if (snapshot.evaluatedAt == Instant.EPOCH) "not yet" else format.dateTime(
                    snapshot.evaluatedAt,
                    systemZone
                )
            }\n"
        )
        append(
            "• Earliest recorded usage: ${
                snapshot.historyStart?.let {
                    format.dateTime(
                        it,
                        systemZone
                    )
                } ?: "none"
            }\n")
        append("• Usage folders found: ${snapshot.collection.homes}\n")
        append("• Live log files: ${snapshot.collection.files}\n")
        append("• Stored usage events: ${snapshot.storedEvents}\n")
        append("• Database: ${snapshot.databasePath.ifBlank { "not yet available" }}\n")
        if (snapshot.collection.homes == 0) append("• No configured live source found. Check this account's source settings.\n")
    }.trimEnd()
}

object DisclaimersText {
    fun describe(trackers: List<TrackerDefinition>, systemZone: ZoneId): String = buildString {
        append("• System local time zone: $systemZone\n")
        append("• Collection timestamps use the system local time zone. Period boundaries use each tracker's resolved time zone and show UTC offsets.\n")
        trackers.forEach {
            val resolved = it.zoneId.ifBlank { "$systemZone (system)" }
            append("• ${it.name} time zone: $resolved\n")
        }
        append("\n• Sources: this account's configured local usage logs and sanitized vendor reports. Coverage depends on the supported client/capture path; missing clients are not inferred. No model calls, billing API or collection telemetry.\n")
        append("• Cached input is included. These are locally recorded token totals, not account-wide usage or billing costs. Period totals do not retain separate counter breakdowns; request observations retain reported breakdowns where available.\n")
        append("• Last request means one recorded model response, not a full prompt or turn. Response records provide identity; older reports require a clean cumulative-counter cross-check and remain labeled as legacy evidence. Missing identity, resets, malformed data or newer unverified activity can leave only a last-known reading.\n")
        append("• Weekly and five-hour quotas show provider-reported usage for their respective windows. Choose used or remaining for each in Settings. The reset countdown stays fixed between collections. Values older than 15 minutes are stale, and a passed reset makes the reading unavailable until a new observation. Refresh Now rereads local logs without contacting the provider.\n")
        append("• Logs may not identify the quota account. Such observations remain separate by source session and bucket; percentages are never summed. Optional token budgets are estimates, not provider allowances. Reset timestamps use the provider's instant, displayed in the current system zone.\n")
        append("• Imported history is retained when logs disappear. Logs deleted before import cannot be recovered; a stored zero does not certify complete coverage.\n")
        append("• Ambiguous copies, conflicting histories and recognized forks are conservatively excluded and reported in Usage Info. Malformed records, counter resets and missing sources can leave coverage incomplete.\n")
        append("• Source logs are local records, not authenticated usage statements. The local database relies on operating-system access controls and stores no prompts, responses or credentials.\n")
    }.trimEnd()
}
