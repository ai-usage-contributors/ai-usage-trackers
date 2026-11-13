package local.aiusagetrackers

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsagePeriodTest {
    private val resolver = UsagePeriodResolver()
    private val now = Instant.parse("2026-10-28T14:00:00Z")
    private val utc = ZoneOffset.UTC
    private fun resolve(definition: TrackerDefinition, instant: Instant = now) =
        resolver.resolve(definition, instant, utc)

    @Test
    fun twoAndThreeDaysIncludeToday() {
        val two = resolve(TrackerDefinition(amount = 2))
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), two.intervals.first().start)
        assertEquals(38, Duration.between(two.intervals.first().start, now).toHours())
        assertEquals(
            Instant.parse("2026-10-26T00:00:00Z"),
            resolve(TrackerDefinition(amount = 3)).intervals.first().start
        )
        val completed = resolve(TrackerDefinition(amount = 2, includeCurrent = false))
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), completed.intervals.first().start)
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), completed.intervals.last().end)
    }

    @Test
    fun weeklyBoundariesHaveNoGapAndCrossYear() {
        val week = TrackerDefinition(unit = SpanUnit.WEEK)
        val interval = resolve(week).intervals.single()
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), interval.start)
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), interval.end)
        assertEquals(interval.end, resolve(week, interval.end).intervals.single().start)
        val year = resolve(week, Instant.parse("2027-01-01T12:00:00Z")).intervals.single()
        assertEquals(Instant.parse("2026-12-28T00:00:00Z"), year.start)
    }

    @Test
    fun workingDaysExcludeWeekendAndCountCurrentEligibleDay() {
        val tracker = TrackerDefinition(amount = 2, selection = DaySelection.WORKING)
        fun dates(instant: String) =
            resolve(tracker, Instant.parse(instant)).intervals.map { it.start.atZone(utc).toLocalDate().toString() }
        assertEquals(listOf("2026-10-23", "2026-10-26"), dates("2026-10-26T14:00:00Z"))
        assertEquals(listOf("2026-10-22", "2026-10-23"), dates("2026-10-24T14:00:00Z"))
        assertEquals(listOf("2026-10-27", "2026-10-28"), dates("2026-10-28T14:00:00Z"))
    }

    @Test
    fun elapsedDurationsAndEndAnchors() {
        val elapsed = TrackerDefinition(amount = 2, mode = PeriodMode.ELAPSED)
        assertEquals(Instant.parse("2026-10-26T14:00:00Z"), resolve(elapsed).intervals.single().start)
        val anchored = resolve(elapsed.copy(endAnchor = EndAnchor.DAY, endOffset = 1)).intervals.single()
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), anchored.start)
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), anchored.end)
        assertEquals(
            168,
            Duration.between(resolve(elapsed.copy(amount = 1, unit = SpanUnit.WEEK)).intervals.single().start, now)
                .toHours()
        )
    }

    @Test
    fun monthsUseCalendarAndLeapYear() {
        val months = TrackerDefinition(amount = 2, unit = SpanUnit.MONTH)
        val interval = resolve(months, Instant.parse("2024-03-15T12:00:00Z")).intervals.single()
        assertEquals(Instant.parse("2024-02-01T00:00:00Z"), interval.start)
        assertEquals(Instant.parse("2024-04-01T00:00:00Z"), interval.end)
        assertEquals(60, Duration.between(interval.start, interval.end).toDays())
    }

    @Test
    fun repeatingMonthsKeepOriginalAnchorWithoutDrift() {
        val monthly = TrackerDefinition(mode = PeriodMode.REPEATING, unit = SpanUnit.MONTH, anchor = "2026-01-31T09:15")
        val february = resolve(monthly, Instant.parse("2026-03-01T00:00:00Z")).intervals.single()
        assertEquals(Instant.parse("2026-02-28T09:15:00Z"), february.start)
        assertEquals(Instant.parse("2026-03-31T09:15:00Z"), february.end)
        assertEquals(february.end, resolve(monthly, february.end).intervals.single().start)
        assertEquals(
            Instant.parse("2025-12-31T09:15:00Z"),
            resolve(monthly, Instant.parse("2026-01-01T00:00:00Z")).intervals.single().start
        )
    }

    @Test
    fun explicitTimesAndOvernightWindows() {
        val fixed = TrackerDefinition(
            mode = PeriodMode.FIXED,
            fixedStart = "2026-10-27T09:30:15",
            fixedEnd = "2026-10-28T17:45:20"
        )
        val interval = resolve(fixed).intervals.single()
        assertEquals(Instant.parse("2026-10-27T09:30:15Z"), interval.start)
        assertEquals(Instant.parse("2026-10-28T17:45:20Z"), interval.end)
        val overnight = resolve(
            TrackerDefinition(startTime = "22:00", endTime = "06:00"),
            Instant.parse("2026-10-28T02:00:00Z")
        ).intervals.single()
        assertEquals(Instant.parse("2026-10-27T22:00:00Z"), overnight.start)
        assertEquals(Instant.parse("2026-10-28T06:00:00Z"), overnight.end)
    }

    @Test
    fun hourlyResetOffset() {
        val result = resolve(TrackerDefinition(unit = SpanUnit.HOUR, startTime = "00:30")).intervals.single()
        assertEquals(Instant.parse("2026-10-28T13:30:00Z"), result.start)
        assertEquals(Instant.parse("2026-10-28T14:30:00Z"), result.end)
    }

    @Test
    fun dstCalendarAndElapsedRemainDistinct() {
        val zone = ZoneId.of("Europe/Budapest")
        for ((instant, length) in listOf("2026-03-29T12:00:00Z" to 23L, "2026-10-25T12:00:00Z" to 25L)) {
            val at = Instant.parse(instant)
            val day = resolver.resolve(TrackerDefinition(), at, zone).intervals.single()
            assertEquals(length, Duration.between(day.start, day.end).toHours())
            val elapsed = resolver.resolve(TrackerDefinition(mode = PeriodMode.ELAPSED), at, zone).intervals.single()
            assertEquals(24, Duration.between(elapsed.start, elapsed.end).toHours())
        }
        val repeatedHour = resolver.resolve(
            TrackerDefinition(unit = SpanUnit.HOUR),
            Instant.parse("2026-10-25T01:30:00Z"),
            zone
        ).intervals.single()
        assertEquals(Instant.parse("2026-10-25T01:00:00Z"), repeatedHour.start)
        val missingTime = resolver.resolve(
            TrackerDefinition(startTime = "02:30"),
            Instant.parse("2026-03-29T12:00:00Z"),
            zone
        ).intervals.single()
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), missingTime.start)
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), missingTime.end)
    }

    @Test
    fun fixedZoneOverridesRefreshedSystemZone() {
        val definition = TrackerDefinition(zoneId = "UTC")
        assertEquals(resolve(definition), resolver.resolve(definition, now, ZoneId.of("Pacific/Kiritimati")))
    }

    @Test
    fun activeDatesAreBoundedAndInsufficientHistoryExplained() {
        val definition = TrackerDefinition(amount = 3, selection = DaySelection.ACTIVE)
        val result = resolver.resolve(definition, now, utc) { _, _, _ ->
            listOf(
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 9, 1)
            )
        }
        assertEquals(2, result.intervals.size)
        assertEquals(1, result.issues.size)
    }

    @Test
    fun invalidCombinationsAndValuesRejected() {
        for (definition in listOf(
            TrackerDefinition(amount = 0),
            TrackerDefinition(mode = PeriodMode.ELAPSED, unit = SpanUnit.MONTH),
            TrackerDefinition(unit = SpanUnit.HOUR, selection = DaySelection.WORKING),
            TrackerDefinition(unit = SpanUnit.WEEK, endTime = "17:00")
        )) {
            assertFailsWith<IllegalArgumentException> { resolver.validate(definition) }
        }
    }

    @Test
    fun customBoundaryAndRepeatingAnchorRecoverAfterDstGap() {
        val zone = ZoneId.of("Europe/Budapest")
        val at = Instant.parse("2026-03-29T12:00:00Z")
        val elapsed =
            TrackerDefinition(mode = PeriodMode.ELAPSED, startTime = "02:30", endAnchor = EndAnchor.DAY, endOffset = 1)
        assertEquals(Instant.parse("2026-03-28T01:30:00Z"), resolver.resolve(elapsed, at, zone).intervals.single().end)
        val repeating = TrackerDefinition(mode = PeriodMode.REPEATING, anchor = "2026-03-29T02:30")
        val following = resolver.resolve(repeating, Instant.parse("2026-03-30T12:00:00Z"), zone).intervals.single()
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), following.start)
        assertEquals(Instant.parse("2026-03-31T00:30:00Z"), following.end)
    }

    @Test
    fun invertedAndEmptyGapWindowsResolveToZeroWithExplanation() {
        val zone = ZoneId.of("Europe/Budapest")
        val at = Instant.parse("2026-03-29T12:00:00Z")
        for (end in listOf("03:15", "03:30")) {
            val tracker = TrackerDefinition(startTime = "02:30", endTime = end)
            resolver.validate(tracker)
            val period = resolver.resolve(tracker, at, zone)
            val interval = period.intervals.single()
            assertEquals(interval.start, interval.end)
            assertEquals(1, period.issues.size)
        }
        val following = resolver.resolve(
            TrackerDefinition(startTime = "02:30", endTime = "03:15"),
            Instant.parse("2026-03-30T12:00:00Z"),
            zone
        )
        assertEquals(
            45,
            Duration.between(following.intervals.single().start, following.intervals.single().end).toMinutes()
        )
        assertEquals(emptySet(), following.issues)
    }

    @Test
    fun gapEndpointsAndAutumnOverlapsUseConsistentIntervals() {
        val zone = ZoneId.of("Europe/Budapest")
        val tracker = TrackerDefinition(startTime = "02:10", endTime = "02:40")
        for (at in listOf("2026-03-29T12:00:00Z", "2026-10-25T12:00:00Z")) {
            val period = resolver.resolve(tracker, Instant.parse(at), zone)
            val interval = period.intervals.single()
            assertEquals(30, Duration.between(interval.start, interval.end).toMinutes())
            assertEquals(emptySet(), period.issues)
        }
        val fixed = tracker.copy(
            mode = PeriodMode.FIXED,
            endTime = "",
            fixedStart = "2026-03-29T02:30",
            fixedEnd = "2026-03-29T03:15"
        )
        val interval = resolver.resolve(fixed, Instant.parse("2026-03-29T12:00:00Z"), zone).intervals.single()
        assertEquals(interval.start, interval.end)
    }
}
