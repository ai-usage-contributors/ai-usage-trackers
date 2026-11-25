package local.aiusagetrackers

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerOrderingTest {
    @Test
    fun sortsConfiguredDurationsInEitherDirectionWithStableTies() {
        val trackers = listOf(
            TrackerDefinition(id = "long", name = "A", unit = SpanUnit.HOUR, amount = 25),
            TrackerDefinition(id = "minutes", name = "Z", unit = SpanUnit.MINUTE, amount = 60, visible = false),
            TrackerDefinition(id = "hour", unit = SpanUnit.HOUR),
            TrackerDefinition(id = "day", unit = SpanUnit.DAY),
            TrackerDefinition(id = "fixed", mode = PeriodMode.FIXED, zoneId = "UTC",
                fixedStart = "2026-11-24T00:00", fixedEnd = "2026-11-24T00:30"),
        )
        assertEquals(listOf("fixed", "minutes", "hour", "day", "long"), TrackerOrdering.sortByDuration(trackers, true).map { it.id })
        assertEquals(listOf("long", "day", "minutes", "hour", "fixed"), TrackerOrdering.sortByDuration(trackers, false).map { it.id })
    }

    @Test
    fun previewMovesVisibleTrackersWhileKeepingHiddenSlotsAndIdentities() {
        val trackers = listOf("a", "b", "c", "d").map {
            TrackerDefinition(id = it, name = "Same label", visible = it != "b")
        }
        assertEquals(listOf("d", "b", "a", "c"), TrackerOrdering.moveVisible(trackers, "d", 0).map { it.id })
        assertEquals(listOf("c", "b", "d", "a"), TrackerOrdering.moveVisible(trackers, "a", 3).map { it.id })
        assertEquals(trackers, TrackerOrdering.moveVisible(trackers, "c", 1))
        assertEquals(trackers, TrackerOrdering.moveVisible(trackers, "c", 2))
        for (source in listOf("b", "missing")) assertEquals(trackers, TrackerOrdering.moveVisible(trackers, source, 0))
        for (target in listOf(-1, 4)) assertEquals(trackers, TrackerOrdering.moveVisible(trackers, "a", target))
        val duplicate = trackers + trackers.first()
        assertEquals(duplicate, TrackerOrdering.moveVisible(duplicate, "a", 0))
    }

    private fun tracker(unit: SpanUnit, amount: Int = 1) = TrackerDefinition(unit = unit, amount = amount)
    private fun inserted(existing: List<SpanUnit>, added: SpanUnit): List<SpanUnit> {
        val list = existing.map { tracker(it) }.toMutableList()
        list.add(TrackerOrdering.insertionIndex(list, tracker(added)), tracker(added))
        return list.map { it.unit }
    }

    @Test
    fun suppliedExamplesAndDescendingMiddleInsertion() {
        val hour = SpanUnit.HOUR
        val day = SpanUnit.DAY
        val week = SpanUnit.WEEK
        assertEquals(listOf(hour, day, week), inserted(listOf(day, week), hour))
        assertEquals(listOf(week, day, hour), inserted(listOf(week, day), hour))
        assertEquals(listOf(hour, day, week), inserted(listOf(hour, week), day))
        assertEquals(listOf(week, day, hour), inserted(listOf(week, hour), day))
    }

    @Test
    fun equalUnitsStayStableAndSmallListsDefaultAscending() {
        assertEquals(0, TrackerOrdering.insertionIndex(emptyList(), tracker(SpanUnit.HOUR)))
        assertEquals(0, TrackerOrdering.insertionIndex(listOf(tracker(SpanUnit.DAY)), tracker(SpanUnit.HOUR)))
        assertEquals(
            2,
            TrackerOrdering.insertionIndex(listOf(tracker(SpanUnit.DAY), tracker(SpanUnit.DAY)), tracker(SpanUnit.DAY))
        )
        assertEquals(
            listOf(SpanUnit.HOUR, SpanUnit.DAY, SpanUnit.MONTH),
            inserted(listOf(SpanUnit.HOUR, SpanUnit.DAY), SpanUnit.MONTH)
        )
    }

    @Test
    fun customAmountsMixedOrderAndExplicitRangesAppend() {
        val mixed = listOf(tracker(SpanUnit.DAY), tracker(SpanUnit.HOUR), tracker(SpanUnit.WEEK))
        assertEquals(3, TrackerOrdering.insertionIndex(mixed, tracker(SpanUnit.HOUR)))
        assertEquals(1, TrackerOrdering.insertionIndex(listOf(tracker(SpanUnit.DAY, 2)), tracker(SpanUnit.HOUR)))
        assertEquals(1, TrackerOrdering.insertionIndex(listOf(tracker(SpanUnit.DAY)), tracker(SpanUnit.HOUR, 2)))
        assertEquals(
            1,
            TrackerOrdering.insertionIndex(
                listOf(tracker(SpanUnit.DAY).copy(mode = PeriodMode.FIXED)),
                tracker(SpanUnit.HOUR)
            )
        )
    }
}
