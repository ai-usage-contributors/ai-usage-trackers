package local.aiusagetrackers

import java.time.Duration
import java.time.Instant

enum class QuotaBoundaryKind { FIXED_DURATION, CALENDAR, ROLLING, UNKNOWN }

enum class QuotaPlanProfile(private val label: String) {
    CODEX_WEEKLY("Codex — weekly only"), CODEX_WEEKLY_AND_FIVE_HOUR("Codex — weekly and five-hour"),
    CLAUDE_WEEKLY("Claude — verified weekly only"),
    CLAUDE_WEEKLY_AND_FIVE_HOUR("Claude — weekly and five-hour"),
    ANTIGRAVITY_REPORTED("Antigravity — reported pools, unverified boundaries"),
    UNKNOWN("Unverified quota scheme");

    override fun toString(): String = label
    val presentationLabel: String get() = when (this) {
        CLAUDE_WEEKLY -> "Claude (experimental) — weekly only"
        CLAUDE_WEEKLY_AND_FIVE_HOUR -> "Claude (experimental) — weekly and five-hour"
        ANTIGRAVITY_REPORTED -> "Antigravity (experimental) — reported pools, unverified boundaries"
        else -> label
    }
    fun capabilities(): PlanCapabilities = when (this) {
        CODEX_WEEKLY -> PlanCapabilities.CODEX_WEEKLY
        CODEX_WEEKLY_AND_FIVE_HOUR -> PlanCapabilities(PlanCapabilities.CODEX_WEEKLY.pools +
            QuotaPool("five-hour", QuotaBoundaryKind.FIXED_DURATION, Duration.ofHours(5), percentage = true, resetInstant = true), illustrativeBudget = true)
        UNKNOWN -> PlanCapabilities.UNKNOWN
        CLAUDE_WEEKLY -> PlanCapabilities(listOf(QuotaPool("seven_day", QuotaBoundaryKind.FIXED_DURATION, Duration.ofDays(7), percentage = true, resetInstant = true)))
        CLAUDE_WEEKLY_AND_FIVE_HOUR -> PlanCapabilities(CLAUDE_WEEKLY.capabilities().pools + QuotaPool("five_hour", QuotaBoundaryKind.FIXED_DURATION, Duration.ofHours(5), percentage = true, resetInstant = true))
        ANTIGRAVITY_REPORTED -> PlanCapabilities.UNKNOWN
    }
    companion object {
        fun forVendor(vendor: UsageVendor): List<QuotaPlanProfile> = when (vendor) {
            UsageVendor.CODEX -> listOf(CODEX_WEEKLY, CODEX_WEEKLY_AND_FIVE_HOUR, UNKNOWN)
            UsageVendor.CLAUDE -> listOf(CLAUDE_WEEKLY, CLAUDE_WEEKLY_AND_FIVE_HOUR, UNKNOWN)
            UsageVendor.ANTIGRAVITY -> listOf(ANTIGRAVITY_REPORTED, UNKNOWN)
        }
    }
}

/** Provider billing identity is independent of the agent or model that runs a task. */
data class QuotaPool(
    val id: String,
    val boundary: QuotaBoundaryKind,
    val duration: Duration?,
    val percentage: Boolean,
    val resetInstant: Boolean,
) {
    val fixedWeek: Boolean get() = boundary == QuotaBoundaryKind.FIXED_DURATION && duration == Duration.ofDays(7)
}

data class PlanCapabilities(val pools: List<QuotaPool>, val illustrativeBudget: Boolean = false) {
    val weekly: Boolean get() = pools.any { it.fixedWeek }
    val fiveHour: Boolean get() = pools.any { it.duration == Duration.ofHours(5) }

    companion object {
        val CODEX_WEEKLY = PlanCapabilities(listOf(QuotaPool("weekly", QuotaBoundaryKind.FIXED_DURATION,
            Duration.ofDays(7), percentage = true, resetInstant = true)), illustrativeBudget = true)
        val UNKNOWN = PlanCapabilities(emptyList())
    }
}

/** Pure capability policy; temporary stale or missing observations never erase settings. */
object QuotaEligibility {
    data class Decision(val enabled: Boolean, val reason: String)
    fun metric(plan: PlanCapabilities, minutes: Int, reset: Boolean): Decision {
        val enabled = plan.pools.any { pool ->
            pool.duration == Duration.ofMinutes(minutes.toLong()) && (minutes != 10080 || pool.fixedWeek) &&
                if (reset) pool.resetInstant else pool.percentage
        }
        return Decision(enabled, if (enabled) "Supported; current data may still be unavailable" else
            "Requires a verified ${if (minutes == 10080) "fixed weekly" else "five-hour"} pool reporting ${if (reset) "reset timestamps" else "percentages"}")
    }
    fun window(plan: PlanCapabilities, minutes: Int): Boolean = plan.pools.any {
        it.duration == Duration.ofMinutes(minutes.toLong()) &&
            (minutes != 10080 || it.fixedWeek)
    }

    fun budget(plan: PlanCapabilities, minutes: Int = 10080): Boolean = plan.illustrativeBudget &&
        plan.pools.any { it.boundary == QuotaBoundaryKind.FIXED_DURATION &&
            it.duration == Duration.ofMinutes(minutes.toLong()) && it.percentage && it.resetInstant }

    fun resetAligned(plan: PlanCapabilities, minutes: Int): Boolean =
        if (minutes == 10080) plan.weekly else plan.pools.any {
            it.boundary == QuotaBoundaryKind.FIXED_DURATION && it.duration == Duration.ofMinutes(minutes.toLong()) && it.resetInstant
        }

    fun interval(pool: QuotaPool, reset: Instant): UsageInterval? =
        if (pool.fixedWeek) UsageInterval(reset.minus(pool.duration!!), reset) else null
}
