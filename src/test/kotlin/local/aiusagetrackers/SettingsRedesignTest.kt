package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRedesignTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun allowancesMultiplyExactlyAndStopAtOneWeek() {
        fun goal(unit: SpanUnit, amount: Int) = QuotaMetricPresentation.goal(TrackerDefinition(unit = unit, amount = amount))
        assertEquals(0, assertNotNull(goal(SpanUnit.MINUTE, 30)).compareTo(BigDecimal("1.25")))
        assertEquals("10.0", goal(SpanUnit.HOUR, 4)?.toPlainString())
        assertEquals("60", goal(SpanUnit.DAY, 3)?.toPlainString())
        assertEquals("100", goal(SpanUnit.DAY, 7)?.toPlainString())
        assertEquals("100", goal(SpanUnit.HOUR, 168)?.toPlainString())
        assertNull(goal(SpanUnit.HOUR, 169)); assertNull(goal(SpanUnit.DAY, 8))
        assertNull(goal(SpanUnit.WEEK, 2)); assertNull(goal(SpanUnit.MONTH, 1))
        assertNull(QuotaMetricPresentation.goal(TrackerDefinition(mode = PeriodMode.FIXED)))
        assertNull(QuotaMetricPresentation.goal(TrackerDefinition(mode = PeriodMode.REPEATING)))
        val unsupported = QuotaMetricPresentation(null, "No estimate", true).tracker(
            StatusValue("long", "Two weeks", "1", BigDecimal.ONE),
            TrackerDefinition(unit = SpanUnit.WEEK, amount = 2, colorByQuotaGoal = true), DisplayPreferences())
        assertFalse(unsupported.quotaColorEnabled)
        assertTrue(unsupported.warnings.isEmpty())
        assertEquals(StatusValueState.AVAILABLE, unsupported.state)
    }

    @Test fun pacingUsesResetProgressWithoutChangingReportedPercentageOrDailyGoal() {
        val reset = Instant.parse("2026-11-08T12:00:00Z")
        val start = reset.minusSeconds(604800)
        val clock = Clock.fixed(start.plusSeconds(3600), ZoneId.of("Europe/Budapest"))
        val early = QuotaMetricPresentation(BigDecimal(100), "sample", reset = reset, evaluatedAt = clock.instant())
        val late = early.copy(evaluatedAt = reset.minusSeconds(3600))
        val definition = TrackerDefinition(unit = SpanUnit.WEEK, mode = PeriodMode.PROVIDER_WEEK, colorByQuotaGoal = true, showPercentUsed = true)
        val value = StatusValue("week", "Week", "90", BigDecimal(90))
        val preferences = DisplayPreferences(weeklyPacing = true)
        val earlyValue = early.tracker(value, definition, preferences)
        val lateValue = late.tracker(value, definition, preferences)
        assertTrue(assertNotNull(earlyValue.quotaGoalRatio) > BigDecimal("6"))
        assertTrue(assertNotNull(lateValue.quotaGoalRatio) < BigDecimal.ONE)
        assertEquals("90|90%", earlyValue.text); assertEquals(earlyValue.text, lateValue.text)
        assertEquals(0, assertNotNull(early.copy(evaluatedAt = start.plusSeconds(302400)).tracker(value.copy(raw = BigDecimal(50)), definition, preferences).quotaGoalRatio).compareTo(BigDecimal("0.875")))
        val daily = definition.copy(mode = PeriodMode.CALENDAR, unit = SpanUnit.DAY)
        assertEquals(early.tracker(value, daily, preferences.copy(weeklyPacing = false)).quotaGoalRatio,
            early.tracker(value, daily, preferences).quotaGoalRatio)
        assertEquals(0, assertNotNull(late.tracker(value, definition, preferences.copy(weeklyPacing = false)).quotaGoalRatio).compareTo(BigDecimal("0.9")))
        assertNull(late.copy(evaluatedAt = reset).tracker(value, definition, preferences).quotaGoalRatio)
        assertNull(late.copy(budget = null).tracker(value, definition, preferences).quotaGoalRatio)
        assertTrue(assertNotNull(late.tracker(value.copy(raw = BigDecimal(101)), definition, preferences).quotaGoalRatio) > BigDecimal.ONE)
    }

    @Test fun v4PaletteAndPacingRoundTripWithLegacyReaderCompatibility() {
        val preferences = DisplayPreferences(weeklyPacing = true, palette = "#123456,#00FF00,#FFFF00,#FF8800,#FF0000")
        val config = TrackerConfiguration(listOf(TrackerDefinition(hideLabel = true)), preferences)
        val text = TrackerConfigurationJson.encode(config)
        assertTrue("\"version\": 11" in text)
        val decoded = TrackerConfigurationJson.decode(text)
        assertEquals(preferences, decoded.display)
        assertTrue(decoded.trackers.single().hideLabel)
        val legacy = TrackerDefinition(mode = PeriodMode.REPEATING, anchor = "2026-01-01T04:00", startTime = "04:00")
        val imported = TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(listOf(legacy))))
        assertEquals(legacy.copy(id = imported.trackers.single().id), imported.trackers.single())
        assertFailsWith<IllegalArgumentException> { preferences.copy(palette = "red").validate() }
        val settings = UsageSettings()
        settings.update(emptyList(), config.trackers, preferences)
        val restored = UsageSettings().apply { loadState(settings.state) }
        assertEquals(preferences, restored.display)
    }

    @Test fun sharedPaletteOverridesOldColorsAndKeepsNeutralValuesConsistent() {
        val preferences = DisplayPreferences(palette = "#123456,#00FF00,#FFFF00,#FF8800,#FF0000")
        val value = StatusValue("value", "Today", "1", BigDecimal.ONE, style = "#FFFFFF|false|0=#FFFFFF",
            quotaGoalRatio = BigDecimal.ONE, quotaColorEnabled = true, palette = preferences.palette)
        assertEquals(Color.YELLOW, StatusValueStyle.forDisplay(value.style).color(value, Color.BLACK))
        assertEquals(Color.decode("#123456"), StatusValueStyle.forDisplay(value.style).color(value.copy(quotaColorEnabled = false), Color.BLACK))
        val html = WidgetAppearance.text(value, Color.BLACK)
        assertTrue("Today: <font" in html)
        assertTrue("#FFFF00" in html)
    }

    @Test fun malformedLogsAreClassifiedAtStorageBoundaryAndOnlyAppearOnIntegrityPage() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("sample.jsonl")
        Files.writeString(source, TestLogs.meta() + "\n" + TestLogs.event(100, "2026-11-05T11:00:00Z") + "\n{broken\n")
        val now = Instant.parse("2026-11-05T12:00:00Z")
        UsageDatabase(home.resolve("usage.sqlite")).use { database ->
            CodexUsageCollector(database).collect(Discovery(setOf(home), emptySet()), now)
            val collection = database.collection()
            assertTrue(collection.integrityIssues.isNotEmpty())
            assertTrue(collection.operationalIssues.isEmpty())
            val snapshot = UsageSnapshot(loading = false, storedEvents = 1, evaluatedAt = now,
                collection = collection, issues = collection.issues, operationalIssues = collection.operationalIssues,
                integrityIssues = collection.integrityIssues)
            val pages = UsageDetailPages.capture(snapshot, DisplayPreferences(), ZoneOffset.UTC, AnalyticsDisplayOptions(), false)
            assertTrue("sample.jsonl" in pages.integrity)
            collection.integrityIssues.forEach {
                assertFalse(it in pages.warnings); assertFalse(it in pages.usage)
            }
            assertTrue(WidgetWarnings.messages(snapshot, emptyList()).isEmpty())
        }
    }

    @Test fun hoverSummariesContainOneSentenceAndAtMostFifteenWords() {
        val help = "Choose a shared color palette for every displayed metric and its calculated quota thresholds. Detailed explanations belong on the detail pages."
        val summary = PluginTooltips.concise(help)
        assertTrue(summary.split(' ').size <= 15)
        assertEquals(1, summary.count { it == '.' })
        assertFalse("Detailed" in summary)
    }

    @Test fun removedSchedulesRemainStableUntilExplicitlyReplaced() {
        val original = TrackerDefinition(mode = PeriodMode.FIXED, fixedStart = "2026-11-01T04:00", fixedEnd = "2026-11-02T06:00", startTime = "04:00")
        val clock = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC)
        val resolver = UsagePeriodResolver()
        val before = resolver.resolve(original, clock.instant(), ZoneOffset.UTC)
        val imported = TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(listOf(original)))).trackers.single()
        assertEquals(before, resolver.resolve(imported, clock.instant(), ZoneOffset.UTC))
        assertTrue(imported.usesLegacySchedule())
        val automatic = imported.automaticSchedule()
        assertFalse(automatic.usesLegacySchedule())
        assertEquals(Instant.parse("2026-11-05T00:00:00Z"), resolver.resolve(automatic, clock.instant(), ZoneOffset.UTC).intervals.single().start)
        assertEquals("2026-11-01T04:00", imported.fixedStart)
    }
}
