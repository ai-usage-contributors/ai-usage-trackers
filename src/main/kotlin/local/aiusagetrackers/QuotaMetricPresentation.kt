package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode


/** Presentation-only shares of an illustrative weekly budget; never changes token accounting. */
data class QuotaMetricPresentation(val budget: BigDecimal?, val explanation: String, val stale: Boolean = false,
    val reset: java.time.Instant? = null, val evaluatedAt: java.time.Instant? = null,
    val applicable: Boolean = true) {
    fun tracker(value: StatusValue, definition: TrackerDefinition, preferences: DisplayPreferences): StatusValue {
        if (!applicable) return value
        val goal = goal(definition)
        val base = apply(value, preferences.allTokenPercentUsed || definition.showPercentUsed, goal, definition.colorByQuotaGoal && goal != null)
        if (!preferences.weeklyPacing || !definition.colorByQuotaGoal || definition.mode != PeriodMode.PROVIDER_WEEK || goal == null) return base
        val end = reset ?: return base.copy(quotaGoalRatio = null, warnings = base.warnings + "Weekly pacing requires a current reset observation")
        val now = evaluatedAt ?: return base.copy(quotaGoalRatio = null)
        if (now >= end || now < end.minusSeconds(604800) || budget == null || budget <= BigDecimal.ZERO) return base.copy(quotaGoalRatio = null)
        val remaining = java.time.Duration.between(now, end)
        // Below six whole days remaining starts the second allowance day.
        val allowanceDays = (7 - remaining.toDays()).coerceIn(1, 7).toBigDecimal()
        val target = BigDecimal(100).multiply(allowanceDays).divide(BigDecimal(7), MathContext.DECIMAL128)
        val ratio = value.raw?.takeIf { it >= BigDecimal.ZERO }?.multiply(BigDecimal(7))
            ?.divide(budget.multiply(allowanceDays), MathContext.DECIMAL128)
        return base.copy(quotaGoalRatio = ratio, description = base.description + " Weekly pacing target: ${target.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%.")
    }
    fun apply(value: StatusValue, showPercent: Boolean, goal: BigDecimal? = null, colorEnabled: Boolean = false): StatusValue {
        if (!applicable) return value
        if (!showPercent && !colorEnabled) return value
        val percent = value.raw?.takeIf { it >= BigDecimal.ZERO }?.let { tokens ->
            budget?.takeIf { it > BigDecimal.ZERO }?.let { tokens.multiply(BigDecimal(100)).divide(it, MathContext.DECIMAL128) }
        }
        return value.copy(
            text = value.text + if (showPercent) "|${percent?.setScale(1, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.plus("%") ?: "—"}" else "",
            description = listOf(value.description, explanation).filter { it.isNotEmpty() }.joinToString(" "),
            state = if (stale && value.state == StatusValueState.AVAILABLE) StatusValueState.STALE else value.state,
            quotaGoalRatio = goal?.let { percent?.divide(it, MathContext.DECIMAL128) },
            quotaColorEnabled = colorEnabled,
            warnings = value.warnings + if (budget == null || stale) listOf(explanation) else emptyList(),
        )
    }

    companion object {
        fun forTracker(snapshot: UsageSnapshot, options: AnalyticsDisplayOptions, definition: TrackerDefinition): QuotaMetricPresentation =
            select(QuotaPresentation.forPool(snapshot, definition.quotaPoolId), if (definition.quotaPoolId.isBlank()) options else options.copy(quotaScope = ""))
        fun select(snapshot: UsageSnapshot, options: AnalyticsDisplayOptions): QuotaMetricPresentation {
            if (!QuotaEligibility.budget(options.capabilities())) return QuotaMetricPresentation(null,
                "The selected plan does not support weekly token-budget estimates.", applicable = false)
            val selected = QuotaPresentation.select(snapshot, options)
            val reading = selected.reading
            val estimate = reading?.let { snapshot.budgetEstimates[QuotaPresentation.scope(it)] }
                ?.takeIf { it.observedAt == reading.time && it.usedPercent == reading.usage.usedPercent &&
                    it.resetsAt?.epochSecond == reading.usage.resetsAt && !reading.discontinuity }
            val budget = estimate?.total?.takeIf { !selected.expired && it > BigDecimal.ZERO }
            return QuotaMetricPresentation(
                budget,
                if (budget == null) "Percentage unavailable: ${selected.warning(false) ?: unavailableExplanation(estimate)}"
                else "Approximate share of the selected weekly token budget. ${estimate.explanation} ${selected.warning(false).orEmpty()}",
                selected.stale && budget != null,
                reading?.usage?.resetsAt?.let(java.time.Instant::ofEpochSecond), snapshot.evaluatedAt,
            )
        }

        private fun unavailableExplanation(estimate: QuotaBudgetEstimate?): String =
            estimate?.explanation ?: "no usable estimated weekly token budget"

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
