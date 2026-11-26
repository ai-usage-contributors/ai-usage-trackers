package local.aiusagetrackers

import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters
import com.intellij.util.xmlb.annotations.Property
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

enum class QuotaPercentMode(private val label: String) {
    USED("Percentage used"), REMAINING("Percentage remaining");

    override fun toString(): String = label
}

/** Settings only; no source paths, account identities or credentials. */
data class AnalyticsDisplayOptions(
    var lastRequestLabel: String = "Last request",
    var showQuota: Boolean = false,
    var showReset: Boolean = false,
    var quotaPercentMode: QuotaPercentMode = QuotaPercentMode.USED,
    var quotaScope: String = "",
    var showFiveHourQuota: Boolean = false,
    var showFiveHourReset: Boolean = false,
    var fiveHourScope: String = "",
    var estimateBudget: Boolean = false,
    var lastRequestPercentUsed: Boolean = false,
    var lastRequestStyle: String = "",
    var weeklyQuotaStyle: String = "",
    var weeklyResetStyle: String = "",
    var fiveHourQuotaStyle: String = "",
    var fiveHourResetStyle: String = "",
    var weeklyQuotaLabel: String = "Weekly quota",
    var weeklyResetLabel: String = "Weekly reset",
    var fiveHourQuotaLabel: String = "5h quota",
    var fiveHourResetLabel: String = "5h reset",
    var colorWeeklyQuota: Boolean = false,
    var planProfile: QuotaPlanProfile = QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR,
    var showReportedPools: Boolean = false,
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var fiveHourPercentMode: QuotaPercentMode? = null,
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var estimateFiveHourBudget: Boolean = false,
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var lastRequestFiveHourPercentUsed: Boolean = false,
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var lastRequestMetricOrder: TokenMetricOrder = TokenMetricOrder.TOKENS_WEEKLY_FIVE_HOUR,
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var lastRequestTokenPrefix: String = "",
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var lastRequestWeeklyPrefix: String = "",
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var lastRequestFiveHourPrefix: String = "",
) {
    fun capabilities(): PlanCapabilities = planProfile.capabilities()

    fun percentMode(windowMinutes: Int): QuotaPercentMode =
        if (windowMinutes == 300) fiveHourPercentMode ?: quotaPercentMode else quotaPercentMode

    fun showsBudgetEstimate(windowMinutes: Int): Boolean = QuotaEligibility.budget(capabilities(), windowMinutes) &&
        if (windowMinutes == 300) estimateFiveHourBudget else estimateBudget

    fun lastRequestMetricLayout(): TokenMetricLayout =
        TokenMetricLayout(lastRequestMetricOrder, lastRequestTokenPrefix, lastRequestWeeklyPrefix, lastRequestFiveHourPrefix)

    fun validate() {
        listOf(weeklyQuotaLabel, weeklyResetLabel, fiveHourQuotaLabel, fiveHourResetLabel).forEach { label ->
            require(label.isNotBlank() && label.length <= 80 && label.none(Char::isISOControl)) {
                "Status labels must contain 1–80 characters without control characters"
            }
        }
        require(lastRequestLabel.isNotBlank() && lastRequestLabel.length <= 80 && lastRequestLabel.none(Char::isISOControl)) {
            "Last request label must contain 1–80 characters without control characters"
        }
        require(quotaScope.length <= 512 && quotaScope.none(Char::isISOControl)) { "Invalid quota observation scope" }
        require(fiveHourScope.length <= 512 && fiveHourScope.none(Char::isISOControl)) { "Invalid five-hour observation scope" }
        lastRequestMetricLayout().validate()
        listOf(lastRequestStyle, weeklyQuotaStyle, weeklyResetStyle, fiveHourQuotaStyle, fiveHourResetStyle).forEach(
            StatusValueStyle::parse
        )
    }

    fun normalized(): AnalyticsDisplayOptions = try {
        copy(
            lastRequestLabel = lastRequestLabel.trim(),
            weeklyQuotaLabel = weeklyQuotaLabel.trim(),
            weeklyResetLabel = weeklyResetLabel.trim(),
            fiveHourQuotaLabel = fiveHourQuotaLabel.trim(),
            fiveHourResetLabel = fiveHourResetLabel.trim(),
            quotaScope = quotaScope.replace(Regex("/(primary|secondary)$"), "/10080")
        ).also { it.validate() }
    } catch (_: IllegalArgumentException) {
        copy(
            lastRequestLabel = "Last request",
            weeklyQuotaLabel = "Weekly quota",
            weeklyResetLabel = "Weekly reset",
            fiveHourQuotaLabel = "5h quota",
            fiveHourResetLabel = "5h reset",
            quotaScope = "",
            fiveHourScope = "",
            lastRequestStyle = "",
            weeklyQuotaStyle = "",
            weeklyResetStyle = "",
            fiveHourQuotaStyle = "",
            fiveHourResetStyle = "",
            lastRequestTokenPrefix = "",
            lastRequestWeeklyPrefix = "",
            lastRequestFiveHourPrefix = "",
        )
    }
}

