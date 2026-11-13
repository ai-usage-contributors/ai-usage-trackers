package local.aiusagetrackers

import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities
import javax.swing.JPanel
import kotlin.test.*

class RibbonSteppingTest {
    private fun buttons(text: String = "12345678") = ('A'..'E').map {
        RibbonValueButton(StatusValue(it.toString(), "", text), Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true) {}
    }
    private fun wheel(ribbon: UsageRibbon, rotation: Double) {
        ribbon.dispatchEvent(MouseWheelEvent(ribbon, MouseEvent.MOUSE_WHEEL, 0, 0, 5, 5, 5, 5, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation.toInt(), rotation))
    }

    @Test fun dragThresholdsMoveWholeEntriesAndDirectionChangesDiscardFractionalWheelInput() = SwingUtilities.invokeAndWait {
        UsageRibbon { 0 }.use { ribbon ->
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, verticalStepSeconds = 0)
            ribbon.setEntries(buttons()); ribbon.setSize(80, 22); ribbon.doLayout()
            fun mouse(id: Int, y: Int, button: Int) = MouseEvent(ribbon, id, 0,
                if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.BUTTON1_DOWN_MASK else 0,
                5, 5, 100, y, 1, false, button)
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, 100, MouseEvent.BUTTON1))
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, 71, MouseEvent.NOBUTTON))
            assertEquals("A", ribbon.selectedEntry)
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, 70, MouseEvent.NOBUTTON))
            assertEquals("B", ribbon.selectedEntry)
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, 70, MouseEvent.BUTTON1))
            assertTrue(ribbon.dragged)
            val background = ribbon.view as com.intellij.openapi.wm.IdeGlassPane.TopComponent
            assertFalse(background.canBePreprocessed(mouse(MouseEvent.MOUSE_PRESSED, 70, MouseEvent.BUTTON1)))
            wheel(ribbon, 0.8); wheel(ribbon, -0.4)
            assertEquals("B", ribbon.selectedEntry)
            wheel(ribbon, -0.6); assertEquals("A", ribbon.selectedEntry)
            ribbon.chooserActive = true
            assertTrue((ribbon.view as JPanel).components.filterIsInstance<RibbonValueButton>().all { it.toolTipText == null })
            ribbon.chooserActive = false
            assertTrue((ribbon.view as JPanel).components.filterIsInstance<RibbonValueButton>().all { it.toolTipText != null })
        }
    }

    @Test fun horizontalStepsAlignRightEdgesWrapAndReverseIncludingAnOversizedFirstEntry() = SwingUtilities.invokeAndWait {
        var now = 0L
        UsageRibbon { now }.use { ribbon ->
            val entries = buttons()
            val width = entries[0].measuredWidth() * 2 + entries[0].getFontMetrics(entries[0].font).stringWidth("· ") + 2
            ribbon.configure(width, 0, RibbonScrollMode.HORIZONTAL_STEP, 1)
            ribbon.setEntries(entries); ribbon.setSize(width, 22); ribbon.doLayout()
            assertEquals(0, ribbon.viewPosition.x)
            now = 999_999_999; ribbon.tick(); assertEquals(0, ribbon.viewPosition.x)
            ribbon.configure(width, 0, RibbonScrollMode.HORIZONTAL_STEP, 1, 20, RibbonStepDirection.DOWN)
            now++; ribbon.tick()
            assertEquals("C", ribbon.selectedEntry)
            assertEquals(entries[2].x + entries[2].width, ribbon.viewPosition.x + ribbon.width)
            now += 1_000_000_000; ribbon.tick(); assertEquals("D", ribbon.selectedEntry)
            now += 1_000_000_000; ribbon.tick(); assertEquals("E", ribbon.selectedEntry)
            now += 1_000_000_000; ribbon.tick(); assertEquals(0, ribbon.viewPosition.x)
            wheel(ribbon, -1.0); assertEquals("E", ribbon.selectedEntry)
            ribbon.configure(20, 0, RibbonScrollMode.VERTICAL_STEP)
            ribbon.configure(20, 0, RibbonScrollMode.HORIZONTAL_STEP)
            ribbon.setSize(20, 22); ribbon.doLayout()
            assertEquals(0, ribbon.viewPosition.x)
            wheel(ribbon, 1.0); assertEquals("A", ribbon.selectedEntry)
            assertEquals(entries[0].width, ribbon.viewPosition.x + 20)
        }
    }

    @Test fun verticalShowsWholeEntriesCentersSinglesAndSelectsAnyHiddenEntry() = SwingUtilities.invokeAndWait {
        UsageRibbon { 0 }.use { ribbon ->
            val entries = buttons()
            val singleWidth = entries[0].measuredWidth()
            ribbon.configure(singleWidth + 10, 30, RibbonScrollMode.VERTICAL_STEP, verticalStepSeconds = 0)
            ribbon.setEntries(entries); ribbon.setSize(singleWidth + 10, 22); ribbon.doLayout()
            assertEquals(listOf("A"), entries.filter { it.isVisible }.map { it.value.id })
            assertEquals(5, entries[0].x)
            assertEquals(listOf("E", "D", "C", "B"), ribbon.chooserRows().flatten().map { it.value.id })
            ribbon.selectEntry("D")
            assertEquals(listOf("D"), entries.filter { it.isVisible }.map { it.value.id })
            wheel(ribbon, 1.0); assertEquals("E", ribbon.selectedEntry)
            wheel(ribbon, 1.0); assertEquals("A", ribbon.selectedEntry)
            ribbon.configure(singleWidth + 10, 0, RibbonScrollMode.VERTICAL_STEP, stepDirection = RibbonStepDirection.DOWN)
            wheel(ribbon, 1.0); assertEquals("E", ribbon.selectedEntry)
            assertEquals(listOf("D", "C", "B", "A"), ribbon.chooserRows().flatten().map { it.value.id })
            ribbon.setSize(25, 22); ribbon.doLayout()
            assertEquals(1, entries.count { it.isVisible })
            assertTrue(entries.single { it.isVisible }.text.endsWith("…"))
            assertEquals(25, entries.single { it.isVisible }.width)
        }
    }

    @Test fun precisionInputAccumulatesDetentsAndRefreshDoesNotRestartTheDwell() = SwingUtilities.invokeAndWait {
        var now = 0L
        UsageRibbon { now }.use { ribbon ->
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, horizontalStepSeconds = 120, verticalStepSeconds = 3)
            ribbon.setEntries(buttons()); ribbon.setSize(80, 22); ribbon.doLayout()
            wheel(ribbon, 0.4); wheel(ribbon, 0.4); assertEquals("A", ribbon.selectedEntry)
            wheel(ribbon, 0.2); assertEquals("B", ribbon.selectedEntry)
            now = 2_999_999_999; ribbon.tick(); assertEquals("B", ribbon.selectedEntry)
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, verticalStepSeconds = 3)
            ribbon.setEntries(buttons("87654321"))
            assertEquals("B", ribbon.selectedEntry)
            now++; ribbon.tick(); assertEquals("C", ribbon.selectedEntry)
            now += 100_000_000_000; ribbon.tick(); assertEquals("D", ribbon.selectedEntry)
            ribbon.tick(); assertEquals("D", ribbon.selectedEntry)
            ribbon.chooserActive = true
            now += 10_000_000_000; ribbon.tick(); assertEquals("D", ribbon.selectedEntry)
            ribbon.chooserActive = false
            now += 2_999_999_999; ribbon.tick(); assertEquals("D", ribbon.selectedEntry)
            now++; ribbon.tick(); assertEquals("E", ribbon.selectedEntry)
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, verticalStepSeconds = 0)
            now += 100_000_000_000; ribbon.tick(); assertEquals("E", ribbon.selectedEntry)
            wheel(ribbon, 1.0); assertEquals("A", ribbon.selectedEntry)
        }
    }

    @Test fun removalRepackingAllFitAndModeChangesKeepAValidSelection() = SwingUtilities.invokeAndWait {
        UsageRibbon { 0 }.use { ribbon ->
            val entries = buttons()
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP)
            ribbon.setEntries(entries); ribbon.setSize(80, 22); ribbon.doLayout()
            ribbon.selectEntry("C")
            ribbon.setEntries(entries.filter { it.value.id in setOf("A", "D") })
            assertEquals("D", ribbon.selectedEntry)
            ribbon.setSize(300, 22); ribbon.doLayout()
            assertEquals(2, entries.count { it.isVisible && it.parent === ribbon.view })
            assertTrue(ribbon.chooserRows().isEmpty())
            wheel(ribbon, 1.0); assertEquals("D", ribbon.selectedEntry)
            ribbon.configure(80, 0, RibbonScrollMode.HORIZONTAL_STEP)
            assertNull(ribbon.selectedEntry)
            ribbon.setEntries(emptyList()); assertFalse(ribbon.isVisible)
            ribbon.tick()
        }
    }
}
