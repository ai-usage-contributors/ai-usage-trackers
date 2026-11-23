package local.aiusagetrackers

import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters
import com.intellij.util.xmlb.annotations.Property
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class RibbonPlacement { AUTOMATIC, FIXED, RIBBON }

enum class SpanUnit(val label: String) {
    MINUTE("Minutes"), HOUR("Hours"), DAY("Days"), WEEK("Weeks"), MONTH("Months");

    override fun toString() = label
}

enum class PeriodMode(val label: String) {
    CALENDAR("Calendar periods"), ELAPSED("Elapsed duration"), REPEATING("Repeating from anchor"), FIXED("Explicit start and end"), PROVIDER_WEEK(
        "Provider reset-aligned week (all days)"
    );

    override fun toString() = label
}

enum class DaySelection(val label: String) {
    ALL("All days"), WORKING("Monday–Friday"), ACTIVE("Days with usage");

    override fun toString() = label
}

enum class EndAnchor(val label: String) {
    NOW("Now"), MINUTE("Start of minute"), HOUR("Start of hour"), DAY("Start of day"), WEEK("Start of week"), MONTH("Start of month");

    override fun toString() = label
}

/** Mutable bean for IntelliJ's settings serializer; copies isolate readers from the settings UI. */
data class TrackerDefinition(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Today",
    var amount: Int = 1,
    var unit: SpanUnit = SpanUnit.DAY,
    var mode: PeriodMode = PeriodMode.CALENDAR,
    var selection: DaySelection = DaySelection.ALL,
    var includeCurrent: Boolean = true,
    var zoneId: String = "",
    var startTime: String = "00:00",
    var endTime: String = "",
    var anchor: String = "2026-01-01T00:00",
    var fixedStart: String = "",
    var fixedEnd: String = "",
    var endAnchor: EndAnchor = EndAnchor.NOW,
    var endOffset: Int = 0,
    var visible: Boolean = true,
    var showPeriodStart: Boolean = false,
    var style: String = "",
    var showPercentUsed: Boolean = false,
    var colorByQuotaGoal: Boolean = false,
    var hideLabel: Boolean = false,
    var ribbonPlacement: RibbonPlacement = RibbonPlacement.AUTOMATIC,
    var accountId: String = ProviderProfile.LEGACY_CODEX,
    var quotaPoolId: String = "",
    @get:Property(filter = SkipDefaultValuesSerializationFilters::class)
    var resetDailyAtWeeklyReset: Boolean = false,
) {
    fun fixedInWidget(): Boolean = when (ribbonPlacement) {
        RibbonPlacement.FIXED -> true
        RibbonPlacement.RIBBON -> false
        RibbonPlacement.AUTOMATIC -> amount == 1 && unit in setOf(SpanUnit.WEEK, SpanUnit.DAY, SpanUnit.HOUR)
    }

    fun usesLegacySchedule(): Boolean = mode in setOf(PeriodMode.FIXED, PeriodMode.REPEATING) || startTime != "00:00" || endTime.isNotEmpty()

    fun supportsDailyWeeklyReset(): Boolean =
        mode == PeriodMode.CALENDAR && unit == SpanUnit.DAY && amount == 1 && includeCurrent && !usesLegacySchedule()

    fun automaticSchedule(): TrackerDefinition = copy(mode = PeriodMode.CALENDAR, startTime = "00:00", endTime = "",
        anchor = "2026-01-01T00:00", fixedStart = "", fixedEnd = "", includeCurrent = true,
        selection = DaySelection.ALL, endAnchor = EndAnchor.NOW, endOffset = 0)

    fun sharedPresentation(): TrackerDefinition = copy(style = "", hideLabel = hideLabel || StatusValueStyle.forDisplay(style).hideLabel)

    companion object {
        fun defaults(): List<TrackerDefinition> = listOf(
            TrackerDefinition(id = "today", name = "Today"),
            TrackerDefinition(id = "week", name = "This week", unit = SpanUnit.WEEK),
        )
    }
}

data class UsageInterval(val start: Instant, val end: Instant) {
    init {
        require(start <= end)
    }
}

data class ResolvedPeriod(val intervals: List<UsageInterval>, val zone: ZoneId, val issues: Set<String> = emptySet())
data class TrackerResult(
    val definition: TrackerDefinition,
    val period: ResolvedPeriod,
    val total: Long?,
    val issues: Set<String> = emptySet()
)

