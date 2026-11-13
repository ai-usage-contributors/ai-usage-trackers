package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.*

class QuotaGradientTest {
    @Test fun anchorsMidpointsAndCustomBreakpoints() {
        val palette = UsagePalette.parse(DisplayPreferences().palette)
        fun color(r: String) = palette.color(r.toBigDecimal())
        assertEquals(Color.GREEN, color("0"))
        assertEquals(Color.YELLOW, color("1"))
        assertEquals(Color(0xFFA500), color("2"))
        assertEquals(Color.RED, color("3"))
        assertEquals(Color.RED, color("999999"))
        assertEquals(Color(0x1AFF00), color("0.1"))
        assertEquals(Color(0x80FF00), color("0.5"))
        assertEquals(Color(0xFFD200), color("1.5"))
        assertEquals(Color(0xFFD200), palette.color(BigDecimal("1.25"), "1.5", "4.5"))
        listOf("1" to "3", "2" to "2", "3" to "2", "NaN" to "3", "2" to "1e9").forEach { (a, w) ->
            assertFailsWith<IllegalArgumentException> { UsagePalette.validateBreakpoints(a, w) }
        }
    }

    @Test fun pacingUsesWholeDayAllowanceAndHandlesWindowBoundaries() {
        val start = Instant.parse("2026-11-01T00:00:00Z")
        val definition = TrackerDefinition(unit = SpanUnit.WEEK, mode = PeriodMode.PROVIDER_WEEK, colorByQuotaGoal = true)
        val prefs = DisplayPreferences(weeklyPacing = true)
        val metric = QuotaMetricPresentation(BigDecimal(100), "test", reset = start.plusSeconds(604800), evaluatedAt = start.plusSeconds(25200))
        val value = StatusValue("week", "Week", "50", BigDecimal(50))
        assertEquals(0, assertNotNull(metric.tracker(value, definition, prefs).quotaGoalRatio).compareTo(BigDecimal("3.5")))
        assertEquals(BigDecimal("3.5"), metric.copy(evaluatedAt = start).tracker(value, definition, prefs).quotaGoalRatio)
        assertEquals(BigDecimal.ZERO, metric.copy(evaluatedAt = start).tracker(value.copy(raw = BigDecimal.ZERO), definition, prefs).quotaGoalRatio)
        val normalUsage = value.copy(raw = BigDecimal.ONE)
        fun ratio(at: Instant) = metric.copy(evaluatedAt = at).tracker(normalUsage, definition, prefs).quotaGoalRatio
        assertEquals(BigDecimal("0.07"), ratio(start.plusSeconds(1200)))
        for (day in 1L..6L) {
            val boundary = start.plusSeconds(day * 86400)
            val before = BigDecimal(7).divide(BigDecimal(100 * day), java.math.MathContext.DECIMAL128)
            val after = BigDecimal(7).divide(BigDecimal(100 * (day + 1)), java.math.MathContext.DECIMAL128)
            assertEquals(before, ratio(boundary.minusNanos(1)))
            assertEquals(before, ratio(boundary))
            assertEquals(after, ratio(boundary.plusNanos(1)))
        }
        assertEquals(BigDecimal("0.01"), ratio(start.plusSeconds(604800).minusNanos(1)))
        assertNull(metric.copy(evaluatedAt = start.minusSeconds(1)).tracker(value, definition, prefs).quotaGoalRatio)
        assertNull(metric.copy(evaluatedAt = start.plusSeconds(604800)).tracker(value, definition, prefs).quotaGoalRatio)
    }

    @Test fun portableAndLocalSettingsPreserveDecimalMultipliers() {
        val prefs = DisplayPreferences(aboveQuotaMultiplier = "2.5", warningQuotaMultiplier = "4.75")
        val config = TrackerConfiguration(listOf(TrackerDefinition()), prefs)
        val json = TrackerConfigurationJson.encode(config)
        assertEquals(prefs, TrackerConfigurationJson.decode(json).display)
        val settings = UsageSettings().apply { update(emptyList(), config.trackers, prefs) }
        assertEquals(prefs, UsageSettings().apply { loadState(settings.state) }.display)
        assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.decode(json.replace("4.75", "1")) }
    }
}
