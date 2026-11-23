package local.aiusagetrackers

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class WidgetPointerTest {
    private val showing = object : JPanel() {
        override fun isShowing() = true
    }

    @Test fun displayLookupFailuresRecoverAndUnrelatedErrorsRemainVisible() {
        repeat(3) {
            assertNull(WidgetPointer.inside(showing) { throw ArrayIndexOutOfBoundsException("Index 1 out of bounds for length 1") })
        }
        assertEquals(true, WidgetPointer.inside(showing) { Point(1, 1) })
        assertEquals(false, WidgetPointer.inside(showing) { null })
        assertEquals(false, WidgetPointer.inside(JPanel()) { error("Hidden components must not query AWT") })
        assertFailsWith<IllegalStateException> { WidgetPointer.inside(showing) { error("Unrelated failure") } }
    }

    @Test fun popupRequiresReliableOutsideReadingsForTheFullGracePeriod() {
        val now = AtomicLong()
        val lifetime = RibbonEntryPopup.Companion.HoverLifetime(now::get)
        assertFalse(lifetime.shouldClose(false))
        now.set(150_000_000L)
        assertFalse(lifetime.shouldClose(null))
        now.addAndGet(1_000_000_000L)
        assertFalse(lifetime.shouldClose(null))
        assertFalse(lifetime.shouldClose(false))
        now.addAndGet(199_999_999L)
        assertFalse(lifetime.shouldClose(false))
        now.incrementAndGet()
        assertTrue(lifetime.shouldClose(false))
        assertEquals(true, WidgetPointer.anyInside(null, true, false))
        assertNull(WidgetPointer.anyInside(false, null, false))
        assertEquals(false, WidgetPointer.anyInside(false, false, false))
    }

    @Test fun carouselRefreshAndTicksPauseDuringFailureAndRecover() = SwingUtilities.invokeAndWait {
        var now = 0L
        var failing = true
        VendorCarousel { now }.use { carousel ->
            carousel.pointerInside = {
                WidgetPointer.inside(showing) {
                    if (failing) throw ArrayIndexOutOfBoundsException(1)
                    null
                }
            }
            val items = linkedMapOf(UsageVendor.CODEX to JPanel(), UsageVendor.CLAUDE to JPanel())
            val state = VendorDisplayState(motion = RibbonScrollMode.VERTICAL_STEP, verticalSeconds = 1)
            carousel.configure(items, state)
            repeat(3) {
                now += 2_000_000_000L
                carousel.configure(items, state)
                carousel.tick()
                assertEquals(UsageVendor.CODEX, carousel.selected)
            }
            failing = false
            now += 1_000_000_000L
            carousel.tick()
            assertEquals(UsageVendor.CLAUDE, carousel.selected)
            failing = true
            carousel.navigate(1)
            assertEquals(UsageVendor.CODEX, carousel.selected)
        }
    }

    @Test fun unavailableReadingsRestartTheSteppedRibbonDwellWithoutCatchUp() = SwingUtilities.invokeAndWait {
        var now = 0L
        var reading: Boolean? = null
        UsageRibbon { now }.use { ribbon ->
            ribbon.pointerInside = { reading }
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, 1, 1)
            ribbon.setEntries(('A'..'C').map {
                RibbonValueButton(StatusValue(it.toString(), "", "12345678"), Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true) {}
            })
            ribbon.setSize(80, 22)
            ribbon.doLayout()
            repeat(3) {
                now += 10_000_000_000L
                ribbon.tick()
                assertEquals("A", ribbon.selectedEntry)
            }
            reading = false
            now += 999_999_999L
            ribbon.tick()
            assertEquals("A", ribbon.selectedEntry)
            now++
            ribbon.tick()
            assertEquals("B", ribbon.selectedEntry)
        }
    }

    @Test fun ribbonPausesMotionAndHoverCallbacksDuringFailureThenRecovers() = SwingUtilities.invokeAndWait {
        var now = 0L
        var reading: Boolean? = null
        UsageRibbon { now }.use { ribbon ->
            ribbon.pointerInside = { reading }
            ribbon.view = JPanel().apply { preferredSize = Dimension(1000, 22) }
            ribbon.configure(100, 40)
            ribbon.setSize(100, 22)
            ribbon.doLayout()
            repeat(3) {
                now += 1_000_000_000L
                ribbon.tick()
                assertEquals(0, ribbon.viewPosition.x)
            }
            reading = false
            now += 100_000_000L
            ribbon.tick()
            assertTrue(ribbon.viewPosition.x > 0)
            reading = true
            val position = ribbon.viewPosition.x
            now += 100_000_000L
            ribbon.tick()
            assertEquals(position, ribbon.viewPosition.x)
            ribbon.configure(100, 0, RibbonScrollMode.VERTICAL_STEP)
            var entered = 0
            var exited = 0
            ribbon.hoverRequested = { entered++ }
            ribbon.hoverEnded = { exited++ }
            reading = null
            ribbon.tick()
            assertEquals(0, entered)
            assertEquals(0, exited)
            reading = true
            ribbon.tick()
            assertEquals(1, entered)
            reading = false
            ribbon.tick()
            assertEquals(1, exited)
        }
    }

    @Test fun unavailablePointerDoesNotCaptureShortcutsWithoutWidgetFocus() = SwingUtilities.invokeAndWait {
        VendorCarousel { 0L }.use { carousel ->
            carousel.pointerInside = { null }
            var focused = false
            var navigations = 0
            val keys = WidgetNavigationKeys({ carousel.navigationEligible(focused) }, { navigations++ })
            fun press() = KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0, KeyEvent.ALT_DOWN_MASK,
                KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED)
            val outside = press()
            assertFalse(keys.dispatchKeyEvent(outside))
            assertFalse(outside.isConsumed)
            focused = true
            assertTrue(keys.dispatchKeyEvent(press()))
            assertEquals(1, navigations)
            keys.reset()
            focused = false
            carousel.pointerInside = { true }
            assertTrue(keys.dispatchKeyEvent(press()))
            assertEquals(2, navigations)
        }
    }
}
