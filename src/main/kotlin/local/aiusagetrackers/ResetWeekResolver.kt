package local.aiusagetrackers

import java.time.Instant

data class ResetWeek(val interval: UsageInterval, val inferred: Boolean, val scheduled: Boolean) {
    val issues: Set<String>
        get() = buildSet {
            add("Local usage across all days; provider account coverage is not established")
            if (inferred) add("Weekly start inferred from reported duration and reset; early resets may change this interval")
            if (scheduled) add("Scheduled weekly rollover; awaiting a fresh provider observation")
        }
}

object ResetWeekResolver {
    private const val SECONDS = 10080L * 60

    fun resolve(reading: QuotaObservation?, now: Instant): ResetWeek? {
        if (reading == null || reading.usage.windowMinutes != 10080 || reading.time > now) return null
        val reset = reading.usage.resetsAt?.let(Instant::ofEpochSecond) ?: return null
        if (reset <= reading.time) return null
        val inferredStart = reset.minusSeconds(SECONDS)
        val observedStart = reading.previousReset?.let(Instant::ofEpochSecond)
            ?.takeIf { it >= inferredStart && it <= reading.time && it < reset }
        if (now < reset) return ResetWeek(
            UsageInterval(observedStart ?: inferredStart, reset),
            observedStart == null,
            false
        )
        val index = Math.floorDiv(now.epochSecond - reset.epochSecond, SECONDS)
        val start = reset.plusSeconds(Math.multiplyExact(index, SECONDS))
        return ResetWeek(UsageInterval(start, start.plusSeconds(SECONDS)), inferred = false, scheduled = true)
    }
}
