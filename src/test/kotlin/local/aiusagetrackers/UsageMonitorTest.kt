package local.aiusagetrackers

import com.intellij.util.xmlb.XmlSerializer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jdom.Element
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UsageMonitorTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val now = Instant.parse("2026-10-28T14:00:00Z")

    @Test
    fun providerWeekRollsOverWithoutNewLogsAndExpiresTheOldEstimate() = runBlocking(Dispatchers.IO) {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("weekly.jsonl")
        val end = now.plusSeconds(60)
        Files.writeString(
            source, listOf(
                TestLogs.meta(), TestLogs.event(100, now.minusSeconds(60).toString()),
                """{"type":"event_msg","timestamp":"$now","payload":{"type":"token_count","rate_limits":{"limit_id":"codex","secondary":{"used_percent":20,"window_minutes":10080,"resets_at":${end.epochSecond}}}}}"""
            ).joinToString("\n")
        )
        val clock = MutableClock(now)
        val job = SupervisorJob()
        val tracker = TrackerDefinition(unit = SpanUnit.WEEK, mode = PeriodMode.PROVIDER_WEEK)
        val monitor = UsageMonitor(
            CoroutineScope(job + Dispatchers.IO), clock, { ZoneOffset.UTC },
            { home.resolve("usage.sqlite") }, { listOf(tracker) }, { Discovery(setOf(home), emptySet()) })
        try {
            val first =
                withTimeout(60.seconds) { monitor.snapshots.first { !it.loading && it.trackers.firstOrNull()?.total == 100L } }
            assertTrue(first.budgetEstimates.values.single().total != null)
            clock.time = end
            val next =
                withTimeout(60.seconds) { monitor.snapshots.first { it.evaluatedAt == end && it.trackers.firstOrNull()?.total == 0L } }
            assertEquals(now, next.collection.lastCompleted)
            assertEquals(end, next.trackers.single().period.intervals.single().start)
            assertTrue(QuotaPresentation.select(next).expired)
            val text = WidgetText.text(
                next,
                analyticsDisplay = AnalyticsDisplayOptions(showQuota = true, estimateBudget = true)
            )
            assertTrue("estimate unavailable" in text)
            clock.time = now.minusSeconds(1)
            val rollback = withTimeout(60.seconds) { monitor.snapshots.first { it.evaluatedAt == clock.time } }
            assertEquals(null, QuotaPresentation.select(rollback).reading)
        } finally {
            job.cancel(); job.join()
        }
    }

    @Test
    fun quotaDurationAdvancesOnlyAfterCollectionEvenWhenLogsAreUnchanged() = runBlocking {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("quota.jsonl")
        val reset = now.plusSeconds(3 * 86400 + 13 * 3600 + 2 * 60).epochSecond
        Files.writeString(
            source, TestLogs.meta() + "\n" +
                    """{"type":"event_msg","timestamp":"$now","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex","secondary":{"used_percent":13,"window_minutes":10080,"resets_at":$reset}}}}"""
        )
        val clock = MutableClock(now)
        val job = SupervisorJob()
        val monitor = UsageMonitor(
            CoroutineScope(job + Dispatchers.IO), clock, { ZoneOffset.UTC },
            { home.resolve("usage.sqlite") }, { TrackerDefinition.defaults() }, { Discovery(setOf(home), emptySet()) })

        suspend fun await(predicate: (UsageSnapshot) -> Boolean) =
            withTimeout(120.seconds) { monitor.snapshots.first(predicate) }
        try {
            val first = await { it.collection.lastCompleted == now && it.analytics.quotaObservations.isNotEmpty() }
            assertEquals("3d 13h 2m", QuotaPresentation.select(first).resetDuration())
            clock.time = now.plusSeconds(120)
            val ticking = await { it.evaluatedAt == clock.time }
            assertEquals("3d 13h 2m", QuotaPresentation.select(ticking).resetDuration())
            monitor.refresh()
            val refreshed = await { it.collection.lastCompleted == clock.time }
            assertEquals("3d 13h 0m", QuotaPresentation.select(refreshed).resetDuration())
            assertEquals(13.0, refreshed.analytics.quotaObservations.single().usage.usedPercent)
        } finally {
            job.cancel(); job.join()
        }
    }

    @Test
    fun midnightRollbackZoneChangeTrackerEditsAndDisposal() = runBlocking {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("synthetic.jsonl")
        Files.writeString(source, TestLogs.meta() + "\n" + TestLogs.event(100, "2026-10-28T09:00:00Z"))
        val database = temp.newFolder().toPath().resolve("usage.sqlite")
        val clock = MutableClock(now)
        val zone = AtomicReference<ZoneId>(ZoneOffset.UTC)
        val definitions = AtomicReference(TrackerDefinition.defaults())
        val scans = AtomicInteger()
        val job = SupervisorJob()
        val monitor = UsageMonitor(
            CoroutineScope(job + Dispatchers.IO),
            clock,
            { zone.get() },
            { database },
            { definitions.get() }) {
            scans.incrementAndGet(); Discovery(setOf(home), emptySet())
        }

        suspend fun await(predicate: (UsageSnapshot) -> Boolean) =
            withTimeout(60.seconds) { monitor.snapshots.first(predicate) }
        try {
            await { !it.loading && it.trackers.firstOrNull()?.total == 100L }
            clock.time = Instant.parse("2026-10-29T00:00:00Z")
            await { it.evaluatedAt == clock.time && it.trackers[0].total == 0L && it.trackers[1].total == 100L }
            clock.time = now
            await { it.evaluatedAt == now && it.trackers[0].total == 100L }
            zone.set(ZoneId.of("Pacific/Kiritimati"))
            await { it.trackers[0].period.zone == zone.get() && it.trackers[0].total == 0L }
            definitions.set(listOf(TrackerDefinition(name = "Two days", amount = 2)))
            await { it.trackers.size == 1 && it.trackers[0].definition.name == "Two days" && it.trackers[0].total == 100L }
            Files.delete(source)
            monitor.refresh()
            await { it.collection.files == 0 && it.trackers[0].total == 100L && it.issues.isEmpty() }
            assertEquals("Codex: Two days: 100", WidgetText.text(monitor.snapshots.value))
        } finally {
            job.cancel(); job.join()
        }
        val stopped = scans.get()
        monitor.refresh()
        assertEquals(stopped, scans.get())
        UsageDatabase(database).use { assertEquals(1, it.eventCount()) }
    }

    @Test
    fun settingsCopiesAndDefaultMigration() {
        val settings = UsageSettings()
        val legacy = UsageSettings.SettingsState().apply { extraHomes.add("synthetic-home") }
        settings.loadState(legacy)
        assertEquals(listOf("today", "week"), settings.trackers.map { it.id })
        val draft = settings.trackers.first()
        draft.name = "Unsaved edit"
        assertEquals("Today", settings.trackers.first().name)
        val state = settings.state
        state.extraHomes.clear()
        assertEquals(listOf("synthetic-home"), settings.extraHomes)
        val custom = TrackerDefinition(name = "Custom", amount = 2, selection = DaySelection.WORKING)
        settings.update(settings.extraHomes, listOf(custom))
        custom.amount = 99
        assertEquals(2, settings.trackers.single().amount)
        assertEquals(1, settings.state.trackers.size)
    }

    @Test
    fun widgetDisplaysPersistedHistoryWithoutLiveHomesAndShowsExactRanges() {
        val period = UsagePeriodResolver().resolve(TrackerDefinition(amount = 2), now, ZoneOffset.UTC)
        val result = TrackerResult(TrackerDefinition(name = "Two days", amount = 2), period, 1_230_000)
        val snapshot = UsageSnapshot(listOf(result), loading = false, storedEvents = 1, evaluatedAt = now)
        assertEquals("Codex: Two days: 1.2M", WidgetText.text(snapshot))
        val info = UsageInfoText.describe(snapshot, DisplayPreferences(), ZoneOffset.UTC)
        assertTrue("1230000 (1.2M) tokens" in info)
        assertTrue("2026-10-27 00:00:00" in info)
        assertTrue("2026-10-28 14:00:00" in info)
        assertTrue("retained" in DisclaimersText.describe(listOf(result.definition), ZoneOffset.UTC))
        assertFalse(WidgetText.text(snapshot).contains("⚠"))
        assertTrue(WidgetText.tooltip(snapshot.copy(operationalIssues = setOf("Stale collection"))).contains("warnings"))
        assertEquals("Codex: loading", WidgetText.text(UsageSnapshot()))
        assertEquals("Codex: unavailable", WidgetText.text(UsageSnapshot(loading = false)))
    }

    @Test
    fun settingsXmlRoundTripAndExistingXmlKeepsDefaultTrackers() {
        val original = UsageSettings.SettingsState().apply {
            extraHomes.add("synthetic-home")
            display = DisplayPreferences("dd/MM/yyyy", ClockFormat.TWELVE_HOUR)
            trackers = mutableListOf(
                TrackerDefinition(
                    name = "Previous workdays", amount = 3,
                    selection = DaySelection.WORKING, includeCurrent = false, zoneId = "Europe/Budapest",
                    startTime = "09:30", endTime = "17:45", visible = false, showPeriodStart = true
                )
            )
        }
        val xml = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(xml, UsageSettings.SettingsState::class.java)
        assertEquals(original.extraHomes, restored.extraHomes)
        assertEquals(original.trackers, restored.trackers)
        assertEquals(original.display, restored.display)
        val legacy = XmlSerializer.deserialize(Element("state"), UsageSettings.SettingsState::class.java)
        assertEquals(listOf("today", "week"), legacy.trackers.map { it.id })
        assertEquals(DisplayPreferences(), legacy.display)
        assertFalse(legacy.trackers.any { it.showPeriodStart })
    }

    private class MutableClock(@Volatile var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
    }
}
