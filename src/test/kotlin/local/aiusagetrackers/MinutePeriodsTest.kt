package local.aiusagetrackers

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinutePeriodsTest {
    @Test
    fun completedMinutesInTheRepeatedHourKeepTheirUtcOffset() {
        val zone = ZoneId.of("Europe/Budapest")
        val now = Instant.parse("2026-10-25T02:30:00Z")
        val tracker = TrackerDefinition(name = "Minute", unit = SpanUnit.MINUTE, showPeriodStart = true)
        for ((instant, offset) in listOf("2026-10-25T00:30:00Z" to "+02:00", "2026-10-25T01:30:00Z" to "+01:00")) {
            val start = Instant.parse(instant)
            val result =
                TrackerResult(tracker, ResolvedPeriod(listOf(UsageInterval(start, start.plusSeconds(60))), zone), 100)
            assertTrue(WidgetText.trackerText(result, now).contains("02:30 $offset - 02:31 $offset"))
        }
    }

    private val resolver = UsagePeriodResolver()
    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-04T12:34:30.123456789Z")

    @Test
    fun calendarOffsetNanosecondsAndCompletedPeriods() {
        val tracker = TrackerDefinition(unit = SpanUnit.MINUTE, startTime = "09:59:30.123456789")
        val current = resolver.resolve(tracker, now, utc).intervals.single()
        assertEquals(now, current.start)
        assertEquals(now.plusSeconds(60), current.end)
        val before = resolver.resolve(tracker, now.minusNanos(1), utc).intervals.single()
        assertEquals(now.minusSeconds(60), before.start)
        assertEquals(now, before.end)
        assertEquals(before, resolver.resolve(tracker.copy(includeCurrent = false), now, utc).intervals.single())
        assertEquals(
            Instant.parse("2026-11-03T23:59:00Z"), resolver.resolve(
                tracker.copy(startTime = "00:00"),
                Instant.parse("2026-11-04T00:00:00Z").minusNanos(1), utc
            ).intervals.single().start
        )
    }

    @Test
    fun elapsedAndRepeatingMinutesAreExactAcrossBothBudapestTransitions() {
        val zone = ZoneId.of("Europe/Budapest")
        for (instant in listOf("2026-03-29T01:30:00Z", "2026-10-25T01:30:00Z")) {
            val at = Instant.parse(instant)
            val tracker = TrackerDefinition(unit = SpanUnit.MINUTE, amount = 120, mode = PeriodMode.ELAPSED)
            assertEquals(
                Duration.ofMinutes(120),
                resolver.resolve(tracker, at, zone).intervals.single().let { Duration.between(it.start, it.end) })
            val repeating = tracker.copy(mode = PeriodMode.REPEATING, anchor = "2026-11-04T00:00:30.1")
            val interval = resolver.resolve(repeating, at, zone).intervals.single()
            assertEquals(Duration.ofMinutes(120), Duration.between(interval.start, interval.end))
            assertTrue(interval.start <= at && at < interval.end)
        }
    }

    @Test
    fun minuteEndAnchorAndHourEquivalenceUseSameRange() {
        val tracker = TrackerDefinition(
            unit = SpanUnit.MINUTE, amount = 60, mode = PeriodMode.ELAPSED,
            endAnchor = EndAnchor.MINUTE, endOffset = 2
        )
        val period = resolver.resolve(tracker, now, utc)
        assertEquals(Instant.parse("2026-11-04T12:32:00Z"), period.intervals.single().end)
        assertEquals(period, resolver.resolve(tracker.copy(unit = SpanUnit.HOUR, amount = 1), now, utc))
        assertTrue(UnitEquivalence.exactHint(tracker)!!.contains("1 elapsed hour"))
        assertFailsWith<IllegalArgumentException> { resolver.validate(tracker.copy(selection = DaySelection.WORKING)) }
    }

    @Test
    fun transferUsesCurrentVersionAndRejectsOlderFormats() {
        val tracker = TrackerDefinition(unit = SpanUnit.MINUTE, style = "#123456|true|100=#FF0000")
        val current = TrackerConfigurationJson.encode(TrackerConfiguration(listOf(tracker)))
        assertTrue(current.contains("\"version\": 11"))
        val decoded = TrackerConfigurationJson.decode(current).trackers.single()
        assertEquals(tracker.copy(id = decoded.id), decoded)
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.decode(
                current.replace(
                    "\"version\": 11",
                    "\"version\": 1"
                )
            )
        }
        val hourly = TrackerDefinition(unit = SpanUnit.HOUR)
        assertEquals(0, TrackerOrdering.insertionIndex(listOf(hourly), tracker.copy(style = "")))
    }

    @Test
    fun fractionalEndpointsStayDistinctInBothClockFormats() {
        for (clock in ClockFormat.entries) {
            val text = DisplayFormats(DisplayPreferences(clockFormat = clock)).time(now, utc, true)
            assertTrue(text.contains("34:30.123456789"))
            if (clock == ClockFormat.TWELVE_HOUR) assertTrue(text.endsWith(" PM"))
        }
    }
}
