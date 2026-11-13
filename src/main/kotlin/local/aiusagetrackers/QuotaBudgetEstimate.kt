package local.aiusagetrackers

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

data class QuotaBudgetEstimate(
    val observedAt: Instant, val resetsAt: Instant?, val tokens: Long?, val usedPercent: Double?,
    val total: BigDecimal? = null, val remaining: BigDecimal? = null,
    val explanation: String,
) {
    fun amount(mode: QuotaPercentMode): BigDecimal? =
        if (mode == QuotaPercentMode.USED) tokens?.toBigDecimal() else remaining

    companion object {
        fun calculate(
            reading: QuotaObservation,
            tokens: Long?,
            window: ResetWeek?,
            issues: Set<String> = emptySet()
        ): QuotaBudgetEstimate {
            val p = reading.usage.usedPercent
            val reset = reading.usage.resetsAt?.let(Instant::ofEpochSecond)
            val reason = when {
                reading.discontinuity -> "Reported usage decreased without an established new weekly boundary; estimate invalidated"
                window == null || window.scheduled -> "Weekly boundary unavailable for this observation"
                p == null || !p.isFinite() || p < 1 || p > 100 -> "Estimate requires at least 1% reported weekly usage"
                tokens == null || tokens <= 0 -> "No positive local weekly token usage available for this observation"
                else -> null
            }
            if (reason != null) return QuotaBudgetEstimate(reading.time, reset, null, p, explanation = reason)
            val budget = tokens!!.toBigDecimal().multiply(BigDecimal(100))
                .divide(BigDecimal.valueOf(p!!), MathContext.DECIMAL128)
            val remaining = budget.multiply(BigDecimal(100).subtract(BigDecimal.valueOf(p))).divide(BigDecimal(100))
            return QuotaBudgetEstimate(
                reading.time, reset, tokens, p, budget, remaining,
                buildSet {
                    addAll(window!!.issues); addAll(issues)
                    add(
                        "Approximate budget from cumulative local tokens / observed quota used. " +
                                "Includes weekends and holidays; missing sources, rounded percentages and changing usage mix can bias this estimate. " +
                                "It reflects the average usage mix so far, not a fixed token allowance or a forecast for a newly selected model/mode."
                    )
                }.joinToString("; ")
            )
        }
    }
}
