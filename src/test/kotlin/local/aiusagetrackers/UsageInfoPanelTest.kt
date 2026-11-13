package local.aiusagetrackers

import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageInfoPanelTest {
    @Test
    fun readingSnapshotDoesNotChangeUntilRequestedAndCopyUsesDisplayedText() = SwingUtilities.invokeAndWait {
        var latest = "• Original collected snapshot\n• Another metric"
        var reads = 0
        var copied = ""
        val panel = UsageInfoPanel({ reads++; latest }, {}, {}, { copied = it })
        val text = panel.preferredFocus
        val original = text.text
        assertFalse(text.isEditable)
        text.select(2, 10)
        latest = "• Updated collected snapshot\n• Another metric"
        assertEquals(original, text.text)
        assertEquals(1, reads)
        panel.button("Copy All").doClick()
        assertEquals(original, copied)
        panel.button("Update View").doClick()
        assertEquals(latest, text.text)
        assertEquals(2, reads)
        assertEquals(2, text.selectionStart)
        assertEquals(10, text.selectionEnd)
        panel.button("Copy All").doClick()
        assertEquals(latest, copied)
    }

    @Test
    fun shortUpdatesKeepSelectionInBoundsAndNavigationRemainsAvailable() = SwingUtilities.invokeAndWait {
        var latest = "• A long diagnostic message with selectable text"
        var disclaimersOpened = false
        var closed = false
        val panel = UsageInfoPanel({ latest }, { disclaimersOpened = true }, { closed = true })
        panel.preferredFocus.select(10, 40)
        latest = "• None"
        panel.button("Update View").doClick()
        assertEquals(latest.length, panel.preferredFocus.selectionStart)
        assertEquals(latest.length, panel.preferredFocus.selectionEnd)
        panel.button("Disclaimers").doClick()
        assertTrue(disclaimersOpened)
        panel.button("Close").doClick()
        assertTrue(closed)
    }

    private fun Container.button(label: String): JButton =
        descendants().filterIsInstance<JButton>().single { it.text == label }

    @Test
    fun detailPagesCaptureTogetherAndCopyOnlyTheSelectedPage() = SwingUtilities.invokeAndWait {
        var latest = UsageDetailPages("usage", "operational warning", "integrity finding")
        var reads = 0
        var copied = ""
        val panel = UsageInfoPanel({ error("Separate content must not be read") }, {}, {}, { copied = it }, details = { reads++; latest })
        val tabs = panel.descendants().filterIsInstance<javax.swing.JTabbedPane>().single()
        assertEquals(listOf("Usage", "Warnings", "Log Integrity"), (0 until tabs.tabCount).map(tabs::getTitleAt))
        assertEquals(1, reads)
        latest = UsageDetailPages("new usage", "new warning", "new finding")
        tabs.selectedIndex = 2
        panel.button("Copy Page").doClick()
        assertEquals("integrity finding", copied)
        panel.button("Update View").doClick()
        assertEquals(2, reads)
        panel.button("Copy Page").doClick()
        assertEquals("new finding", copied)
        tabs.selectedIndex = 1
        panel.button("Copy Page").doClick()
        assertEquals("new warning", copied)
        tabs.selectedIndex = 0
        panel.button("Copy Page").doClick()
        assertEquals("new usage", copied)
    }

    @Test
    fun acknowledgementVisibilityTracksOutstandingPagesSavingAndRefreshRaces() = SwingUtilities.invokeAndWait {
        var latest = UsageDetailPages("usage", "• warning", "• integrity", setOf("warning"), setOf("integrity"))
        lateinit var completion: (Boolean) -> Unit
        var errors = 0
        var saves = 0
        val panel = UsageInfoPanel({ "" }, {}, {}, details = { latest }, acknowledge = { _, _, callback ->
            saves++; completion = callback
        }, acknowledgementError = { errors++ })
        val tabs = panel.descendants().filterIsInstance<javax.swing.JTabbedPane>().single()
        val button = panel.button("Acknowledge Page")
        assertFalse(button.isVisible)
        tabs.selectedIndex = 1
        assertTrue(button.isVisible); assertTrue(button.isEnabled)
        button.doClick()
        assertTrue(button.isVisible); assertFalse(button.isEnabled)
        button.doClick(); assertEquals(1, saves)
        tabs.selectedIndex = 0; assertFalse(button.isVisible)
        tabs.selectedIndex = 2; assertTrue(button.isVisible); assertFalse(button.isEnabled)
        completion(true)
        assertTrue(button.isVisible); assertTrue(button.isEnabled)
        tabs.selectedIndex = 1; assertFalse(button.isVisible)
        tabs.selectedIndex = 2
        button.doClick(); completion(false)
        assertEquals(1, errors); assertTrue(button.isVisible); assertTrue(button.isEnabled)
        button.doClick()
        latest = latest.copy(integrity = "• new integrity", integrityFindings = setOf("new integrity"))
        panel.button("Update View").doClick()
        completion(true)
        assertTrue(button.isVisible); assertTrue(button.isEnabled)
        button.doClick(); completion(true)
        assertFalse(button.isVisible)
        latest = latest.copy(warningIssues = emptySet(), integrityFindings = emptySet())
        panel.button("Update View").doClick()
        tabs.selectedIndex = 1; assertFalse(button.isVisible)
        tabs.selectedIndex = 0; assertFalse(button.isVisible)
    }
    private fun Container.descendants(): List<Component> = components.flatMap {
        listOf(it) + if (it is Container) it.descendants() else emptyList()
    }
}
