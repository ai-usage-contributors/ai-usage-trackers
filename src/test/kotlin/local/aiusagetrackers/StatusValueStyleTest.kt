package local.aiusagetrackers

import com.intellij.util.xmlb.XmlSerializer
import java.awt.Color
import javax.swing.SwingUtilities
import kotlin.test.*

class StatusValueStyleTest {
    @Test
    fun expandedAnalyticsControlsRemainInBoundedScrollableTabs() = SwingUtilities.invokeAndWait {
        AnalyticsSettingsPanel { AnalyticsSettingsPanel.sampleSnapshot(1) }.use { analytics ->
            val tabs = settingsTabs(
                "Analytics" to analytics,
                "Display" to javax.swing.JPanel(),
                "Sources" to javax.swing.JPanel()
            )
            assertEquals(3, tabs.tabCount)
            assertTrue((tabs.getComponentAt(0) as javax.swing.JScrollPane).viewport.view is javax.swing.Scrollable)
            assertTrue(tabs.getComponentAt(0) is javax.swing.JScrollPane)
            val form = SettingsForm()
            val short = javax.swing.JLabel("Short")
            val tall = javax.swing.JPanel().apply { preferredSize = java.awt.Dimension(100, 120) }
            form.addRow(javax.swing.JLabel("First"), short)
            form.addRow(javax.swing.JLabel("Second"), tall)
            form.setSize(400, form.preferredSize.height); form.doLayout()
            assertTrue(short.height < tall.height)
        }
    }

    @Test
    fun inclusiveThresholdsFallbackAndUnavailableStates() {
        val style = StatusValueStyle.parse("#123456|true|100=#FF8800,200=#FF0000")
        val raw = StatusValue("a", "Daily", "100", 100.toBigDecimal())
        assertEquals(Color.decode("#FF8800"), style.color(raw, Color.BLACK))
        assertEquals(Color.RED, style.color(raw.copy(raw = 200.toBigDecimal()), Color.BLACK))
        assertEquals(Color.decode("#123456"), style.color(raw.copy(raw = 99.toBigDecimal()), Color.BLACK))
        for (state in listOf(StatusValueState.UNAVAILABLE, StatusValueState.LOADING, StatusValueState.EXPIRED))
            assertEquals(Color.BLACK, style.color(raw.copy(state = state), Color.BLACK))
        assertEquals(Color.WHITE, StatusValueStyle.parse("").color(raw, Color.WHITE))
        assertEquals(Color.BLACK, StatusValueStyle.parse("").color(raw, Color.BLACK))
    }

    @Test
    fun rejectsAmbiguousRulesAndPreservesIdentityWhenLabelsHidden() {
        for (spec in listOf(
            "red|false|",
            "|false|2=#FF0000,1=#FFFFFF",
            "|false|1=#FF0000,1=#FFFFFF",
            "|false|-1=#FF0000"
        ))
            assertFailsWith<IllegalArgumentException> { StatusValueStyle.parse(spec) }
        val value = StatusValue("id", "Weekly quota", "80%", style = "|true|")
        assertEquals("80%", value.displayText())
        assertEquals("Weekly quota: 80%", value.accessibleText)
        assertFalse(value.copy(state = StatusValueState.STALE).displayText().contains("⚠"))
        assertTrue(value.copy(state = StatusValueState.STALE).accessibleText.contains("stale"))
        assertFalse(StatusValueStyle.forDisplay("invalid draft").hideLabel)
    }

    @Test
    fun remainingModeColorsDepletionWhenExplicitlyEnabled() {
        val snapshot = AnalyticsSettingsPanel.sampleSnapshot(1)
        val options = AnalyticsDisplayOptions(
            showQuota = true, colorWeeklyQuota = true, quotaPercentMode = QuotaPercentMode.REMAINING,
            weeklyQuotaStyle = "|false|20=#FF0000"
        )
        val value = WidgetText.values(snapshot, options = options).single { it.id == "quota:10080" }
        assertEquals("80%", value.text)
        assertEquals(0, 20.toBigDecimal().compareTo(value.raw!!))
        assertEquals(UsagePalette.parse(DisplayPreferences().palette).color(java.math.BigDecimal("0.6")), StatusValueStyle.parse(value.style).color(value, Color.BLACK))
    }

    @Test
    fun stylesRoundTripAndSettingsCopiesRemainIsolated() {
        val tracker =
            TrackerDefinition(style = "#123456|true|100=#FF0000", mode = PeriodMode.PROVIDER_WEEK, unit = SpanUnit.WEEK)
        val settings = UsageSettings()
        val options =
            AnalyticsDisplayOptions(showFiveHourQuota = true, estimateBudget = true, weeklyQuotaStyle = tracker.style)
        settings.update(emptyList(), listOf(tracker), analyticsDisplay = options)
        val state = XmlSerializer.deserialize(
            XmlSerializer.serialize(settings.getState()),
            UsageSettings.SettingsState::class.java
        )
        val restored = UsageSettings().also { it.loadState(state) }
        assertEquals(options, restored.analyticsDisplay)
        val draft = restored.trackers.single().copy(style = "")
        assertEquals("", restored.trackers.single().style)
        assertTrue(restored.trackers.single().hideLabel)
        assertEquals("", draft.style)
        val encoded = TrackerConfigurationJson.encode(TrackerConfiguration(restored.trackers))
        assertEquals("", TrackerConfigurationJson.decode(encoded).trackers.single().style)
        assertTrue(TrackerConfigurationJson.decode(encoded).trackers.single().hideLabel)
        assertEquals(21.0, StatusValueStyle.contrast(Color.BLACK, Color.WHITE))
    }

    @Test
    fun invalidDraftStylesDoNotBreakEitherSettingsPreview() = SwingUtilities.invokeAndWait {
        TrackerWidgetPreview { _, _ -> }.use {
            it.update(
                listOf(TrackerDefinition(style = "unfinished")),
                DisplayPreferences()
            )
        }
        AnalyticsSettingsPanel { AnalyticsSettingsPanel.sampleSnapshot(1) }.use { panel ->
            panel.reset(AnalyticsDisplayOptions(showQuota = true, weeklyQuotaStyle = "invalid"))
            panel.options().validate()
            assertEquals("", panel.options().weeklyQuotaStyle)
        }
    }
}