data class QuotaSelection(
    val reading: QuotaObservation?,
    val refreshedAt: Instant?,
    val multipleScopes: Boolean,
    val windowMinutes: Int = 10080,
    val evaluatedAt: Instant? = null
) {
    val expired: Boolean
        get() = reading?.usage?.resetsAt?.let { reset ->
            listOfNotNull(evaluatedAt, refreshedAt).maxOrNull()?.let { reset <= it.epochSecond }
        } == true
    val stale: Boolean
        get() = reading?.usage?.freshnessUnknown == true || reading?.time?.let { observed ->
            listOfNotNull(refreshedAt, evaluatedAt).maxOrNull()?.let { Duration.between(observed, it) > Duration.ofMinutes(15) }
        } == true

    fun matchingEstimate(snapshot: UsageSnapshot): QuotaBudgetEstimate? {
        val observation = reading ?: return null
        if (expired || observation.discontinuity || observation.usage.disabled) return null
        return snapshot.budgetEstimates[QuotaPresentation.scope(observation)]?.takeIf {
            it.observedAt == observation.time && it.usedPercent == observation.usage.usedPercent &&
                it.resetsAt?.epochSecond == observation.usage.resetsAt
        }
    }

    fun percentage(mode: QuotaPercentMode): String? {
        if (expired || reading?.usage?.disabled == true) return null
        val used = reading?.usage?.usedPercent ?: return null
        val value = BigDecimal.valueOf(used).let {
            if (mode == QuotaPercentMode.REMAINING) BigDecimal.valueOf(100).subtract(it) else it
        }
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"
    }

    fun fiveHourGoalPercent(): BigDecimal? {
        val reset = reading?.usage?.resetsAt?.let(Instant::ofEpochSecond) ?: return null
        val now = evaluatedAt?.takeUnless { it == Instant.EPOCH } ?: refreshedAt ?: return null
        val remaining = Duration.between(now, reset)
        if (remaining <= Duration.ZERO || remaining > Duration.ofHours(5)) return null
        // Advance after a whole-hour boundary, as weekly pacing does for days.
        val allowanceHours = (5 - remaining.toHours()).coerceAtLeast(1)
        return BigDecimal.valueOf(20 * allowanceHours)
    }

    fun resetDuration(): String? {
        if (expired) return null
        val reset = reading?.usage?.resetsAt ?: return null
        val at = refreshedAt ?: return null
        val minutes = Duration.between(at, Instant.ofEpochSecond(reset)).toMinutes()
        return "${minutes / 1440}d ${minutes / 60 % 24}h ${minutes % 60}m"
    }

    fun warning(reset: Boolean): String? = when {
        refreshedAt == null -> "Usage hasn’t finished updating yet."
        reading == null -> "No ${windowLabel()} quota reading for the selected source."
        reading.usage.disabled -> "The provider reports this quota pool as disabled."
        expired -> "The ${windowLabel()} reset has passed. Waiting for a new quota reading."
        reset && reading.usage.resetsAt == null -> "The logs do not report a ${windowLabel()} reset timestamp."
        !reset && reading.usage.usedPercent == null -> "The logs do not report a ${windowLabel()} quota percentage."
        reading.usage.freshnessUnknown -> "The report shows when it was saved. The age of the quota reading is unknown."
        stale -> "Showing the last quota reading. It’s over 15 minutes old."
        else -> null
    }

    private fun windowLabel(): String = when (windowMinutes) { 300 -> "five-hour"; 10080 -> "weekly"; else -> "reported" }
}

object QuotaPresentation {
    fun forPool(snapshot: UsageSnapshot, poolId: String): UsageSnapshot = if (poolId.isBlank()) snapshot else
        snapshot.copy(analytics = snapshot.analytics.copy(quotaObservations = snapshot.analytics.quotaObservations.filter { it.usage.bucket == poolId }))
    fun scope(quota: QuotaObservation): String = "${quota.source}/${quota.usage.bucket}/${quota.usage.windowMinutes}"

    private fun matches(quota: QuotaObservation, pin: String): Boolean = pin.isEmpty() || scope(quota) == pin ||
            (quota.usage.windowMinutes == 10080 && pin in listOf(
                "primary",
                "secondary"
            ).map { "${quota.source}/${quota.usage.bucket}/$it" })

    fun select(
        snapshot: UsageSnapshot,
        options: AnalyticsDisplayOptions = AnalyticsDisplayOptions(),
        windowMinutes: Int = 10080
    ): QuotaSelection {
        val at = snapshot.collection.lastCompleted
        val cutoff = at?.let { if (snapshot.evaluatedAt == Instant.EPOCH) it else minOf(it, snapshot.evaluatedAt) }
        val pin = if (windowMinutes == 300) options.fiveHourScope else options.quotaScope
        val candidates =
            snapshot.analytics.quotaObservations.filter { it.usage.windowMinutes == windowMinutes && cutoff != null && it.time <= cutoff }
        val selected = candidates.filter { matches(it, pin) }
            .sortedWith(compareByDescending<QuotaObservation> { it.time }.thenBy(::scope)).firstOrNull()
        return QuotaSelection(
            selected,
            at,
            candidates.map(::scope).distinct().size > 1,
            windowMinutes,
            snapshot.evaluatedAt
        )
    }
}
