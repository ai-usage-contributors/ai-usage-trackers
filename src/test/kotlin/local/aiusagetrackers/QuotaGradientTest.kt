package local.aiusagetrackers

import com.intellij.util.xmlb.XmlSerializer
import java.awt.Color
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import javax.swing.SwingUtilities
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

    @Test fun standaloneFiveHourQuotaUsesElapsedHourAndColorsUsedInEitherDisplayMode() {
        val start = Instant.parse("2026-11-01T00:00:00Z")
        val end = start.plusSeconds(5 * 3600)
        val options = AnalyticsDisplayOptions(showFiveHourQuota = true)
        val style = StatusValueStyle.parse("")
        fun value(
            percent: Double, now: Instant = start, reset: Instant? = end,
            mode: QuotaPercentMode = QuotaPercentMode.USED, enableColor: Boolean = true,
        ): StatusValue {
            val snapshot = UsageSnapshot(
                loading = false, evaluatedAt = now, collection = CollectionSnapshot(lastCompleted = start),
                analytics = UsageAnalytics(quotaObservations = listOf(
                    QuotaObservation("test", start, QuotaUsage("codex", percent, reset?.epochSecond, "primary", 300))
                ))
            )
            return WidgetText.values(
                snapshot, DisplayPreferences(coloringEnabled = enableColor), options = options.copy(fiveHourPercentMode = mode)
            ).single { it.id == "quota:300" }
        }
        assertEquals(Color.GREEN, style.color(value(0.0), Color.BLACK))
        assertEquals(Color(0x80FF00), style.color(value(10.0), Color.BLACK))
        assertEquals(Color.YELLOW, style.color(value(20.0), Color.BLACK))
        assertEquals(Color(0xFFD200), style.color(value(30.0), Color.BLACK))
        assertEquals(Color(0xFFA500), style.color(value(40.0), Color.BLACK))
        assertEquals(Color.RED, style.color(value(60.0), Color.BLACK))
        val remaining = value(40.0, mode = QuotaPercentMode.REMAINING)
        assertEquals("60%", remaining.text)
        assertEquals(Color(0xFFA500), style.color(remaining, Color.BLACK))
        for (hour in 1L..4) {
            val boundary = start.plusSeconds(hour * 3600)
            val percent = 20.0 * hour
            assertEquals(Color.YELLOW, style.color(value(percent, boundary.minusNanos(1)), Color.BLACK))
            assertEquals(Color.YELLOW, style.color(value(percent, boundary), Color.BLACK))
            assertEquals(Color.YELLOW, style.color(value(percent + 20, boundary.plusNanos(1)), Color.BLACK))
        }
        assertEquals(Color.YELLOW, style.color(value(100.0, end.minusNanos(1)), Color.BLACK))
        assertEquals(Color.BLACK, style.color(value(40.0, end), Color.BLACK))
        assertEquals(Color.BLACK, style.color(value(40.0, reset = null), Color.BLACK))
        assertEquals(Color.BLACK, style.color(value(40.0, reset = end.plusSeconds(1)), Color.BLACK))
        assertEquals(Color.BLACK, style.color(value(40.0, enableColor = false), Color.BLACK))
        val withEstimate = WidgetText.values(
            AnalyticsSettingsPanel.sampleSnapshot(1), options = options.copy(estimateFiveHourBudget = true)
        ).single { it.id == "quota:300" }
        assertTrue("<font color=\"#000000\">|</font>" in WidgetAppearance.text(withEstimate, Color.BLACK))
    }

    @Test fun trackerFiveHourColorUsesItsDurationAndStaysOnItsOwnReorderedValue() = SwingUtilities.invokeAndWait {
        val now = Instant.parse("2026-11-01T01:00:00Z")
        val definition = TrackerDefinition(
            name = "Two hours", unit = SpanUnit.HOUR, amount = 2, showPeriodStart = true,
            showPercentUsed = true, showFiveHourPercentUsed = true, colorByQuotaGoal = true,
            metricOrder = TokenMetricOrder.WEEKLY_FIVE_HOUR_TOKENS, fiveHourPrefix = "<5h>| ",
        )
        val period = ResolvedPeriod(listOf(UsageInterval(now.minusSeconds(3600), now.plusSeconds(3600))), ZoneOffset.UTC)
        val weekly = QuotaMetricPresentation(BigDecimal(2000), "weekly")
        val fiveHour = QuotaMetricPresentation(BigDecimal(125), "five-hour")
        fun value(
            prefs: DisplayPreferences = DisplayPreferences(),
            tracker: TrackerDefinition = definition,
            fiveHourMetric: QuotaMetricPresentation = fiveHour,
        ) = WidgetText.trackerValue(TrackerResult(tracker, period, 100), now, prefs, weekly, fiveHourMetric)

        val prefs = DisplayPreferences(palette = "#123456,#00FF00,#FFFF00,#FFA500,#FF0000")
        val value = value(prefs)
        val span = assertNotNull(value.fiveHourColor)
        assertEquals("<5h>| 80%", value.text.substring(span.start, span.start + span.length))
        assertEquals(0, assertNotNull(span.ratio).compareTo(BigDecimal(2)))
        assertEquals(0, assertNotNull(value.quotaGoalRatio).compareTo(BigDecimal.ONE))
        val rendered = WidgetAppearance.text(value, Color.BLACK)
        assertTrue("<font color=\"#FFA500\">&lt;5h&gt;| 80%</font>" in rendered)
        assertTrue("<font color=\"#FFFF00\">100</font>" in rendered)
        assertEquals(2, Regex.escape("<font color=\"#123456\">|</font>").toRegex().findAll(rendered).count())
        val noGoal = value(prefs, tracker = definition.copy(colorByQuotaGoal = false))
        assertNull(noGoal.fiveHourColor)
        assertFalse(noGoal.quotaColorEnabled)
        assertFalse("#FFA500" in WidgetAppearance.text(noGoal, Color.BLACK))
        val missing = value(fiveHourMetric = fiveHour.copy(budget = null))
        assertTrue("<font color=\"#000000\">&lt;5h&gt;| —</font>" in WidgetAppearance.text(missing, Color.BLACK))
        val sharedColor = value(prefs.copy(separateFiveHourColors = false))
        assertNull(sharedColor.fiveHourColor)
        assertTrue("<font color=\"#FFFF00\">&lt;5h&gt;| 80%</font>" in WidgetAppearance.text(sharedColor, Color.BLACK))
        assertFalse(WidgetAppearance.usesHtml(value(DisplayPreferences(coloringEnabled = false))))

        val button = RibbonValueButton(value, javax.swing.JLabel().font, Color.BLACK, false) {}
        val metrics = button.getFontMetrics(button.font)
        val visible = value.displayText().substringBefore("<5h>") + "<5"
        button.render(maximum = metrics.stringWidth("$visible…") + button.insets.left + button.insets.right)
        assertTrue("<font color=\"#FFA500\">&lt;5</font>" in button.text)
        assertTrue("<font color=\"#123456\">…</font>" in button.text)
        button.render(maximum = 10)
        assertFalse("#FFA500" in button.text)
    }

    @Test fun separateFiveHourColorsDefaultOnAndPersistWhenDisabled() {
        val enabled = DisplayPreferences()
        assertTrue(enabled.separateFiveHourColors)
        val disabled = enabled.copy(separateFiveHourColors = false)
        val trackers = TrackerDefinition.defaults()
        val legacyJson = TrackerConfigurationJson.encode(TrackerConfiguration(trackers, enabled))
        assertTrue(TrackerConfigurationJson.decode(legacyJson).display!!.separateFiveHourColors)
        val json = TrackerConfigurationJson.encode(TrackerConfiguration(trackers, disabled))
        assertTrue("\"version\": 14" in json)
        assertFalse(TrackerConfigurationJson.decode(json).display!!.separateFiveHourColors)
        val settings = UsageSettings().apply { update(emptyList(), trackers, disabled) }
        val persisted = XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), UsageSettings.SettingsState::class.java)
        assertFalse(UsageSettings().apply { loadState(persisted) }.display.separateFiveHourColors)
    }
}
