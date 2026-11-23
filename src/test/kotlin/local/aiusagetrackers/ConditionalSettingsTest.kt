package local.aiusagetrackers

import java.awt.BorderLayout
import javax.swing.*
import kotlin.test.*

class ConditionalSettingsTest {
    @Test fun dailyResetOnlyAppliesToOneCurrentAutomaticCalendarDay() {
        val day = TrackerDefinition(resetDailyAtWeeklyReset = true)
        assertTrue(day.supportsDailyWeeklyReset())
        assertFalse(day.copy(amount = 2).supportsDailyWeeklyReset())
        assertFalse(day.copy(includeCurrent = false).supportsDailyWeeklyReset())
        assertFalse(day.copy(unit = SpanUnit.WEEK).supportsDailyWeeklyReset())
        assertFalse(day.copy(mode = PeriodMode.ELAPSED).supportsDailyWeeklyReset())
        assertFalse(day.copy(startTime = "08:00").supportsDailyWeeklyReset())
    }

    @Test fun providerWeeksOnlyExposeApplicablePeriodControls() {
        assertEquals(setOf("period", "zone"), trackerSettingVisibility(PeriodMode.PROVIDER_WEEK, SpanUnit.WEEK, EndAnchor.NOW, false))
        assertEquals(setOf("period", "zone", "amount", "unit", "days", "current"),
            trackerSettingVisibility(PeriodMode.CALENDAR, SpanUnit.DAY, EndAnchor.NOW, false))
        val hours = trackerSettingVisibility(PeriodMode.CALENDAR, SpanUnit.HOUR, EndAnchor.NOW, false)
        assertFalse("days" in hours); assertTrue("boundaries" in hours); assertTrue("current" in hours)
        for (unit in SpanUnit.entries) {
            val now = trackerSettingVisibility(PeriodMode.ELAPSED, unit, EndAnchor.NOW, false)
            assertTrue("end" in now); assertFalse("offset" in now)
            assertFalse("days" in now); assertFalse("current" in now)
            assertTrue("offset" in trackerSettingVisibility(PeriodMode.ELAPSED, unit, EndAnchor.DAY, false))
        }
        assertTrue(trackerSettingVisibility(PeriodMode.REPEATING, SpanUnit.DAY, EndAnchor.NOW, true).isEmpty())
        assertEquals(setOf("boundaries"), trackerSettingVisibility(PeriodMode.FIXED, SpanUnit.HOUR, EndAnchor.NOW, true))
    }

    @Test fun hidingWrappedRowsRemovesLabelsAndEmptyCategoriesButKeepsDraftAndCollapseState() = SwingUtilities.invokeAndWait {
        val draft = JTextField("Invalid unsaved value")
        val label = JLabel("Unit")
        val wrapper = JPanel(BorderLayout()).apply { add(draft, BorderLayout.CENTER); add(JButton("Info"), BorderLayout.EAST) }
        val root = SettingsForm()
        root.section("Period").addRow(label, wrapper)
        val category = root.components.single() as SettingsCategory
        val height = root.preferredSize.height
        category.expanded = false
        setSettingVisible(draft, false)
        assertFalse(label.isVisible); assertFalse(wrapper.isVisible); assertFalse(category.isVisible)
        assertTrue(root.preferredSize.height < height)
        setSettingVisible(draft, true)
        assertTrue(label.isVisible); assertTrue(wrapper.isVisible); assertTrue(category.isVisible)
        assertFalse(category.expanded)
        assertSame(draft, wrapper.getComponent(0))
        assertEquals("Invalid unsaved value", draft.text)
        setSettingVisible(draft, false)
        assertFailsWith<IllegalArgumentException> { validateSetting(draft) { require(false) } }
        assertTrue(category.isVisible); assertTrue(category.expanded)
        assertTrue(label.isVisible); assertTrue(wrapper.isVisible)
    }
}
