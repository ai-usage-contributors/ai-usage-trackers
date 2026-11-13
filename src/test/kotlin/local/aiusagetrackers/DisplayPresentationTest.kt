package local.aiusagetrackers

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayPresentationTest {
    @Test
    fun sharedSegmentFormattingKeepsHourlyAndUnavailableStatusTextIdentical() {
        val definition = TrackerDefinition(name = "Hourly", unit = SpanUnit.HOUR, showPeriodStart = true)
        val reading = snapshot(definition)
        val segment = WidgetText.trackerText(reading.trackers.single(), now)
        assertEquals("Hourly: 12:00 - now: 3.2M", segment)
        assertEquals("Codex: $segment", WidgetText.text(reading))
        val unavailable = reading.trackers.single().copy(total = null)
        assertEquals("Hourly: 12:00 - now: unavailable", WidgetText.trackerText(unavailable, now))
    }
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-02T12:34:56Z")

    private fun snapshot(definition: TrackerDefinition, at: Instant = now, inZone: ZoneId = zone): UsageSnapshot =
        UsageSnapshot(
            trackers = listOf(
                TrackerResult(
                    definition,
                    UsagePeriodResolver().resolve(definition, at, inZone),
                    3_200_000
                )
            ),
            loading = false, storedEvents = 12, evaluatedAt = at,
        )

    @Test
    fun infoShowsMillionsWithoutChangingPreviewOrUnavailable() {
        val base = snapshot(TrackerDefinition())
        listOf(30_243_111L to "30.2", 1_005_000L to "1.0", 0L to "0.0", Long.MAX_VALUE to "9223372036854.8").forEach { (total, expected) ->
            val result = base.trackers.single().copy(total = total)
            val info = UsageInfoText.describe(base.copy(trackers = listOf(result)), DisplayPreferences(), zone)
            assertTrue("$total (${expected}M) tokens" in info)
            assertTrue("$total tokens" in PeriodPresentation.describe(result, now))
        }
        val unavailable = base.trackers.single().copy(total = null)
        val info = UsageInfoText.describe(base.copy(trackers = listOf(unavailable)), DisplayPreferences(), zone)
        assertTrue("Today: unavailable" in info)
        assertFalse("(0.00M)" in info)
    }

    @Test
    fun hourlyStartIsOptInAndUsesResolvedReset() {
        val hourly = TrackerDefinition(name = "Hourly", unit = SpanUnit.HOUR)
        assertEquals("Codex: Hourly: 3.2M", WidgetText.text(snapshot(hourly)))
        hourly.showPeriodStart = true
        assertEquals("Codex: Hourly: 12:00 - now: 3.2M", WidgetText.text(snapshot(hourly)))
        hourly.startTime = "00:15"
        assertEquals("Codex: Hourly: 12:15 - now: 3.2M", WidgetText.text(snapshot(hourly)))
        hourly.unit = SpanUnit.DAY
        assertEquals("Codex: Hourly: 3.2M", WidgetText.text(snapshot(hourly)))
    }

    @Test
    fun completedHoursHaveAnActualEnd() {
        val hourly =
            TrackerDefinition(name = "Hourly", unit = SpanUnit.HOUR, showPeriodStart = true, includeCurrent = false)
        assertEquals("Codex: Hourly: 11:00 - 12:00: 3.2M", WidgetText.text(snapshot(hourly)))
        val boundary = Instant.parse("2026-11-02T12:00:00Z")
        assertEquals("Codex: Hourly: 11:00 - 12:00: 3.2M", WidgetText.text(snapshot(hourly, boundary)))
    }

    @Test
    fun elapsedAndRepeatingHoursUseTheirRealStartAndIncludePriorDate() {
        val hourly = TrackerDefinition(
            name = "Last two hours", unit = SpanUnit.HOUR, amount = 2,
            mode = PeriodMode.ELAPSED, showPeriodStart = true
        )
        val midnight = Instant.parse("2026-11-02T00:30:00Z")
        assertEquals("Codex: Last two hours: 2026-11-01 22:30 - now: 3.2M", WidgetText.text(snapshot(hourly, midnight)))
        hourly.mode = PeriodMode.REPEATING
        hourly.anchor = "2026-11-02T00:15"
        assertEquals("Codex: Last two hours: 12:15 - now: 3.2M", WidgetText.text(snapshot(hourly)))
    }

    @Test
    fun futureAndCollapsedHoursNeverPretendToEndNow() {
        val hourly = TrackerDefinition(
            name = "Hourly", unit = SpanUnit.HOUR, mode = PeriodMode.FIXED,
            fixedStart = "2026-11-03T00:00", fixedEnd = "2026-11-03T01:00", showPeriodStart = true
        )
        val future = WidgetText.text(snapshot(hourly))
        assertFalse("now" in future)
        assertTrue("2026-11-03" in future)
        val collapsed = TrackerResult(hourly, ResolvedPeriod(listOf(UsageInterval(now, now)), zone), 0)
        assertFalse(PeriodPresentation.endsNow(collapsed, collapsed.period.intervals.single(), now))
        assertEquals(now, PeriodPresentation.displayEnd(UsageInterval(now, now), now.minusSeconds(60)))
    }

    @Test
    fun clockPreferenceHandlesNoonMidnightAndSeconds() {
        val preferences = DisplayPreferences("dd/MM/yyyy", ClockFormat.TWELVE_HOUR)
        val format = DisplayFormats(preferences)
        assertEquals("02/11/2026 12:34:56 PM", format.dateTime(now, zone))
        assertEquals("12:00 AM", format.time(Instant.parse("2026-11-02T00:00:00Z"), zone))
        assertEquals("12:00 PM", format.time(Instant.parse("2026-11-02T12:00:00Z"), zone))
        val hourly =
            TrackerDefinition(name = "Hourly", unit = SpanUnit.HOUR, showPeriodStart = true, startTime = "00:15:30")
        assertEquals("Codex: Hourly: 12:15:30 PM - now: 3.2M", WidgetText.text(snapshot(hourly), preferences))
    }

    @Test
    fun dstOverlapShowsOffsetsAndHonorsTheTrackersZone() {
        val hourly = TrackerDefinition(
            name = "Hourly", unit = SpanUnit.HOUR, amount = 2,
            mode = PeriodMode.ELAPSED, showPeriodStart = true, zoneId = "Europe/Budapest"
        )
        val autumn = Instant.parse("2026-10-25T01:30:00Z")
        val view = snapshot(hourly, autumn)
        assertTrue("01:30 +02:00 - now" in WidgetText.text(view))
        val info = UsageInfoText.describe(view, DisplayPreferences(), zone)
        assertTrue("2026-10-25 01:30:00 +02:00" in info)
        assertTrue("2026-10-25 02:30:00 +01:00" in info)
    }

    @Test
    fun infoIncludesHiddenTrackersAndDiagnosticsAsBulletsWithoutGeneralDisclaimers() {
        val hidden = snapshot(TrackerDefinition(name = "Hidden", visible = false)).trackers.single()
            .copy(issues = setOf("Incomplete tracker"))
        val view = snapshot(TrackerDefinition(name = "Visible")).copy(
            trackers = snapshot(TrackerDefinition(name = "Visible")).trackers + hidden,
            collection = CollectionSnapshot(
                homes = 2,
                files = 5,
                lastSuccess = now.minusSeconds(10),
                issues = setOf("Source issue")
            ),
            historyStart = now.minusSeconds(86400),
            databasePath = "synthetic/usage.sqlite",
            issues = setOf("Refresh pending"),
        )
        val info = UsageInfoText.describe(view, DisplayPreferences(), zone)
        listOf(
            "Visible: 3200000 (3.2M) tokens", "Hidden: 3200000 (3.2M) tokens", "Hidden from status bar", "Incomplete tracker",
            "Last warning-free collection: 2026-11-02 12:34:46", "Earliest recorded usage: 2026-11-01",
            "Live source homes: 2", "Live log files: 5", "Stored usage events: 12", "synthetic/usage.sqlite",
            "Last completed collection"
        ).forEach { assertTrue(it in info, it) }
        assertTrue(info.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("• ") })
        assertFalse("Cached input" in info)
        assertFalse("System local time zone" in info)
        assertFalse("Source issue" in info)
        assertFalse("Hidden" in WidgetText.text(view))
        assertTrue(WidgetText.tooltip(view).length < 160)
        assertFalse("⚠" in WidgetText.text(view))
    }

    @Test
    fun problemFilesFollowExistingSummaryAndRespectDateFormat() {
        val collection = CollectionSnapshot(lastCompleted = now, issues = setOf("Existing summary"), sourceProblems = listOf(
            SourceProblem("rollout-2026-10-23-test.jsonl", java.time.LocalDate.parse("2026-10-23"), setOf("Malformed or incomplete log records; retried on refresh")),
            SourceProblem("unknown\nname.jsonl", null, setOf("Session identity missing; ambiguous copy excluded"))
        ))
        val info = UsageInfoText.describe(snapshot(TrackerDefinition()).copy(collection = collection), DisplayPreferences("dd/MM/yyyy"), zone)
        assertTrue("Last completed collection: 02/11/2026 12:34:56" in info)
        assertTrue("Last warning-free collection: none" in info)
        val pages = UsageDetailPages.capture(snapshot(TrackerDefinition()).copy(collection = collection), DisplayPreferences("dd/MM/yyyy"), zone, AnalyticsDisplayOptions())
        assertFalse("rollout-2026-10-23-test.jsonl" in info)
        assertTrue("Existing summary" in pages.warnings)
        assertTrue("rollout-2026-10-23-test.jsonl — 23/10/2026 — Malformed" in pages.integrity)
        assertTrue("unknown name.jsonl — date unknown — Session identity" in pages.integrity)
    }

    @Test
    fun customFormatsApplyToEveryDisplayedTimestampAndPreview() {
        val view =
            snapshot(TrackerDefinition()).copy(collection = CollectionSnapshot(lastSuccess = now), historyStart = now)
        val preferences = DisplayPreferences("dd MMM yyyy", ClockFormat.TWELVE_HOUR)
        val info = UsageInfoText.describe(view, preferences, zone)
        assertFalse("2026-11-02" in info)
        assertTrue("02 Nov 2026 12:34:56 PM" in info)
        assertTrue("02 Nov 2026 12:00:00 AM" in PeriodPresentation.describe(view.trackers.single(), now, preferences))
    }

    @Test
    fun badSavedFormatsFallBackButEditorValidationRejectsThem() {
        listOf("", "yyyy-QQQQQQ", "yyyy-MM-dd HH:mm", "'unclosed").forEach { pattern ->
            val invalid = DisplayPreferences(pattern)
            assertFailsWith<RuntimeException> { invalid.validate() }
            assertEquals("2026-11-02 12:34:56", DisplayFormats(invalid).dateTime(now, zone))
        }
        val settings = UsageSettings()
        settings.loadState(UsageSettings.SettingsState().apply { display = DisplayPreferences("bad [") })
        assertEquals(DisplayPreferences(), settings.display)
    }

    @Test
    fun displaySettingsAreCopiedAndPreservedDuringTrackerEdits() {
        val settings = UsageSettings()
        val display = DisplayPreferences("dd/MM/yyyy", ClockFormat.TWELVE_HOUR)
        settings.update(emptyList(), TrackerDefinition.defaults(), display)
        display.datePattern = "yyyy"
        settings.display.datePattern = "MM"
        settings.state.display.datePattern = "dd"
        assertEquals("dd/MM/yyyy", settings.display.datePattern)
        settings.update(listOf("synthetic"), TrackerDefinition.defaults())
        assertEquals(ClockFormat.TWELVE_HOUR, settings.display.clockFormat)
        assertEquals("dd/MM/yyyy", settings.display.datePattern)
    }

    @Test
    fun disclaimersResolveSystemAndOverriddenZones() {
        val text = DisclaimersText.describe(
            listOf(
                TrackerDefinition(name = "Local"),
                TrackerDefinition(name = "Fixed zone", zoneId = "America/New_York")
            ), ZoneId.of("Europe/Budapest")
        )
        assertTrue("Local time zone: Europe/Budapest (system)" in text)
        assertTrue("Fixed zone time zone: America/New_York" in text)
        assertTrue("Cached input" in text)
        assertTrue("retained" in text)
    }
}
