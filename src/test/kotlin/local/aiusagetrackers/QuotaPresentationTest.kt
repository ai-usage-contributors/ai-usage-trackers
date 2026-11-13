package local.aiusagetrackers

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaPresentationTest {
    @Test fun customLabelsPersistAndRenderForBothWindows() {
        val options = AnalyticsDisplayOptions(showQuota = true, showReset = true,
            showFiveHourQuota = true, showFiveHourReset = true,
            weeklyQuotaLabel = "Allowance", weeklyResetLabel = "Renewal",
            fiveHourQuotaLabel = "Short allowance", fiveHourResetLabel = "Short renewal")
        val settings = UsageSettings()
        settings.update(emptyList(), emptyList(), analyticsDisplay = options)
        val restored = UsageSettings().also {
            it.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(settings.state), UsageSettings.SettingsState::class.java))
        }
        assertEquals(options, restored.analyticsDisplay)
        val labels = WidgetText.values(snapshot(reading("one", 12.36)), options = restored.analyticsDisplay)
            .filter { it.id.startsWith("quota:") || it.id.startsWith("reset:") }.map { it.label }
        assertEquals(listOf("Allowance", "Renewal", "Short allowance", "Short renewal"), labels)
        assertEquals("12.4%", QuotaPresentation.select(snapshot(reading("one", 12.36))).percentage(QuotaPercentMode.USED))
        assertFailsWith<IllegalArgumentException> { options.copy(weeklyQuotaLabel = "\n").validate() }
    }

    @Test
    fun legacyPlacementSettingRemainsReadableAndDoesNotReplaceDraftTrackers() {
        val state = XmlSerializer.deserialize(
            Element("state").addContent(
                Element("option").setAttribute("name", "widgetAnchor").setAttribute("value", "before Encoding")
            ),
            UsageSettings.SettingsState::class.java
        )
        val settings = UsageSettings().also { it.loadState(state) }
        val trackers = settings.trackers.reversed()
        settings.update(emptyList(), trackers)
        assertEquals("before Encoding", settings.getState().widgetAnchor)
        assertEquals(trackers, settings.trackers)
    }

    private val refreshed = Instant.parse("2026-11-03T14:00:00Z")
    private fun reading(
        source: String,
        percent: Double?,
        seconds: Long = 0,
        reset: Long? = refreshed.plusSeconds(3 * 86400 + 13 * 3600 + 2 * 60).epochSecond
    ) =
        QuotaObservation(
            source,
            refreshed.minusSeconds(60).plusSeconds(seconds),
            QuotaUsage("codex", percent, reset, "secondary")
        )

    private fun snapshot(vararg readings: QuotaObservation) = UsageSnapshot(
        loading = false, storedEvents = 1, evaluatedAt = refreshed,
        collection = CollectionSnapshot(lastCompleted = refreshed),
        analytics = UsageAnalytics(quotaObservations = readings.toList()),
    )

    @Test fun weeklyQuotaColoringIsOptInAndPersists() {
        val data = snapshot(reading("one", 25.0))
        val options = AnalyticsDisplayOptions(showQuota = true)
        fun value(settings: AnalyticsDisplayOptions) = WidgetText.values(data, options = settings).first { it.id == "quota:10080" }
        val defaultValue = value(options)
        assertFalse(defaultValue.quotaColorEnabled)
        assertEquals(java.awt.Color.BLACK, StatusValueStyle.parse("").color(defaultValue, java.awt.Color.BLACK))
        val enabled = options.copy(colorWeeklyQuota = true)
        assertTrue(value(enabled).quotaColorEnabled)
        val settings = UsageSettings().apply { update(emptyList(), emptyList(), analyticsDisplay = enabled) }
        assertEquals(enabled, UsageSettings().apply { loadState(settings.state) }.analyticsDisplay)
        val legacy = XmlSerializer.deserialize(Element("AnalyticsDisplayOptions"), AnalyticsDisplayOptions::class.java)
        assertFalse(legacy.colorWeeklyQuota)
    }

    @Test fun remainingQuotaMovesFromBelowQuotaToWarningAsItIsConsumed() {
        val prefs = DisplayPreferences(palette = ",#123456,#345678,#56789A,#789ABC", warningQuotaMultiplier = "4")
        val options = AnalyticsDisplayOptions(showQuota = true, colorWeeklyQuota = true, quotaPercentMode = QuotaPercentMode.REMAINING)
        fun value(used: Double, settings: AnalyticsDisplayOptions = options) =
            WidgetText.values(snapshot(reading("one", used)), prefs, options = settings).first { it.id == "quota:10080" }
        val full = value(0.0)
        val empty = value(100.0)
        assertEquals("100%", full.text)
        assertEquals("0%", empty.text)
        assertEquals(java.awt.Color(0x123456), StatusValueStyle.parse("").color(full, java.awt.Color.BLACK))
        assertEquals(java.awt.Color(0x789ABC), StatusValueStyle.parse("").color(empty, java.awt.Color.BLACK))
        assertEquals(java.math.BigDecimal("1.00"), value(25.0).quotaGoalRatio)
        assertEquals(java.math.BigDecimal("0.25"), value(25.0, options.copy(quotaPercentMode = QuotaPercentMode.USED)).quotaGoalRatio)
        assertFalse(value(25.0, options.copy(colorWeeklyQuota = false)).quotaColorEnabled)
        val expired = snapshot(reading("one", 100.0)).copy(evaluatedAt = refreshed.plusSeconds(500000))
        val missing = WidgetText.values(expired, prefs, options = options).first { it.id == "quota:10080" }
        assertEquals(java.awt.Color.BLACK, StatusValueStyle.parse("").color(missing, java.awt.Color.BLACK))
    }

    @Test
    fun newestObservationIsSelectedWithoutCombiningAndPinnedScopeNeverFallsBack() {
        val older = reading("old", 90.0)
        val latest = reading("new", 13.0, 10)
        val future = reading("future", 99.0, 120)
        val snapshot = snapshot(older, latest, future)
        val automatic = QuotaPresentation.select(snapshot)
        assertEquals(latest, automatic.reading)
        assertTrue(automatic.multipleScopes)
        assertEquals("13%", automatic.percentage(QuotaPercentMode.USED))
        assertEquals("87%", automatic.percentage(QuotaPercentMode.REMAINING))
        assertEquals(
            older,
            QuotaPresentation.select(
                snapshot,
                AnalyticsDisplayOptions(quotaScope = QuotaPresentation.scope(older))
            ).reading
        )
        assertNull(QuotaPresentation.select(snapshot, AnalyticsDisplayOptions(quotaScope = "missing")).reading)
        assertEquals(
            automatic,
            QuotaPresentation.select(
                snapshot.copy(
                    analytics = snapshot.analytics.copy(
                        quotaObservations = listOf(
                            future,
                            latest,
                            older
                        )
                    )
                )
            )
        )
    }

    @Test
    fun resetDurationIsSampledButExpiryFollowsTheResetBoundary() {
        val snapshot = snapshot(reading("one", 13.0))
        val options = AnalyticsDisplayOptions(showQuota = true, showReset = true)
        val text = WidgetText.text(snapshot, analyticsDisplay = options)
        assertTrue("Weekly quota: 13% · Weekly reset: 3d 13h 2m" in text)
        val later = snapshot.copy(evaluatedAt = refreshed.plusSeconds(120))
        assertEquals(text, WidgetText.text(later, analyticsDisplay = options))
        val next = later.copy(collection = CollectionSnapshot(lastCompleted = refreshed.plusSeconds(120)))
        assertTrue("Weekly reset: 3d 13h 0m" in WidgetText.text(next, analyticsDisplay = options))
        val pastReset = snapshot.copy(evaluatedAt = refreshed.plusSeconds(500_000))
        assertTrue(
            "Weekly quota: unavailable · Weekly reset: unavailable" in WidgetText.text(
                pastReset,
                analyticsDisplay = options
            )
        )
        val expired = pastReset.copy(collection = CollectionSnapshot(lastCompleted = pastReset.evaluatedAt))
        assertTrue(
            "Weekly quota: unavailable · Weekly reset: unavailable" in WidgetText.text(
                expired,
                analyticsDisplay = options
            )
        )
        assertTrue(QuotaPresentation.select(expired).warning(true)!!.contains("passed"))
    }

    @Test
    fun missingPartialAndStaleReadingsHaveIndependentWarningsWithoutInventedValues() {
        val noReset = QuotaPresentation.select(snapshot(reading("one", 0.0, reset = null)))
        assertEquals("0%", noReset.percentage(QuotaPercentMode.USED))
        assertEquals("100%", noReset.percentage(QuotaPercentMode.REMAINING))
        assertNull(noReset.warning(false))
        assertTrue(noReset.warning(true)!!.contains("reset timestamp"))
        val resetOnly = QuotaPresentation.select(snapshot(reading("one", null)))
        assertNull(resetOnly.percentage(QuotaPercentMode.REMAINING))
        assertTrue(resetOnly.warning(false)!!.contains("percentage"))
        assertNull(resetOnly.warning(true))
        val stale = QuotaPresentation.select(snapshot(reading("one", 100.0, -1000)))
        assertEquals("0%", stale.percentage(QuotaPercentMode.REMAINING))
        assertTrue(stale.warning(false)!!.contains("15 minutes"))
        assertNull(QuotaPresentation.select(snapshot().copy(collection = CollectionSnapshot())).resetDuration())
        assertTrue(QuotaPresentation.select(snapshot()).warning(false)!!.contains("No weekly"))
    }

    @Test
    fun infoShowsOnlyOneScopeAndHonorsPercentagePreference() {
        val snapshot = snapshot(reading("old", 90.0), reading("new", 13.0, 10))
        val info = AnalyticsPresentation.describe(
            snapshot,
            DisplayPreferences(),
            ZoneOffset.UTC,
            AnalyticsDisplayOptions(quotaPercentMode = QuotaPercentMode.REMAINING)
        )
        assertTrue("Weekly quota: 87% remaining" in info)
        assertEquals(1, info.lineSequence().count { it.startsWith("• Weekly quota:") })
        assertEquals(1, info.lineSequence().count { it.startsWith("• Weekly reset:") })
        assertFalse("old/codex" in info)
        assertTrue("multiple scopes available" in info)
    }

    @Test
    fun newOptionsRoundTripAndLegacySettingsReceiveShortLabelAndDisabledQuota() {
        val options = AnalyticsDisplayOptions(
            "Request",
            showQuota = true,
            showReset = true,
            QuotaPercentMode.REMAINING,
            "source/codex/secondary"
        )
        val state = UsageSettings.SettingsState().apply { analyticsDisplay = options }
        val restored =
            XmlSerializer.deserialize(XmlSerializer.serialize(state), UsageSettings.SettingsState::class.java)
        assertEquals(options, restored.analyticsDisplay)
        val settings = UsageSettings().also { it.loadState(restored) }
        options.lastRequestLabel = "Unsaved"
        assertEquals("Request", settings.analyticsDisplay.lastRequestLabel)
        settings.analyticsDisplay.lastRequestLabel = "Another unsaved change"
        assertEquals("Request", settings.analyticsDisplay.lastRequestLabel)
        settings.update(emptyList(), TrackerDefinition.defaults())
        assertEquals("Request", settings.analyticsDisplay.lastRequestLabel)
        val legacy = XmlSerializer.deserialize(Element("state"), UsageSettings.SettingsState::class.java)
        assertEquals(AnalyticsDisplayOptions(), legacy.analyticsDisplay)
        assertFailsWith<IllegalArgumentException> { AnalyticsDisplayOptions(lastRequestLabel = "bad\nlabel").validate() }
        assertEquals("Last request", AnalyticsDisplayOptions(lastRequestLabel = "").normalized().lastRequestLabel)
    }

    @Test
    fun customRequestLabelAppliesToAvailableAndMissingReadingsAndOptionsAreOptIn() {
        val snapshot = snapshot().copy(
            analytics = UsageAnalytics(
                lastRequest = RequestReading(
                    refreshed, RequestUsage(RequestCounters(3_200_000, 3_200_000, null, 0, null), "test")
                ), latestActivity = refreshed.plusSeconds(1)
            )
        )
        assertFalse("Quota:" in WidgetText.text(snapshot))
        assertFalse("Reset:" in WidgetText.text(snapshot))
        val options = AnalyticsDisplayOptions(lastRequestLabel = "Req")
        assertTrue("Req: 3.2M" in WidgetText.text(snapshot, showLastRequest = true, analyticsDisplay = options))
        assertFalse("known" in WidgetText.text(snapshot, showLastRequest = true))
        assertTrue(
            "Req: unavailable" in WidgetText.text(
                snapshot.copy(analytics = UsageAnalytics()),
                showLastRequest = true,
                analyticsDisplay = options
            )
        )
    }
}