data class UsageSnapshot(
    val trackers: List<TrackerResult> = emptyList(),
    val collection: CollectionSnapshot = CollectionSnapshot(),
    val issues: Set<String> = emptySet(),
    val loading: Boolean = true,
    val storedEvents: Long = 0,
    val evaluatedAt: Instant = Instant.EPOCH,
    val databasePath: String = "",
    val historyStart: Instant? = null,
    val analytics: UsageAnalytics = UsageAnalytics(),
    val budgetEstimates: Map<String, QuotaBudgetEstimate> = emptyMap(),
    val acknowledgedWarnings: Set<String> = emptySet(),
    val acknowledgedIntegrity: Set<String> = emptySet(),
    val agreementRequired: Boolean = false,
    val operationalIssues: Set<String> = issues,
    val integrityIssues: Set<String> = emptySet(),
)

class UsagePeriodResolver {
    fun validate(definition: TrackerDefinition) {
        require(definition.accountId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Select a valid account binding" }
        require(definition.quotaPoolId.length <= 256 && definition.quotaPoolId.none(Char::isISOControl)) { "Invalid quota pool binding" }
        require(definition.name.isNotBlank()) { "Give the tracker a name" }
        StatusValueStyle.parse(definition.style)
        require(
            definition.mode != PeriodMode.PROVIDER_WEEK ||
                    (definition.unit == SpanUnit.WEEK && definition.amount == 1 && definition.includeCurrent)
        ) {
            "Provider reset-aligned tracking requires one current week"
        }
        require(definition.amount in 1..1000) { "Amount must be between 1 and 1000" }
        require(definition.endOffset in 0..1000) { "Boundary offset must be between 0 and 1000" }
        if (definition.zoneId.isNotBlank()) ZoneId.of(definition.zoneId)
        LocalTime.parse(definition.startTime)
        if (definition.endTime.isNotBlank()) LocalTime.parse(definition.endTime)
        require(definition.selection == DaySelection.ALL || (definition.mode == PeriodMode.CALENDAR && definition.unit == SpanUnit.DAY)) {
            "Working-day and active-day selection requires calendar days"
        }
        require(definition.endTime.isBlank() || (definition.mode == PeriodMode.CALENDAR && definition.unit == SpanUnit.DAY)) {
            "A daily window end requires calendar days; use explicit endpoints for other custom ranges"
        }
        require(definition.mode != PeriodMode.ELAPSED || definition.unit != SpanUnit.MONTH) {
            "Months have variable length; choose calendar periods or repeating months"
        }
        require(definition.mode != PeriodMode.ELAPSED || definition.endAnchor != EndAnchor.NOW || definition.endOffset == 0) {
            "Now does not have a boundary offset"
        }
        if (definition.mode == PeriodMode.REPEATING) LocalDateTime.parse(definition.anchor)
        if (definition.mode == PeriodMode.FIXED) {
            require(LocalDateTime.parse(definition.fixedStart) < LocalDateTime.parse(definition.fixedEnd)) { "End must be after start" }
        }
    }

    fun resolve(
        definition: TrackerDefinition,
        now: Instant,
        systemZone: ZoneId,
        activeDates: (TrackerDefinition, ZoneId, Instant) -> List<LocalDate> = { _, _, _ -> emptyList() },
    ): ResolvedPeriod {
        validate(definition)
        require(definition.mode != PeriodMode.PROVIDER_WEEK) { "Select a weekly quota observation in Analytics settings; provider context is required" }
        val zone = definition.zoneId.takeIf(String::isNotBlank)?.let(ZoneId::of) ?: systemZone
        val local = now.atZone(zone)
        val time = LocalTime.parse(definition.startTime)
        return when (definition.mode) {
            PeriodMode.PROVIDER_WEEK -> error("Provider context required")
            PeriodMode.FIXED -> rangeResult(
                LocalDateTime.parse(definition.fixedStart).atZone(zone),
                LocalDateTime.parse(definition.fixedEnd).atZone(zone),
                zone
            )
            PeriodMode.ELAPSED -> elapsed(definition, local, time)
            PeriodMode.REPEATING -> repeating(definition, now, local, zone)
            PeriodMode.CALENDAR -> calendar(definition, now, local, time, activeDates)
        }
    }

    private fun elapsed(definition: TrackerDefinition, local: ZonedDateTime, time: LocalTime): ResolvedPeriod {
        val endUnit = when (definition.endAnchor) {
            EndAnchor.NOW -> null
            EndAnchor.MINUTE -> SpanUnit.MINUTE
            EndAnchor.HOUR -> SpanUnit.HOUR
            EndAnchor.DAY -> SpanUnit.DAY
            EndAnchor.WEEK -> SpanUnit.WEEK
            EndAnchor.MONTH -> SpanUnit.MONTH
        }
        val end = endUnit?.let {
            stepBoundary(boundary(local, it, time), it, -definition.endOffset.toLong(), time)
        } ?: local
        val minutes = definition.amount.toLong() * when (definition.unit) {
            SpanUnit.MINUTE -> 1
            SpanUnit.HOUR -> 60
            SpanUnit.DAY -> 1440
            SpanUnit.WEEK -> 10080
            SpanUnit.MONTH -> error("Calendar month required")
        }
        return rangeResult(end.minusMinutes(minutes), end, local.zone)
    }

    private fun calendar(
        definition: TrackerDefinition,
        now: Instant,
        local: ZonedDateTime,
        time: LocalTime,
        activeDates: (TrackerDefinition, ZoneId, Instant) -> List<LocalDate>,
    ): ResolvedPeriod {
        val unit = definition.unit
        val current = boundary(local, unit, time)
        if (unit == SpanUnit.DAY) return calendarDays(definition, now, local.zone, current, activeDates)
        val end = stepBoundary(current, unit, if (definition.includeCurrent) 1 else 0, time)
        return rangeResult(stepBoundary(end, unit, -definition.amount.toLong(), time), end, local.zone)
    }

    private fun stepBoundary(value: ZonedDateTime, unit: SpanUnit, amount: Long, time: LocalTime): ZonedDateTime {
        val stepped = add(value, unit, amount)
        return when (unit) {
            SpanUnit.MINUTE, SpanUnit.HOUR -> stepped
            else -> stepped.toLocalDate().atTime(time).atZone(value.zone)
        }
    }

    private fun rangeResult(start: ZonedDateTime, end: ZonedDateTime, zone: ZoneId): ResolvedPeriod {
        val interval = safeInterval(start, end)
        return ResolvedPeriod(listOf(interval), zone, collapseIssues(listOf(interval), zone))
    }
    private fun repeating(definition: TrackerDefinition, now: Instant, local: ZonedDateTime, zone: ZoneId): ResolvedPeriod {
        val localAnchor = LocalDateTime.parse(definition.anchor)
        val anchor = localAnchor.atZone(zone)
        fun occurrence(index: Long): ZonedDateTime {
            val amount = Math.multiplyExact(index, definition.amount.toLong())
            return when (definition.unit) {
                SpanUnit.MINUTE -> anchor.plusMinutes(amount)
                SpanUnit.HOUR -> anchor.plusHours(amount)
                SpanUnit.DAY -> localAnchor.plusDays(amount).atZone(zone)
                SpanUnit.WEEK -> localAnchor.plusWeeks(amount).atZone(zone)
                SpanUnit.MONTH -> localAnchor.plusMonths(amount).atZone(zone)
            }
        }

        var index = when (definition.unit) {
            SpanUnit.MINUTE -> Math.floorDiv(
                Duration.between(anchor.toInstant(), now).seconds,
                60L * definition.amount
            )

            SpanUnit.HOUR -> Math.floorDiv(
                Duration.between(anchor.toInstant(), now).seconds,
                3600L * definition.amount
            )

            SpanUnit.DAY -> Math.floorDiv(
                ChronoUnit.DAYS.between(anchor.toLocalDate(), local.toLocalDate()),
                definition.amount.toLong()
            )

            SpanUnit.WEEK -> Math.floorDiv(
                ChronoUnit.DAYS.between(anchor.toLocalDate(), local.toLocalDate()),
                7L * definition.amount
            )

            SpanUnit.MONTH -> Math.floorDiv(
                ChronoUnit.MONTHS.between(
                    anchor.toLocalDate().withDayOfMonth(1),
                    local.toLocalDate().withDayOfMonth(1)
                ), definition.amount.toLong()
            )
        }
        while (occurrence(index).toInstant() > now) index--
        while (occurrence(index + 1).toInstant() <= now) index++
        if (!definition.includeCurrent) index--
        return rangeResult(occurrence(index), occurrence(index + 1), zone)
    }
    private fun calendarDays(definition: TrackerDefinition, now: Instant, zone: ZoneId, current: ZonedDateTime, activeDates: (TrackerDefinition, ZoneId, Instant) -> List<LocalDate>): ResolvedPeriod {
        var date = current.toLocalDate().minusDays(if (definition.includeCurrent) 0 else 1)
        val dates = when (definition.selection) {
            DaySelection.ACTIVE -> activeDates(definition, zone, now).distinct().filter { it <= date }
                .sortedDescending().take(definition.amount)

            else -> buildList {
                while (size < definition.amount) {
                    if (definition.selection == DaySelection.ALL || date.dayOfWeek !in setOf(
                            DayOfWeek.SATURDAY,
                            DayOfWeek.SUNDAY
                        )
                    ) add(date)
                    date = date.minusDays(1)
                }
            }
        }
        val intervals = dates.sorted().map { dayInterval(it, definition, zone) }
        val issues = collapseIssues(intervals, zone) +
                if (dates.size < definition.amount) setOf("Only ${dates.size} active days available in imported history") else emptySet()
        return ResolvedPeriod(intervals, zone, issues)
    }

    fun dayInterval(date: LocalDate, definition: TrackerDefinition, zone: ZoneId): UsageInterval {
        val time = LocalTime.parse(definition.startTime)
        val start = date.atTime(time).atZone(zone)
        val endTime = definition.endTime.takeIf(String::isNotBlank)?.let(LocalTime::parse)
        val end = if (endTime == null) date.plusDays(1).atTime(time).atZone(zone)
        else date.plusDays(if (endTime <= time) 1 else 0).atTime(endTime).atZone(zone)
        return safeInterval(start, end)
    }

    // Gap resolution can push a requested start past its end. Keep the interval
    // empty and explain it, rather than counting outside the requested window.
    private fun safeInterval(start: ZonedDateTime, end: ZonedDateTime): UsageInterval =
        UsageInterval(start.toInstant(), maxOf(start.toInstant(), end.toInstant()))

    private fun collapseIssues(intervals: List<UsageInterval>, zone: ZoneId): Set<String> =
        intervals.filter { it.start == it.end }.map {
            "Window on ${it.start.atZone(zone).toLocalDate()} collapses after time-zone adjustment; no usage counted"
        }.toSet()

    fun dayOf(instant: Instant, definition: TrackerDefinition, zone: ZoneId): LocalDate =
        boundary(instant.atZone(zone), SpanUnit.DAY, LocalTime.parse(definition.startTime)).toLocalDate()

    private fun boundary(local: ZonedDateTime, unit: SpanUnit, time: LocalTime): ZonedDateTime {
        var start = when (unit) {
            SpanUnit.MINUTE -> local.withSecond(time.second).withNano(time.nano)
            SpanUnit.HOUR -> local.withMinute(time.minute).withSecond(time.second).withNano(time.nano)
            SpanUnit.DAY -> local.toLocalDate().atTime(time).atZone(local.zone)
            SpanUnit.WEEK -> local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atTime(time)
                .atZone(local.zone)

            SpanUnit.MONTH -> local.toLocalDate().withDayOfMonth(1).atTime(time).atZone(local.zone)
        }
        if (start.toInstant() > local.toInstant()) {
            val previous = add(start, unit, -1)
            start =
                if (unit == SpanUnit.HOUR || unit == SpanUnit.MINUTE) previous else previous.toLocalDate().atTime(time)
                    .atZone(local.zone)
        }
        return start
    }

    private fun add(time: ZonedDateTime, unit: SpanUnit, amount: Long): ZonedDateTime = when (unit) {
        SpanUnit.MINUTE -> time.plusMinutes(amount)
        SpanUnit.HOUR -> time.plusHours(amount)
        SpanUnit.DAY -> time.plusDays(amount)
        SpanUnit.WEEK -> time.plusWeeks(amount)
        SpanUnit.MONTH -> time.plusMonths(amount)
    }
}
