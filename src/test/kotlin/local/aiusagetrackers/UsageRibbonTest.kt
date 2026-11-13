package local.aiusagetrackers

import java.awt.Dimension
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class UsageRibbonTest {
    @Test fun placementDefaultsAndOverridesFollowPeriodRatherThanLabel() {
        for (unit in SpanUnit.entries) {
            val tracker = TrackerDefinition(name = "Renamed", unit = unit)
            assertEquals(unit in setOf(SpanUnit.WEEK, SpanUnit.DAY, SpanUnit.HOUR), tracker.fixedInWidget())
            assertFalse(tracker.copy(amount = 2).fixedInWidget())
            assertTrue(tracker.copy(ribbonPlacement = RibbonPlacement.FIXED).fixedInWidget())
            assertFalse(tracker.copy(ribbonPlacement = RibbonPlacement.RIBBON).fixedInWidget())
        }
    }

    @Test fun portableRoundTripRetainsPlacementAndRibbonOptions() {
        val tracker = TrackerDefinition(ribbonPlacement = RibbonPlacement.RIBBON)
        val display = DisplayPreferences(ribbonWidth = 450, ribbonSpeed = 0, fixedAuxiliary = "request,quota:10080", ribbonScrollMode = RibbonScrollMode.CONTINUOUS)
        val decoded = TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(listOf(tracker), display)))
        assertEquals(RibbonPlacement.RIBBON, decoded.trackers.single().ribbonPlacement)
        assertEquals(display, decoded.display)
        assertFailsWith<IllegalArgumentException> { display.copy(ribbonSpeed = -1).validate() }
        assertFailsWith<IllegalArgumentException> { display.copy(ribbonWidth = 0).validate() }
    }

    @Test fun preciseWheelDragAndAnimationRespectBoundsAndPause() = SwingUtilities.invokeAndWait {
        var now = 0L
        UsageRibbon { now }.use { ribbon ->
            ribbon.view = JPanel().apply { preferredSize = Dimension(1000, 22) }
            ribbon.configure(100, 40)
            ribbon.setSize(100, 22)
            ribbon.doLayout()
            val wheel = MouseWheelEvent(ribbon, MouseEvent.MOUSE_WHEEL, 0, 0, 5, 5, 5, 5, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 0.5)
            ribbon.dispatchEvent(wheel)
            assertTrue(wheel.isConsumed)
            assertTrue(ribbon.viewPosition.x > 0)
            val afterWheel = ribbon.viewPosition.x
            now += 100_000_000L; ribbon.tick()
            assertEquals(afterWheel, ribbon.viewPosition.x)
            now += 2_000_000_000L; ribbon.tick()
            assertTrue(ribbon.viewPosition.x > afterWheel)
            fun mouse(id: Int, screenX: Int, button: Int) = MouseEvent(ribbon, id, 0,
                if (id == MouseEvent.MOUSE_DRAGGED) MouseEvent.BUTTON1_DOWN_MASK else 0,
                5, 5, screenX, 10, 1, false, button)
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, 200, MouseEvent.BUTTON1))
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, -2000, MouseEvent.NOBUTTON))
            ribbon.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, -2000, MouseEvent.BUTTON1))
            assertTrue(ribbon.dragged)
            assertEquals(900, ribbon.viewPosition.x)
            assertFalse(ribbon.canBePreprocessed(mouse(MouseEvent.MOUSE_PRESSED, 10, MouseEvent.BUTTON1)))
            assertTrue(ribbon.canBePreprocessed(mouse(MouseEvent.MOUSE_PRESSED, 10, MouseEvent.BUTTON3)))
            ribbon.configure(100, 0)
            now += 3_000_000_000L; ribbon.tick()
            assertEquals(900, ribbon.viewPosition.x)
        }
    }
    @Test fun bounceReversesAtBothEndsAndContinuousWrapsWithoutPause() = SwingUtilities.invokeAndWait {
        var now = 0L
        UsageRibbon { now }.use { ribbon ->
            ribbon.view = JPanel(java.awt.FlowLayout(0, 0, 0)).apply { preferredSize = Dimension(200, 22) }
            ribbon.configure(100, 200, RibbonScrollMode.BOUNCE)
            ribbon.setSize(100, 22)
            ribbon.doLayout()
            fun advance(ticks: Int) { repeat(ticks) { now += 100_000_000L; ribbon.tick() } }
            advance(5)
            assertEquals(100, ribbon.viewPosition.x)
            advance(9)
            assertEquals(100, ribbon.viewPosition.x)
            advance(1)
            assertTrue(ribbon.viewPosition.x < 100)
            advance(4)
            assertEquals(0, ribbon.viewPosition.x)
            advance(10)
            assertTrue(ribbon.viewPosition.x > 0)

            // Continuous views contain two equal copies, each 200 pixels wide.
            ribbon.view.preferredSize = Dimension(400, 22)
            ribbon.configure(100, 200, RibbonScrollMode.CONTINUOUS)
            ribbon.doLayout()
            advance(9)
            assertEquals(180, ribbon.viewPosition.x)
            advance(1)
            assertEquals(0, ribbon.viewPosition.x)
            advance(1)
            assertEquals(20, ribbon.viewPosition.x)
            ribbon.configure(100, 0, RibbonScrollMode.CONTINUOUS)
            advance(20)
            assertEquals(20, ribbon.viewPosition.x)
            // Short content stays still instead of looping repeated metrics.
            ribbon.view.preferredSize = Dimension(160, 22)
            ribbon.configure(100, 200, RibbonScrollMode.CONTINUOUS)
            advance(1)
            assertEquals(0, ribbon.viewPosition.x)
        }
    }

    @Test fun currentPortableFormatPreservesModesAndUnknownModesFail() {
        val config = TrackerConfiguration(listOf(TrackerDefinition()), DisplayPreferences())
        val json = TrackerConfigurationJson.encode(config)
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.decode(json.replace("\"restart\"", "\"unknown\""))
        }
        for (mode in RibbonScrollMode.entries) {
            val display = DisplayPreferences(ribbonScrollMode = mode)
            assertEquals(display, TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(config.copy(display = display))).display)
            val settings = UsageSettings().apply { update(emptyList(), config.trackers, display) }
            assertEquals(mode, UsageSettings().apply { loadState(settings.state) }.display.ribbonScrollMode)
        }
    }
}
