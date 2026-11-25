package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant


/** Presentation-only shares of an illustrative token budget. */
data class QuotaMetricPresentation(val budget: BigDecimal?, val explanation: String, val stale: Boolean = false,
    val reset: Instant? = null, val evaluatedAt: Instant? = null,
    val applicable: Boolean = true) {
    fun tracker(
        value: StatusValue,
        definition: TrackerDefinition,
        preferences: DisplayPreferences,
        fiveHour: QuotaMetricPresentation? = null,
    ): StatusValue {
        val goal = goal(definition)
        val shortBudget = fiveHour.takeIf { definition.showFiveHourPercentUsed && definition.supportsFiveHourShare() }
        val fiveHourColorMinutes = if (shortBudget != null && definition.colorByQuotaGoal &&
            preferences.coloringEnabled && preferences.separateFiveHourColors) {
            if (definition.unit == SpanUnit.HOUR) definition.amount * 60 else definition.amount
        } else null
        val base = apply(
            value, preferences.allTokenPercentUsed || definition.showPercentUsed, goal,
            definition.colorByQuotaGoal && goal != null, definition.metricLayout(),
            shortBudget, fiveHourColorMinutes,
        )
        if (!applicable || !preferences.weeklyPacing || !definition.colorByQuotaGoal ||
            definition.mode != PeriodMode.PROVIDER_WEEK || goal == null) return base
        val end = reset ?: return base.copy(quotaGoalRatio = null, warnings = base.warnings + "Weekly pacing requires a current reset observation")
        val now = evaluatedAt ?: return base.copy(quotaGoalRatio = null)
        if (now >= end || now < end.minusSeconds(604800) || budget == null || budget <= BigDecimal.ZERO) return base.copy(quotaGoalRatio = null)
        val remaining = Duration.between(now, end)
        // Below six whole days remaining starts the second allowance day.
        val allowanceDays = (7 - remaining.toDays()).coerceIn(1, 7).toBigDecimal()
        val target = BigDecimal(100).multiply(allowanceDays).divide(BigDecimal(7), MathContext.DECIMAL128)
        val ratio = value.raw?.takeIf { it >= BigDecimal.ZERO }?.multiply(BigDecimal(7))
            ?.divide(budget.multiply(allowanceDays), MathContext.DECIMAL128)
        return base.copy(quotaGoalRatio = ratio, description = base.description + " Weekly pacing target: ${target.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%.")
    }
    fun apply(
        value: StatusValue,
        showPercent: Boolean,
        goal: BigDecimal? = null,
        colorEnabled: Boolean = false,
        layout: TokenMetricLayout = TokenMetricLayout(),
        fiveHour: QuotaMetricPresentation? = null,
        fiveHourColorMinutes: Int? = null,
    ): StatusValue {
        val weeklyVisible = applicable && showPercent
        val weeklyActive = applicable && (showPercent || colorEnabled)
        val weeklyPercent = if (weeklyActive) share(value.raw) else null
        val shortBudget = fiveHour?.takeIf { it.applicable }
        val fiveHourPercent = shortBudget?.share(value.raw)
        val parts = layout.parts(
            value.text,
            if (weeklyVisible) formatShare(weeklyPercent) else null,
            shortBudget?.let { formatShare(fiveHourPercent) },
        )
        val fiveHourColor = fiveHourColorMinutes?.let { minutes ->
            val index = parts.indexOfFirst { it.first == TokenMetric.FIVE_HOUR }
            if (index < 0) null else QuotaColorSpan(
                start = parts.take(index).sumOf { it.second.length + 1 },
                length = parts[index].second.length,
                // One hour of the five-hour window allows 20 percent, or one percent per three minutes.
                ratio = fiveHourPercent?.multiply(BigDecimal(3))?.divide(minutes.toBigDecimal(), MathContext.DECIMAL128),
            )
        }
        var offset = 0
        val dividers = parts.dropLast(1).map { (_, text) ->
            val separator = offset + text.length
            offset = separator + 1
            separator..separator
        }
        val arranged = value.copy(
            text = parts.joinToString("|") { it.second }, fiveHourColor = fiveHourColor, neutralRanges = dividers,
        )
        val weeklyValue = if (weeklyActive) annotate(arranged).copy(
            quotaGoalRatio = goal?.let { weeklyPercent?.divide(it, MathContext.DECIMAL128) },
            quotaColorEnabled = colorEnabled,
        ) else arranged
        return shortBudget?.annotate(weeklyValue) ?: weeklyValue
    }

    private fun share(tokens: BigDecimal?): BigDecimal? =
        tokens?.takeIf { it >= BigDecimal.ZERO }?.let { used ->
            budget?.takeIf { it > BigDecimal.ZERO }?.let { used.multiply(BigDecimal(100)).divide(it, MathContext.DECIMAL128) }
        }

    private fun formatShare(percent: BigDecimal?): String =
        percent?.setScale(1, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.plus("%") ?: "—"

    private fun annotate(value: StatusValue): StatusValue =
        value.copy(
            description = listOf(value.description, explanation).filter { it.isNotEmpty() }.joinToString(" "),
            state = if (stale && value.state == StatusValueState.AVAILABLE) StatusValueState.STALE else value.state,
            warnings = value.warnings + if (budget == null || stale) listOf(explanation) else emptyList(),
        )

    companion object {
        fun forTracker(
            snapshot: UsageSnapshot, options: AnalyticsDisplayOptions, definition: TrackerDefinition,
            windowMinutes: Int = 10080,
        ): QuotaMetricPresentation =
            select(
                QuotaPresentation.forPool(snapshot, definition.quotaPoolId),
                if (definition.quotaPoolId.isBlank()) options else options.copy(quotaScope = "", fiveHourScope = ""),
                windowMinutes,
            )

        fun select(
            snapshot: UsageSnapshot, options: AnalyticsDisplayOptions, windowMinutes: Int = 10080,
        ): QuotaMetricPresentation {
            val label = if (windowMinutes == 300) "five-hour" else "weekly"
            if (!QuotaEligibility.budget(options.capabilities(), windowMinutes)) return QuotaMetricPresentation(null,
                "The selected plan does not support $label token-budget estimates.", applicable = false)
            val selected = QuotaPresentation.select(snapshot, options, windowMinutes)
            val reading = selected.reading
            val estimate = selected.matchingEstimate(snapshot)
            val budget = estimate?.total?.takeIf { it > BigDecimal.ZERO }
            return QuotaMetricPresentation(
                budget,
                if (budget == null) "$label percentage unavailable: ${selected.warning(false) ?: estimate?.explanation ?: "no usable token budget estimate"}"
                else "Approximate share of the selected $label token budget. ${estimate.explanation} ${selected.warning(false).orEmpty()}",
                selected.stale && budget != null,
                reading?.usage?.resetsAt?.let(Instant::ofEpochSecond), snapshot.evaluatedAt,
            )
        }

        fun goal(definition: TrackerDefinition): BigDecimal? {
            if (definition.amount < 1 || definition.mode in setOf(PeriodMode.FIXED, PeriodMode.REPEATING)) return null
            val rate = when (definition.unit) {
                SpanUnit.MINUTE -> {
                    if (definition.amount > 10080) return null
                    return BigDecimal("2.5").multiply(BigDecimal(definition.amount)).divide(BigDecimal(60), MathContext.DECIMAL128).min(BigDecimal(100))
                }
                SpanUnit.HOUR -> if (definition.amount <= 168) BigDecimal("2.5") else return null
                SpanUnit.DAY -> if (definition.amount <= 7) BigDecimal(20) else return null
                SpanUnit.WEEK -> if (definition.amount == 1) BigDecimal(100) else return null
                SpanUnit.MONTH -> return null
            }
            return rate.multiply(BigDecimal(definition.amount)).min(BigDecimal(100))
        }

        fun color(ratio: BigDecimal): Color = UsagePalette.parse(DisplayPreferences().palette).color(ratio)
    }
}
