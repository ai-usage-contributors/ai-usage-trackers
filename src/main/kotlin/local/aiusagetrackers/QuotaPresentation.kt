package local.aiusagetrackers

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
) {
    fun capabilities(): PlanCapabilities = planProfile.capabilities()

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
            fiveHourResetStyle = ""
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

    fun percentage(mode: QuotaPercentMode): String? {
        if (expired || reading?.usage?.disabled == true) return null
        val used = reading?.usage?.usedPercent ?: return null
        val value = BigDecimal.valueOf(used).let {
            if (mode == QuotaPercentMode.REMAINING) BigDecimal.valueOf(100).subtract(it) else it
        }
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"
    }

    fun resetDuration(): String? {
        if (expired) return null
        val reset = reading?.usage?.resetsAt ?: return null
        val at = refreshedAt ?: return null
        val minutes = Duration.between(at, Instant.ofEpochSecond(reset)).toMinutes()
        return "${minutes / 1440}d ${minutes / 60 % 24}h ${minutes % 60}m"
    }

    fun warning(reset: Boolean): String? = when {
        refreshedAt == null -> "No completed background data refresh is available."
        reading == null -> "No ${windowLabel()} quota observation is available for the selected scope."
        reading.usage.disabled -> "The provider reports this quota pool as disabled."
        expired -> "The observed ${windowLabel()} reset has passed. Awaiting a new provider observation."
        reset && reading.usage.resetsAt == null -> "The logs do not report a ${windowLabel()} reset timestamp."
        !reset && reading.usage.usedPercent == null -> "The logs do not report a ${windowLabel()} quota percentage."
        reading.usage.freshnessUnknown -> "Provider observation age is unknown; callback capture time does not establish freshness."
        stale -> "The provider observation is over 15 minutes old; displayed data is last observed."
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
