package local.aiusagetrackers

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.*

class QuotaMetricPresentationTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val quota = QuotaMetricPresentation(BigDecimal("33783783.7837837838"), "Illustrative budget")
    private fun tracker(definition: TrackerDefinition = TrackerDefinition(showPercentUsed = true)) = TrackerResult(
        definition, ResolvedPeriod(listOf(UsageInterval(now.minusSeconds(3600), now)), ZoneOffset.UTC), 2_500_000
    )

    @Test
    fun compactShareAndGoalRatioAreIndependentOfVisibility() {
        val result = tracker(TrackerDefinition(name = "Today", showPercentUsed = true, colorByQuotaGoal = true))
        val value = WidgetText.trackerValue(result, now, quotaMetric = quota)
        assertEquals("Today: 2.5M|7.4%", value.displayText())
        assertEquals(
            0,
            assertNotNull(value.quotaGoalRatio).setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(BigDecimal("0.37"))
        )
        assertTrue(value.quotaColorEnabled)
        val hiddenPercent = WidgetText.trackerValue(
            result.copy(definition = result.definition.copy(showPercentUsed = false)),
            now,
            quotaMetric = quota
        )
        assertEquals("Today: 2.5M", hiddenPercent.displayText())
        assertEquals(value.quotaGoalRatio, hiddenPercent.quotaGoalRatio)
        val noColor = WidgetText.trackerValue(
            result.copy(definition = result.definition.copy(colorByQuotaGoal = false)),
            now,
            quotaMetric = quota
        )
        assertFalse(noColor.quotaColorEnabled)
        assertEquals(value.text, noColor.text)
    }

    @Test
    fun zeroMissingAndOverBudgetDoNotInventOrClampPercentage() {
        val budget = QuotaMetricPresentation(BigDecimal(100), "sample")
        val zero = budget.apply(StatusValue("a", "Day", "0", BigDecimal.ZERO), true, BigDecimal(20), true)
        assertEquals("0|0%", zero.text)
        assertEquals(
            QuotaMetricPresentation.color(BigDecimal.ZERO),
            StatusValueStyle.parse("").color(zero, java.awt.Color.BLACK)
        )
        val over = budget.apply(StatusValue("a", "Day", "150", BigDecimal(150)), true, BigDecimal(20), true)
        assertEquals("150|150%", over.text)
        assertEquals(
            QuotaMetricPresentation.color(BigDecimal(3)),
            StatusValueStyle.parse("").color(over, java.awt.Color.BLACK)
        )
        val missing = QuotaMetricPresentation(null, "missing").apply(zero.copy(text = "0"), true, BigDecimal(20), true)
        assertEquals("0|—", missing.text)
        assertEquals(
            java.awt.Color.BLACK,
            StatusValueStyle.parse("#FF0000|false|").color(missing, java.awt.Color.BLACK)
        )
        val unavailable =
            budget.apply(StatusValue("a", "Day", "unavailable", state = StatusValueState.UNAVAILABLE), true)
        assertEquals("unavailable|—", unavailable.text)
    }

    @Test
    fun gradientStopsAndSupportedGoals() {
        val green = QuotaMetricPresentation.color(BigDecimal.ZERO)
        val red = QuotaMetricPresentation.color(BigDecimal(3))
        assertEquals(green, QuotaMetricPresentation.color(BigDecimal(-1)))
        assertEquals(red, QuotaMetricPresentation.color(BigDecimal(4)))
        assertEquals(java.awt.Color.YELLOW, QuotaMetricPresentation.color(BigDecimal.ONE))
        assertEquals(java.awt.Color(0xFFA500), QuotaMetricPresentation.color(BigDecimal(2)))
        assertEquals("20", QuotaMetricPresentation.goal(TrackerDefinition())?.toPlainString())
        assertEquals("100", QuotaMetricPresentation.goal(TrackerDefinition(unit = SpanUnit.WEEK))?.toPlainString())
        assertEquals("40", QuotaMetricPresentation.goal(TrackerDefinition(amount = 2))?.toPlainString())
        assertEquals("2.5", QuotaMetricPresentation.goal(TrackerDefinition(unit = SpanUnit.HOUR))?.toPlainString())
        assertTrue(assertNotNull(QuotaMetricPresentation.goal(TrackerDefinition(unit = SpanUnit.MINUTE))) > BigDecimal.ZERO)
    }

    @Test
    fun bulkPercentageIncludesAllUnitsAndRequestButDoesNotFollowRemainingMode() {
        val reading =
            QuotaObservation("sample", now, QuotaUsage("sample", 20.0, now.plusSeconds(3600).epochSecond, "secondary"))
        val snapshot = UsageSnapshot(
            loading = false, storedEvents = 1, evaluatedAt = now,
            trackers = listOf(tracker(TrackerDefinition(unit = SpanUnit.HOUR))),
            collection = CollectionSnapshot(lastCompleted = now),
            analytics = UsageAnalytics(
                quotaObservations = listOf(reading),
                lastRequest = RequestReading(
                    now,
                    RequestUsage(RequestCounters(2500000, 2000000, null, 500000, null), "sample")
                )
            ),
            budgetEstimates = mapOf(
                QuotaPresentation.scope(reading) to QuotaBudgetEstimate(
                    now,
                    now.plusSeconds(3600),
                    20,
                    20.0,
                    quota.budget,
                    explanation = "sample"
                )
            )
        )
        val values = WidgetText.values(
            snapshot, DisplayPreferences(allTokenPercentUsed = true), true,
            AnalyticsDisplayOptions(quotaPercentMode = QuotaPercentMode.REMAINING)
        )
        assertEquals(listOf("2.5M|7.4%", "2.5M|7.4%"), values.map { it.text })
        val individual = WidgetText.values(
            snapshot,
            showLastRequest = true,
            options = AnalyticsDisplayOptions(lastRequestPercentUsed = true)
        )
        assertEquals("2.5M", individual.first().text)
        assertEquals("2.5M|7.4%", individual.last().text)
        assertEquals(
            StatusValueState.AVAILABLE,
            QuotaMetricPresentation(null, "stale", true).apply(
                values.last().copy(state = StatusValueState.AVAILABLE),
                false
            ).state
        )
    }

    @Test
    fun selectedScopeExpiryAndStalenessControlBudgetUse() {
        val observation =
            QuotaObservation("sample", now, QuotaUsage("sample", 20.0, now.plusSeconds(3600).epochSecond, "secondary"))
        val estimate =
            QuotaBudgetEstimate(now, now.plusSeconds(3600), 20, 20.0, BigDecimal(100), explanation = "sample")
        val snapshot = UsageSnapshot(
            loading = false, evaluatedAt = now,
            collection = CollectionSnapshot(lastCompleted = now),
            analytics = UsageAnalytics(quotaObservations = listOf(observation)),
            budgetEstimates = mapOf(QuotaPresentation.scope(observation) to estimate)
        )
        assertEquals(BigDecimal(100), QuotaMetricPresentation.select(snapshot, AnalyticsDisplayOptions()).budget)
        assertNull(QuotaMetricPresentation.select(snapshot, AnalyticsDisplayOptions(quotaScope = "missing")).budget)
        assertNull(
            QuotaMetricPresentation.select(
                snapshot.copy(evaluatedAt = now.plusSeconds(3600)),
                AnalyticsDisplayOptions()
            ).budget
        )
        assertNull(
            QuotaMetricPresentation.select(
                snapshot.copy(
                    budgetEstimates = mapOf(
                        QuotaPresentation.scope(
                            observation
                        ) to estimate.copy(observedAt = now.minusSeconds(1))
                    )
                ), AnalyticsDisplayOptions()
            ).budget
        )
        val stale = QuotaMetricPresentation.select(
            snapshot.copy(
                collection = CollectionSnapshot(
                    lastCompleted = now.plusSeconds(1000)
                )
            ), AnalyticsDisplayOptions()
        )
        assertTrue(stale.stale)
        assertEquals(StatusValueState.STALE, stale.apply(StatusValue("a", "", "1", BigDecimal.ONE), true).state)
    }

    @Test
    fun fiveHourSharesRespectSpanAndAreIndependentOfReportedPercentageMode() {
        val snapshot = AnalyticsSettingsPanel.sampleSnapshot(1)
        val definition = TrackerDefinition(
            unit = SpanUnit.HOUR, amount = 2, showPercentUsed = true, showFiveHourPercentUsed = true,
            metricOrder = TokenMetricOrder.FIVE_HOUR_TOKENS_WEEKLY,
            tokenPrefix = "Tokens ", weeklyPrefix = "W ", fiveHourPrefix = "5h ",
        )
        val options = AnalyticsDisplayOptions(
            showQuota = true, showFiveHourQuota = true, quotaPercentMode = QuotaPercentMode.USED,
            fiveHourPercentMode = QuotaPercentMode.REMAINING, estimateFiveHourBudget = true,
            lastRequestFiveHourPercentUsed = true, lastRequestFiveHourPrefix = "5h ",
            lastRequestMetricOrder = TokenMetricOrder.FIVE_HOUR_TOKENS_WEEKLY,
        )
        val values = WidgetText.values(snapshot.copy(trackers = listOf(tracker(definition))), showLastRequest = true, options = options)
        assertEquals(listOf("5h 40.7%|Tokens 2.5M|W 0.7%", "5h 0%|1.2K", "20%", "60%|~3.7M"), values.map { it.text })
        assertEquals("40%|~2.5M", WidgetText.values(snapshot, options = options.copy(
            fiveHourPercentMode = QuotaPercentMode.USED
        )).single { it.id == "quota:300" }.text)
        assertTrue("60% remaining" in AnalyticsPresentation.describe(snapshot, DisplayPreferences(), ZoneOffset.UTC, options))

        val fiveHourTrackers = listOf(PeriodMode.CALENDAR, PeriodMode.ELAPSED, PeriodMode.PROVIDER_FIVE_HOUR).map {
            tracker(definition.copy(mode = it, amount = 5))
        }
        val fiveHourValues = WidgetText.values(snapshot.copy(trackers = fiveHourTrackers), options = options)
        assertEquals(List(3) { "5h 40.7%|Tokens 2.5M|W 0.7%" }, fiveHourValues.take(3).map { it.text })
        val aligned = fiveHourTrackers.last()
        val colored = WidgetText.trackerValue(
            aligned.copy(definition = aligned.definition.copy(colorByQuotaGoal = true)),
            now, quotaMetric = quota, fiveHourMetric = QuotaMetricPresentation(BigDecimal(2_500_000), "sample"),
        )
        assertEquals(0, assertNotNull(colored.fiveHourColor?.ratio).compareTo(BigDecimal.ONE))

        val unsupported = listOf(definition.copy(amount = 6), definition.copy(unit = SpanUnit.DAY))
        val periods = WidgetText.values(snapshot.copy(trackers = unsupported.map(::tracker)), options = options)
        assertTrue(periods.take(2).all { it.text == "Tokens 2.5M|W 0.7%" })
        assertTrue(definition.copy(unit = SpanUnit.MINUTE, amount = 299).supportsFiveHourShare())
        assertFalse(definition.copy(unit = SpanUnit.MINUTE, amount = 300).supportsFiveHourShare())
        assertFalse(definition.copy(amount = 4, mode = PeriodMode.PROVIDER_FIVE_HOUR).supportsFiveHourShare())
        val missing = WidgetText.values(
            snapshot.copy(trackers = listOf(tracker(definition))),
            options = options.copy(fiveHourScope = "missing"),
        ).first()
        assertEquals("5h —|Tokens 2.5M|W 0.7%", missing.text)
        val reset = snapshot.evaluatedAt.plusSeconds(7200)
        val expired = WidgetText.values(
            snapshot.copy(evaluatedAt = reset, trackers = listOf(tracker(definition))), options = options,
        )
        assertEquals("5h —|Tokens 2.5M|W 0.7%", expired.first().text)
        assertEquals("unavailable|estimate unavailable", expired.single { it.id == "quota:300" }.text)
        val weeklyOnly = WidgetText.values(
            snapshot.copy(trackers = listOf(tracker(definition))),
            options = options.copy(planProfile = QuotaPlanProfile.CODEX_WEEKLY),
        ).first()
        assertEquals("Tokens 2.5M|W 0.7%", weeklyOnly.text)
    }

    @Test
    fun portableV3RoundTripAndSettingsCopiesPreserveOptions() {
        val definition = TrackerDefinition(showPercentUsed = true, colorByQuotaGoal = true)
        val display = DisplayPreferences(widgetFontSize = 10, allTokenPercentUsed = true)
        val json = TrackerConfigurationJson.encode(TrackerConfiguration(listOf(definition), display))
        assertTrue("\"version\": 11" in json)
        val decoded = TrackerConfigurationJson.decode(json)
        assertEquals(definition.copy(id = decoded.trackers.single().id), decoded.trackers.single())
        assertEquals(display, decoded.display)
        val settings = UsageSettings()
        settings.update(
            emptyList(),
            listOf(definition),
            display,
            analyticsDisplay = AnalyticsDisplayOptions(lastRequestPercentUsed = true)
        )
        val restored = UsageSettings().apply { loadState(settings.state) }
        assertEquals(display, restored.display)
        assertTrue(restored.analyticsDisplay.lastRequestPercentUsed)
        definition.showPercentUsed = false
        assertTrue(restored.trackers.single().showPercentUsed)
        assertFailsWith<IllegalArgumentException> { display.copy(widgetFontSize = 7).validate() }
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.decode(
                json.replace(
                    "\"widgetFontSize\": 10",
                    "\"widgetFontSize\": 25"
                )
            )
        }
    }

    @Test
    fun labelsAreEscapedAndFontCanReturnToIdeDefault() = SwingUtilities.invokeAndWait {
        val label = JLabel()
        val custom = WidgetAppearance.font(DisplayPreferences(widgetFontSize = 10), label.font)
        assertTrue(custom.size > 0)
        val default = WidgetAppearance.font(DisplayPreferences(), custom)
        assertEquals(javax.swing.UIManager.getFont("Label.font"), default)
        val value = quota.apply(StatusValue("a", "<Today&>", "2.5M", BigDecimal(2500000)), true, BigDecimal(20), true)
        val text = WidgetAppearance.text(value, java.awt.Color.BLACK)
        assertTrue("&lt;Today&amp;&gt;: <font" in text)
        assertTrue("<font color=\"#000000\">|</font>" in text)
        assertTrue("2.5M|7.4%" in text.replace(Regex("<[^>]+>"), ""))
    }

    @Test
    fun hourlyColorUsesOneEighthOfDailyGoalWithoutChangingPercentageMeaning() {
        val budget = QuotaMetricPresentation(BigDecimal(1_000_000), "sample")
        val definition =
            TrackerDefinition(name = "Hourly", unit = SpanUnit.HOUR, showPercentUsed = true, colorByQuotaGoal = true)
        val style = StatusValueStyle.parse("")
        for ((tokens, ratio) in listOf(0L to "0", 12_500L to "0.5", 25_000L to "1", 50_000L to "2")) {
            val value = WidgetText.trackerValue(tracker(definition).copy(total = tokens), now, quotaMetric = budget)
            assertEquals(0, assertNotNull(value.quotaGoalRatio).compareTo(BigDecimal(ratio)))
            assertEquals(QuotaMetricPresentation.color(BigDecimal(ratio)), style.color(value, java.awt.Color.BLACK))
        }
        val full = WidgetText.trackerValue(tracker(definition).copy(total = 25_000), now, quotaMetric = budget)
        assertEquals("Hourly: 25K|2.5%", full.displayText())
        val daily = WidgetText.trackerValue(
            tracker(definition.copy(unit = SpanUnit.DAY)).copy(total = 25_000),
            now,
            quotaMetric = budget
        )
        assertEquals(0, assertNotNull(daily.quotaGoalRatio).compareTo(BigDecimal("0.125")))
        assertEquals(full.text, daily.text)
        val multipleHours = WidgetText.trackerValue(tracker(definition.copy(amount = 2)), now, quotaMetric = budget)
        assertEquals(0, assertNotNull(multipleHours.quotaGoalRatio).compareTo(BigDecimal("50")))
        assertEquals(QuotaMetricPresentation.color(BigDecimal(3)), style.color(multipleHours, java.awt.Color.BLACK))
    }
}
