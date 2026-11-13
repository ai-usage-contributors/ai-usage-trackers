package local.aiusagetrackers

import java.awt.Color
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.*

class RibbonEntryPopupTest {
    @Test fun hoverTransferHasAGracePeriodAndReentryResetsTheDismissalDeadline() {
        var now = 0L
        val hover = RibbonEntryPopup.Companion.HoverLifetime { now }
        assertFalse(hover.shouldClose(false))
        now = 199_999_999; assertFalse(hover.shouldClose(false))
        assertFalse(hover.shouldClose(true))
        now = 300_000_000; assertFalse(hover.shouldClose(false))
        now = 499_999_999; assertFalse(hover.shouldClose(false))
        now++; assertTrue(hover.shouldClose(false))
    }

    @Test fun packedRowsHaveIndividualSelectionTargetsAndKeepFullAccessibleText() = SwingUtilities.invokeAndWait {
        val original = ('A'..'C').map { id ->
            RibbonValueButton(StatusValue(id.toString(), "", "12345678"), Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true) {}
        }
        var selected: String? = null
        val pairWidth = original[0].measuredWidth() * 2 + original[1].getFontMetrics(original[1].font).stringWidth("· ") + 2
        val panel = RibbonEntryPopup.buildRows(listOf(original.take(2), original.takeLast(1)), pairWidth, 22) { selected = it }
        val buttons = panel.components.filterIsInstance<RibbonValueButton>()
        assertEquals(3, buttons.size)
        assertTrue(buttons.all { it.x >= 0 && it.x + it.width <= pairWidth })
        assertEquals(0, buttons[1].y)
        buttons[1].doClick(0)
        assertEquals("B", selected)
        val enter = buttons[2].getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("ENTER"))
        buttons[2].actionMap.get(enter).actionPerformed(ActionEvent(buttons[2], 0, "ENTER"))
        assertEquals("C", selected)
        assertEquals(22, buttons[2].y)
        assertEquals((pairWidth - buttons[2].width) / 2, buttons[2].x)
        assertTrue(original.all { it.parent == null })
        assertEquals(original[1].value.accessibleText, buttons[1].getAccessibleContext().accessibleName)
    }

    @Test fun oversizedRichEntriesAreEllipsizedWithoutLeakingHtmlOrClippedNeighbors() = SwingUtilities.invokeAndWait {
        val source = RibbonValueButton(StatusValue("a", "A<&", "12345678901234567890", quotaColorEnabled = true),
            Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true) {}
        val panel = RibbonEntryPopup.buildRows(listOf(listOf(source)), 70, 22) {}
        val button = panel.components.single() as RibbonValueButton
        assertEquals(70, button.width)
        assertTrue(button.text.startsWith("<html>"))
        assertTrue("…" in button.text)
        assertFalse("A<&" in button.text)
        assertEquals(source.value.accessibleText, button.toolTipText)
        assertEquals(source.measuredWidth(), button.measuredWidth())
    }
}
