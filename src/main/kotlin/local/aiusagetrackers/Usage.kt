package local.aiusagetrackers

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayWindow(val date: LocalDate, val zone: ZoneId) {
    val start: Instant = date.atStartOfDay(zone).toInstant()
    val end: Instant = date.plusDays(1).atStartOfDay(zone).toInstant()

    companion object {
        fun current(clock: Clock, zone: ZoneId): DayWindow = DayWindow(clock.instant().atZone(zone).toLocalDate(), zone)
    }
}

data class Increment(val time: Instant, val total: Long)

data class ParsedSession(
    val id: String?,
    val digest: String,
    val increments: List<Increment>,
    val issues: Set<String>,
    val latest: Instant?,
)

data class SourceProblem(val fileName: String, val date: LocalDate?, val issues: Set<String>)

data class CollectionSnapshot(
    val sessions: List<ParsedSession> = emptyList(),
    val homes: Int = 0,
    val files: Int = 0,
    val issues: Set<String> = emptySet(),
    val lastSuccess: Instant? = null,
    val day: DayWindow? = null,
    val lastCompleted: Instant? = null,
    val sourceProblems: List<SourceProblem> = emptyList(),
    val operationalIssues: Set<String> = issues,
    val integrityIssues: Set<String> = emptySet(),
)

data class DailySnapshot(
    val day: DayWindow,
    val total: Long,
    val collection: CollectionSnapshot,
    val issues: Set<String>,
    val loading: Boolean,
)

class DailyUsageCalculator(private val clock: Clock, private val zone: () -> ZoneId) {
    fun calculate(collection: CollectionSnapshot, loading: Boolean = false): DailySnapshot {
        val now = clock.instant()
        val day = DayWindow.current(clock, zone())
        val issues = collection.issues.toMutableSet()
        if (collection.day != null && collection.day != day) issues += "Rebuilding usage for the current local day"
        var total = 0L
        for ((_, _, increments, sessionIssues, latest) in collection.sessions) {
            issues += sessionIssues
            if (latest?.isAfter(now) == true) issues += "Future-dated usage excluded"
            for ((time, amount) in increments) {
                if (time >= day.start && time < day.end && time <= now) {
                    try {
                        total = Math.addExact(total, amount)
                    } catch (_: ArithmeticException) {
                        issues += "Daily total exceeds supported integer range"
                    }
                }
            }
        }
        return DailySnapshot(day, total, collection, issues, loading)
    }
}
