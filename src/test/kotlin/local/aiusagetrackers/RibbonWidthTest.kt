package local.aiusagetrackers

import com.google.gson.JsonParser
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import kotlin.test.*

class RibbonWidthTest {
    private fun entry(id: String, text: String) = RibbonValueButton(
        StatusValue(id, "", text),
        Font(Font.MONOSPACED, Font.PLAIN, 12), Color.BLACK, true
    ) {}

    @Test
    fun expansionFitsTheWidestEntryInEveryModeAndRollbackRetainsConfiguredWidth() = SwingUtilities.invokeAndWait {
        for (mode in RibbonScrollMode.entries) UsageRibbon { 0 }.use { ribbon ->
            val wide = entry("wide", "1234567890".repeat(6))
            val short = entry("short", "1")
            ribbon.configure(80, 0, mode, expandToFit = true)
            ribbon.setEntries(listOf(wide, short))
            assertEquals(wide.measuredWidth(), ribbon.preferredSize.width)
            assertTrue(assertNotNull(ribbon.widthExpansionWarning).startsWith("The ribbon widened to fit a long value."))
            ribbon.size = ribbon.preferredSize; ribbon.doLayout()
            if (mode == RibbonScrollMode.VERTICAL_STEP) {
                assertTrue(wide.isVisible)
                assertEquals(wide.value.displayText(), wide.text)
                assertFalse(short.isVisible)
            }
            ribbon.configure(80, 0, mode, expandToFit = false)
            assertEquals(80, ribbon.preferredSize.width)
            assertNull(ribbon.widthExpansionWarning)
            ribbon.size = ribbon.preferredSize; ribbon.doLayout()
            if (mode == RibbonScrollMode.VERTICAL_STEP) assertTrue("…" in wide.text)

        }
    }

    @Test
    fun extensionRecalculatesWhenEntriesChangeAndDoesNotAlterTheSelectedEntry() = SwingUtilities.invokeAndWait {
        UsageRibbon { 0 }.use { ribbon ->
            ribbon.configure(80, 0, RibbonScrollMode.VERTICAL_STEP, expandToFit = true)
            ribbon.setEntries(listOf(entry("a", "1"), entry("b", "2".repeat(50))))
            ribbon.selectEntry("b")
            val previous = ribbon.preferredSize.width
            ribbon.setEntries(listOf(entry("a", "1"), entry("b", "2".repeat(80))))
            assertTrue(ribbon.preferredSize.width > previous)
            assertEquals("b", ribbon.selectedEntry)
            val expanded = ribbon.preferredSize.width
            ribbon.reserveChooserWidth(20)
            assertEquals(expanded + 20, ribbon.preferredSize.width)
            ribbon.reserveChooserWidth(10)
            assertEquals(expanded + 20, ribbon.preferredSize.width)
            ribbon.setEntries(listOf(entry("a", "1"), entry("b", "2")))
            assertNotNull(ribbon.widthExpansionWarning)
            assertEquals(expanded + 20, ribbon.preferredSize.width)
        }
    }

    @Test
    fun settingRoundTripsAndLegacyImportsDefaultOffAndRejectTheNewField() {
        val display = DisplayPreferences(ribbonWidth = 123, ribbonAutoExpand = true)
        val json = TrackerConfigurationJson.encode(TrackerConfiguration(TrackerDefinition.defaults(), display))
        assertEquals(display, TrackerConfigurationJson.decode(json).display)
        val settings = UsageSettings().apply { update(emptyList(), TrackerDefinition.defaults(), display) }
        assertEquals(display, UsageSettings().apply { loadState(settings.state) }.display)
        settings.update(settings.extraHomes, settings.trackers, settings.display.copy(ribbonAutoExpand = false))
        assertFalse(settings.display.ribbonAutoExpand); assertEquals(123, settings.display.ribbonWidth)
        val root = JsonParser.parseString(json).asJsonObject
        root.getAsJsonObject("display").remove("coloringEnabled")
        root.addProperty("version", 8)
        assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.decode(root.toString()) }
        root.addProperty("version", 11)
        root.getAsJsonObject("display").addProperty("ribbonAutoExpand", "true")
        assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.decode(root.toString()) }
    }
}
