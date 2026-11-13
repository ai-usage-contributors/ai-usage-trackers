package local.aiusagetrackers

import java.awt.Color
import java.awt.image.BufferedImage
import java.math.BigDecimal
import javax.swing.JLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetWarningsTest {
    @Test fun sharedStaleWarningsAreDeduplicatedWithoutInlineIcons() {
        val quota = QuotaMetricPresentation(BigDecimal(100), "Weekly quota observation is stale", true)
        val values = listOf("Today", "Hourly", "This week").map {
            quota.apply(StatusValue(it, it, "1", BigDecimal.ONE), true, BigDecimal(20), true)
        }
        assertEquals(listOf("Weekly quota observation is stale"), WidgetWarnings.messages(UsageSnapshot(), values))
        assertTrue(values.all { it.state == StatusValueState.STALE && "stale" in it.accessibleText })
        assertTrue(values.all { "⚠" !in it.displayText() })
        assertTrue(WidgetWarnings.messages(UsageSnapshot(), emptyList()).isEmpty())
        assertTrue(WidgetWarnings.messages(UsageSnapshot(), values.map { it.copy(state = StatusValueState.AVAILABLE, warnings = emptyList()) }).isEmpty())
    }

    @Test fun aggregateIncludesCollectionHiddenTrackerAnalyticsAndMissingValues() {
        val snapshot = UsageSnapshot(
            issues = setOf("Shared issue"),
            collection = CollectionSnapshot(issues = setOf("Shared issue", "Collection issue"),
                sourceProblems = listOf(SourceProblem("sample.jsonl", null, setOf("Unreadable source")))),
            analytics = UsageAnalytics(issues = setOf("Analytics issue")),
            trackers = listOf(TrackerResult(TrackerDefinition(visible = false), ResolvedPeriod(emptyList(), java.time.ZoneOffset.UTC), null, setOf("Hidden tracker issue")))
        )
        val values = listOf(StatusValue("quota", "Weekly quota", "unavailable", state = StatusValueState.UNAVAILABLE),
            StatusValue("loading", "", "loading", state = StatusValueState.LOADING))
        val messages = WidgetWarnings.messages(snapshot, values)
        assertEquals(1, messages.count { it == "Shared issue" })
        assertTrue(messages.containsAll(listOf("Collection issue", "Analytics issue", "Hidden tracker issue", "Weekly quota: unavailable")))
        assertFalse(messages.any { "Unreadable source" in it })
        assertFalse(messages.any { "loading" in it })
    }

    @Test fun missingEstimateIsAWarningEvenWhileLocalTokensAreAvailable() {
        val value = QuotaMetricPresentation(null, "Estimate unavailable").apply(StatusValue("today", "Today", "1", BigDecimal.ONE), true)
        assertEquals(StatusValueState.AVAILABLE, value.state)
        assertEquals(listOf("Estimate unavailable"), WidgetWarnings.messages(UsageSnapshot(), listOf(value)))
        val data = AnalyticsSettingsPanel.sampleSnapshot(1).copy(budgetEstimates = emptyMap())
        val values = WidgetText.values(data, options = AnalyticsDisplayOptions(showQuota = true, estimateBudget = true))
        assertTrue("Weekly token budget estimate unavailable" in WidgetWarnings.messages(data, values))
    }

    @Test fun warningIconPaintRemainsLightOrangeAcrossForegroundColors() {
        fun render(foreground: Color): BufferedImage {
            val icon = WidgetWarnings.icon
            val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try { icon.paintIcon(JLabel().apply { this.foreground = foreground }, g, 0, 0) }
            finally { g.dispose() }
            return image
        }
        val green = render(Color.GREEN)
        val red = render(Color.RED)
        val pixels = green.getRGB(0, 0, green.width, green.height, null, 0, green.width)
        assertTrue(pixels.contentEquals(red.getRGB(0, 0, red.width, red.height, null, 0, red.width)))
        assertTrue(pixels.any { it == Color(0xFFB86C).rgb })
    }
}
