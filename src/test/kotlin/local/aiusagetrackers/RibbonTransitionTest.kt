package local.aiusagetrackers

import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.*

class RibbonTransitionTest {
    private fun entries() = listOf("11111111", "88888888", "44444444").mapIndexed { index, text ->
        RibbonValueButton(StatusValue(index.toString(), "", text), Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true) {}
    }

    private fun frame(ribbon: UsageRibbon): IntArray {
        val image = BufferedImage(ribbon.width, ribbon.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { ribbon.printAll(graphics) } finally { graphics.dispose() }
        return image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun step(ribbon: UsageRibbon) = ribbon.dispatchEvent(MouseWheelEvent(ribbon,
        MouseEvent.MOUSE_WHEEL, 0, 0, 5, 5, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1))

    @Test fun bothModesBlendFromTheOldFrameAndFinishWithoutChangingDwellOrGeometry() = SwingUtilities.invokeAndWait {
        for (mode in listOf(RibbonScrollMode.VERTICAL_STEP, RibbonScrollMode.HORIZONTAL_STEP)) {
            var now = 0L
            UsageRibbon { now }.use { ribbon ->
                ribbon.configure(80, 0, mode, horizontalStepSeconds = 1, verticalStepSeconds = 1)
                ribbon.setEntries(entries()); ribbon.setSize(80, 22); ribbon.doLayout()
                val before = frame(ribbon)
                val size = ribbon.preferredSize
                now = 1_000_000_000; ribbon.tick()
                val selected = ribbon.selectedEntry
                assertContentEquals(before, frame(ribbon))
                now += 80_000_000; ribbon.tick()
                val middle = frame(ribbon)
                assertFalse(before.contentEquals(middle))
                now += 80_000_000; ribbon.tick()
                val after = frame(ribbon)
                assertFalse(middle.contentEquals(after))
                assertFalse(before.contentEquals(after))
                assertEquals(size, ribbon.preferredSize)
                assertEquals(selected, ribbon.selectedEntry)
                now = 1_999_999_999; ribbon.tick(); assertEquals(selected, ribbon.selectedEntry)
                now++; ribbon.tick(); assertNotEquals(selected, ribbon.selectedEntry)
            }
        }
    }

    @Test fun rapidManualStepsContinueTheBlendAndRefreshAndChooserSelectionDiscardStaleFrames() = SwingUtilities.invokeAndWait {
        for (mode in listOf(RibbonScrollMode.VERTICAL_STEP, RibbonScrollMode.HORIZONTAL_STEP)) {
            var now = 0L
            UsageRibbon { now }.use { ribbon ->
                ribbon.configure(80, 0, mode, horizontalStepSeconds = 0, verticalStepSeconds = 0)
                ribbon.setEntries(entries()); ribbon.setSize(80, 22); ribbon.doLayout()
                step(ribbon)
                now += 80_000_000; ribbon.tick()
                val middle = frame(ribbon)
                step(ribbon)
                assertContentEquals(middle, frame(ribbon))
                ribbon.setEntries(entries())
                val refreshed = frame(ribbon)
                now += 160_000_000; ribbon.tick()
                assertContentEquals(refreshed, frame(ribbon))
                if (mode == RibbonScrollMode.VERTICAL_STEP) {
                    step(ribbon)
                    ribbon.selectEntry("1")
                    val selected = frame(ribbon)
                    now += 160_000_000; ribbon.tick()
                    assertEquals("1", ribbon.selectedEntry)
                    assertContentEquals(selected, frame(ribbon))
                }
            }
        }
    }
}
