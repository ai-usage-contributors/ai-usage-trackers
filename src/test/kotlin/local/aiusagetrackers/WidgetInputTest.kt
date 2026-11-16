package local.aiusagetrackers

import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class WidgetInputTest {
    @Test fun cachedWidgetForegroundTracksThemeChangesWithoutHover() = SwingUtilities.invokeAndWait {
        val previous = javax.swing.UIManager.get("Label.foreground")
        var color = java.awt.Color.BLACK
        val dynamic = object : java.awt.Color(0) {
            override fun getRGB(): Int = color.rgb
        }
        try {
            javax.swing.UIManager.put("Label.foreground", dynamic)
            val lightThemeForeground = WidgetAppearance.foreground()
            color = java.awt.Color.WHITE
            val darkThemeForeground = WidgetAppearance.foreground()
            assertEquals(java.awt.Color.BLACK, lightThemeForeground)
            assertEquals(java.awt.Color.WHITE, darkThemeForeground)
            assertNotEquals(lightThemeForeground, darkThemeForeground)
            color = java.awt.Color.BLACK
            assertEquals(java.awt.Color.BLACK, WidgetAppearance.foreground())
            assertEquals(java.awt.Color.WHITE, darkThemeForeground)
        } finally {
            javax.swing.UIManager.put("Label.foreground", previous)
        }
    }

    @Test fun shortcutsSurviveViewReplacementAndConsumeReleasesWithoutTakingFocus() = SwingUtilities.invokeAndWait {
        VendorCarousel { 0L }.use { carousel ->
            carousel.configure(linkedMapOf(UsageVendor.CODEX to JPanel(), UsageVendor.CLAUDE to JPanel()),
                VendorDisplayState(verticalSeconds = 0))
            var active = true
            val keys = WidgetNavigationKeys({ active }, carousel::navigate)
            val editor = JPanel()
            fun key(id: Int, code: Int, modifiers: Int = 0): Boolean {
                val event = KeyEvent(editor, id, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED)
                return keys.dispatchKeyEvent(event).also { assertEquals(it, event.isConsumed) }
            }
            repeat(4) {
                assertTrue(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK))
                assertEquals(if (it % 2 == 0) UsageVendor.CLAUDE else UsageVendor.CODEX, carousel.selected)
                active = false // Completion of a handled chord remains consumed after leaving the widget.
                assertTrue(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_LEFT))
                assertTrue(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_ALT))
                assertFalse(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK))
                active = true
            }
            assertFalse(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT))
            assertFalse(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK or KeyEvent.CTRL_DOWN_MASK))
            keys.reset()
            assertFalse(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_ALT))
        }
    }

    @Test fun labelWheelAndDragNavigateWhileClicksStillOpenActions() = SwingUtilities.invokeAndWait {
        val steps = mutableListOf<Int>()
        var vertical = false
        val label = AgentLabelButton({ vertical }, steps::add).apply { setSize(100, 22) }
        var clicks = 0
        label.addActionListener { clicks++ }
        fun wheel(rotation: Double) {
            val event = MouseWheelEvent(label, MouseEvent.MOUSE_WHEEL, 0, 0, 5, 5, 5, 5, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, rotation.toInt(), rotation)
            assertFalse(label.canBePreprocessed(event))
            label.dispatchEvent(event)
            assertTrue(event.isConsumed)
        }
        wheel(0.5); wheel(0.5); wheel(0.5); wheel(-1.0)
        assertEquals(listOf(1, -1), steps)
        fun mouse(id: Int, x: Int, y: Int) = MouseEvent(label, id, 0,
            if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.BUTTON1_DOWN_MASK else 0,
            5, 5, x, y, 1, false, if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1)
        fun drag(x: Int, y: Int) {
            label.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, 100, 100))
            label.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, x, y))
            label.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, x, y))
        }
        drag(40, 100)
        vertical = true
        drag(100, 160)
        assertEquals(listOf(1, -1, 2, -2), steps)
        assertEquals(0, clicks)
        drag(100, 96) // A drag smaller than one step must still suppress the menu.
        assertEquals(0, clicks)
        label.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, 100, 100))
        label.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, 100, 100))
        assertEquals(1, clicks)
        assertFalse(label.isRequestFocusEnabled)
    }

    @Test fun wheelOnRibbonChildrenIsProtectedAndDoesNotNavigateAgents() = SwingUtilities.invokeAndWait {
        UsageRibbon { 0L }.use { ribbon ->
            val button = RibbonValueButton(StatusValue("test", "Test", "100"),
                JPanel().font, java.awt.Color.BLACK, true) {}
            ribbon.configure(30, 0)
            ribbon.setEntries(listOf(button))
            ribbon.setSize(30, 22)
            ribbon.doLayout()
            val event = MouseWheelEvent(button, MouseEvent.MOUSE_WHEEL, 0, 0, 5, 5, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1)
            assertFalse(ribbon.canBePreprocessed(event))
            assertFalse(button.canBePreprocessed(event))
            assertFalse((ribbon.view as com.intellij.openapi.wm.IdeGlassPane.TopComponent).canBePreprocessed(event))
            button.dispatchEvent(event)
            assertTrue(event.isConsumed)
            assertTrue(ribbon.viewPosition.x > 0)
            assertEquals(Dimension(30, button.preferredSize.height), ribbon.preferredSize)
        }
    }
}
