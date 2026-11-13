package local.aiusagetrackers

import kotlin.test.*

class RibbonStepLayoutTest {
    private fun entries(vararg widths: Int) = widths.mapIndexed { index, width -> RibbonStepLayout.Entry("ABCDE"[index].toString(), width) }

    @Test fun horizontalTargetsRevealTheNextRightEdgeAndRetainInitialView() {
        val entries = entries(40, 40, 30, 50, 40)
        assertEquals(listOf(null to 0, "C" to 30, "D" to 90, "E" to 140),
            RibbonStepLayout.horizontalOffsets(entries, 100, 10))
        assertEquals(listOf(null to 0), RibbonStepLayout.horizontalOffsets(entries, 500, 10))
        assertEquals(listOf(null to 0, "A" to 50, "B" to 65),
            RibbonStepLayout.horizontalOffsets(entries(150, 10), 100, 5))
        assertEquals(listOf(null to 0), RibbonStepLayout.horizontalOffsets(entries, 0, 10))
    }

    @Test fun verticalWindowsAdvanceOneEntryAndWrapWithoutDuplicates() {
        val entries = entries(40, 40, 40, 40, 40)
        fun row(anchor: String, direction: Int) = RibbonStepLayout.row(
            RibbonStepLayout.ordered(entries, anchor, direction), 90, 10).map { it.id }
        assertEquals(listOf("A", "B"), row("A", 1))
        assertEquals(listOf("B", "C"), row("B", 1))
        assertEquals(listOf("E", "A"), row("E", 1))
        assertEquals(listOf("A", "E"), row("A", -1))
        assertEquals(listOf("C", "B"), row("C", -1))
        assertEquals(5, RibbonStepLayout.row(entries, 1000, 10).distinct().size)
    }

    @Test fun packingUsesExactWidthsAndSpacingAndRetainsOversizeEntries() {
        assertEquals(listOf("A", "B"), RibbonStepLayout.row(entries(40, 40, 1), 90, 10).map { it.id })
        assertEquals(listOf("A"), RibbonStepLayout.row(entries(40, 40), 89, 10).map { it.id })
        assertEquals(listOf("A"), RibbonStepLayout.row(entries(500, 1), 90, 10).map { it.id })
        assertTrue(RibbonStepLayout.row(entries(1), 0, 10).isEmpty())
        assertTrue(RibbonStepLayout.row(emptyList(), 90, 10).isEmpty())
    }

    @Test fun hoverPacksAllRemainingEntriesOnceInOrderWithinWidth() {
        val singles = entries(90, 90, 90, 90, 90)
        assertEquals(4, RibbonStepLayout.remainingRows(singles, setOf("A"), 100, 10).size)
        val pairs = entries(40, 40, 40, 40, 40)
        val rows = RibbonStepLayout.remainingRows(pairs, setOf("A", "B"), 90, 10)
        assertEquals(listOf(listOf("C", "D"), listOf("E")), rows.map { row -> row.map { it.id } })
        assertTrue(rows.all { RibbonStepLayout.fits(it, 90, 10) })
        assertEquals(3, RibbonStepLayout.remainingRows(entries(40, 40, 90, 90, 90), setOf("A", "B"), 100, 10).size)
        assertTrue(RibbonStepLayout.remainingRows(pairs, pairs.map { it.id }.toSet(), 90, 10).isEmpty())
        val reverse = RibbonStepLayout.ordered(pairs, "C", -1)
        assertEquals(listOf("A", "E", "D"), RibbonStepLayout.remainingRows(reverse, setOf("C", "B"), 90, 10).flatten().map { it.id })
    }

    @Test fun removedAnchorsChooseTheNextSurvivingNeighborInEitherDirection() {
        val old = listOf("A", "B", "C", "D", "E")
        assertEquals("D", RibbonStepLayout.survivingAnchor(old, listOf("A", "D"), "C", 1))
        assertEquals("A", RibbonStepLayout.survivingAnchor(old, listOf("A", "D"), "C", -1))
        assertEquals("C", RibbonStepLayout.survivingAnchor(old, old.reversed(), "C", 1))
        assertNull(RibbonStepLayout.survivingAnchor(old, emptyList(), "C", 1))
    }
}
