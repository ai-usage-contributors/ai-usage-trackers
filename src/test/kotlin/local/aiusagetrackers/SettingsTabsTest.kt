package local.aiusagetrackers

import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import javax.swing.*
import kotlin.test.*

class SettingsTabsTest {
    @Test
    fun coloringSwitchUsesThemeWithoutChangingValuesOrSavedChoices() {
        val tracker = TrackerDefinition(colorByQuotaGoal = true, showPercentUsed = true)
        val now = Instant.parse("2026-11-10T12:00:00Z")
        val result = TrackerResult(tracker, ResolvedPeriod(emptyList(), ZoneOffset.UTC), 50)
        val metric = QuotaMetricPresentation(BigDecimal(100), "Sample")
        val settings = DisplayPreferences(palette = "#123456,#00FF00,#FFFF00,#FFA500,#FF0000")
        val enabled = WidgetText.trackerValue(result, now, settings, metric)
        val disabled = WidgetText.trackerValue(result, now, settings.copy(coloringEnabled = false), metric)
        assertTrue(WidgetAppearance.usesHtml(enabled))
        assertFalse(WidgetAppearance.usesHtml(disabled))
        assertEquals(enabled.text, disabled.text)
        assertEquals(enabled.raw, disabled.raw)
        assertEquals(enabled.accessibleText, disabled.accessibleText)
        assertTrue(tracker.colorByQuotaGoal)
        assertEquals(enabled, WidgetText.trackerValue(result, now, settings, metric))
    }

    @Test
    fun masterSwitchCoversQuotaAndRequestValuesInEverySampleState() {
        val options = AnalyticsDisplayOptions(
            showQuota = true, showReset = true,
            showFiveHourQuota = true, showFiveHourReset = true, colorWeeklyQuota = true
        )
        for (state in 1..5) {
            val snapshot = AnalyticsSettingsPanel.sampleSnapshot(state)
            val enabled = WidgetText.values(snapshot, DisplayPreferences(), true, options)
            val disabled = WidgetText.values(snapshot, DisplayPreferences(coloringEnabled = false), true, options)
            assertEquals(enabled.map { it.displayText() }, disabled.map { it.displayText() })
            assertTrue(disabled.all { !WidgetAppearance.usesHtml(it) && !it.quotaColorEnabled })
        }
    }

    @Test
    fun portableColorSwitchAndLocalPinSurviveWithoutLeakingPinToExports() {
        val display = DisplayPreferences(coloringEnabled = false)
        val settings = UsageSettings()
        settings.setPreviewPinned(true)
        settings.update(emptyList(), TrackerDefinition.defaults(), display)
        val restored = UsageSettings().apply { loadState(settings.state) }
        assertTrue(restored.state.previewPinned)
        assertFalse(restored.display.coloringEnabled)
        val encoded = TrackerConfigurationJson.encode(TrackerConfiguration(settings.trackers, display))
        assertFalse("previewPinned" in encoded)
        assertEquals(display, TrackerConfigurationJson.decode(encoded).display)
        val legacy = JsonParser.parseString(encoded).asJsonObject
        legacy.addProperty("version", 9)
        legacy.getAsJsonObject("display").remove("coloringEnabled")
        assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.decode(legacy.toString()) }
    }

    @Test
    fun previewMovesBetweenFootersAndDockWithoutDuplicatingOrChangingDrafts() = SwingUtilities.invokeAndWait {
        val draft = JTextField("Unsaved draft")
        val tabs = settingsTabs("Trackers" to draft, "Quota" to JPanel())
        val preview = JLabel("Preview")
        val saved = mutableListOf<Boolean>()
        val layout = SettingsPreviewLayout(tabs, preview, false, saved::add)
        val first = ((tabs.getComponentAt(0) as JScrollPane).viewport.view as SettingsTabContent).footer
        val second = ((tabs.getComponentAt(1) as JScrollPane).viewport.view as SettingsTabContent).footer
        val host = preview.parent as JPanel
        val pin = host.getComponent(0) as JCheckBox
        assertSame(first, host.parent)
        tabs.selectedIndex = 1
        assertSame(second, host.parent)
        assertEquals(0, first.componentCount)
        pin.doClick()
        assertNotSame(second, host.parent)
        tabs.selectedIndex = 0
        assertTrue(SwingUtilities.isDescendingFrom(preview, layout))
        pin.doClick()
        assertSame(first, host.parent)
        assertEquals(listOf(true, false), saved)
        assertEquals("Unsaved draft", draft.text)
    }
}
