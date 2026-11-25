package local.aiusagetrackers

import java.time.Instant

data class ResetWindow(
    val interval: UsageInterval,
    val inferred: Boolean,
    val scheduled: Boolean,
    val label: String = "Weekly",
) {
    val issues: Set<String>
        get() = buildSet {
            add("Local usage across all days; provider account coverage is not established")
            if (inferred) add("$label start inferred from reported duration and reset; early resets may change this interval")
            if (scheduled) add("Scheduled ${label.lowercase()} rollover; awaiting a fresh provider observation")
        }
}

object ResetWindowResolver {
    fun resolve(reading: QuotaObservation?, now: Instant, windowMinutes: Int = 10080): ResetWindow? {
        val label = when (windowMinutes) {
            10080 -> "Weekly"
            300 -> "Five-hour"
            else -> return null
        }
        if (reading == null || reading.usage.windowMinutes != windowMinutes || reading.time > now) return null
        val seconds = windowMinutes * 60L
        val reset = reading.usage.resetsAt?.let(Instant::ofEpochSecond) ?: return null
        if (reset <= reading.time) return null
        if (windowMinutes == 300 && (reading.usage.disabled || reading.time < reset.minusSeconds(seconds))) return null
        if (now < reset) return observedWindow(reading, reset, seconds, label)
        val index = Math.floorDiv(now.epochSecond - reset.epochSecond, seconds)
        val start = reset.plusSeconds(Math.multiplyExact(index, seconds))
        return ResetWindow(UsageInterval(start, start.plusSeconds(seconds)), inferred = false, scheduled = true, label)
    }

    fun forBudget(reading: QuotaObservation): ResetWindow? = resolve(reading, reading.time, reading.usage.windowMinutes)

    private fun observedWindow(reading: QuotaObservation, reset: Instant, seconds: Long, label: String): ResetWindow {
        val inferredStart = reset.minusSeconds(seconds)
        val observedStart = reading.previousReset?.let(Instant::ofEpochSecond)
            ?.takeIf { it >= inferredStart && it <= reading.time && it < reset }
        return ResetWindow(UsageInterval(observedStart ?: inferredStart, reset), observedStart == null, false, label)
    }
}
