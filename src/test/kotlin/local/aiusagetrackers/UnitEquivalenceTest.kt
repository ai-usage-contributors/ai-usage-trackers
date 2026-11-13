package local.aiusagetrackers

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitEquivalenceTest {
    private val resolver = UsagePeriodResolver()
    private val zone = ZoneId.of("Europe/Budapest")

    @Test
    fun elapsedHintsRepresentIdenticalRangesAcrossDstAndCustomEndBoundaries() {
        for (now in listOf("2026-03-29T04:10:00Z", "2026-10-25T04:10:00Z")) {
            for (end in EndAnchor.entries) {
                val hours = TrackerDefinition(
                    amount = 48, unit = SpanUnit.HOUR, mode = PeriodMode.ELAPSED,
                    endAnchor = end, endOffset = if (end == EndAnchor.NOW) 0 else 1, startTime = "01:15"
                )
                val days = hours.copy(amount = 2, unit = SpanUnit.DAY)
                assertNotNull(UnitEquivalence.exactHint(hours))
                assertEquals(
                    resolver.resolve(hours, Instant.parse(now), zone),
                    resolver.resolve(days, Instant.parse(now), zone)
                )
                val weekDays = hours.copy(amount = 14, unit = SpanUnit.DAY)
                assertEquals(
                    resolver.resolve(weekDays, Instant.parse(now), zone),
                    resolver.resolve(weekDays.copy(amount = 2, unit = SpanUnit.WEEK), Instant.parse(now), zone)
                )
            }
        }
    }

    @Test
    fun anchoredDaysToWeeksRemainEquivalentIncludingCompletedPeriods() {
        for (current in listOf(true, false)) {
            val days = TrackerDefinition(
                amount = 14, unit = SpanUnit.DAY, mode = PeriodMode.REPEATING,
                anchor = "2026-03-20T02:30", includeCurrent = current
            )
            val now = Instant.parse("2026-04-03T06:00:00Z")
            assertTrue(UnitEquivalence.exactHint(days)!!.contains("2 repeating weeks"))
            assertEquals(
                resolver.resolve(days, now, zone),
                resolver.resolve(days.copy(amount = 2, unit = SpanUnit.WEEK), now, zone)
            )
        }
    }

    @Test
    fun calendarHoursAndDaysAreNotEquivalentEvenWithoutDst() {
        val hours = TrackerDefinition(amount = 24, unit = SpanUnit.HOUR)
        val days = hours.copy(amount = 1, unit = SpanUnit.DAY)
        val now = Instant.parse("2026-11-02T12:30:00Z")
        assertNull(UnitEquivalence.exactHint(hours))
        assertTrue(resolver.resolve(hours, now, zone) != resolver.resolve(days, now, zone))
        assertNull(UnitEquivalence.exactHint(hours.copy(mode = PeriodMode.REPEATING)))
        assertNull(UnitEquivalence.exactHint(TrackerDefinition(amount = 7)))
        assertNull(UnitEquivalence.exactHint(hours.copy(mode = PeriodMode.FIXED)))
    }

    @Test
    fun nonMultiplesWeeksAndMonthsHaveNoHint() {
        assertNull(
            UnitEquivalence.exactHint(
                TrackerDefinition(
                    amount = 25,
                    unit = SpanUnit.HOUR,
                    mode = PeriodMode.ELAPSED
                )
            )
        )
        assertNull(
            UnitEquivalence.exactHint(
                TrackerDefinition(
                    amount = 8,
                    unit = SpanUnit.DAY,
                    mode = PeriodMode.ELAPSED
                )
            )
        )
        assertNull(
            UnitEquivalence.exactHint(
                TrackerDefinition(
                    amount = 4,
                    unit = SpanUnit.WEEK,
                    mode = PeriodMode.ELAPSED
                )
            )
        )
        assertNull(UnitEquivalence.exactHint(TrackerDefinition(unit = SpanUnit.MONTH)))
    }
}
