package local.aiusagetrackers

import com.google.gson.JsonParser
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhaseBQuotaTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val start = Instant.parse("2026-10-28T14:00:00Z")
    private val end = Instant.parse("2026-11-04T14:00:00Z")
    private val at = Instant.parse("2026-11-04T12:00:00Z")
    private fun quota(
        time: Instant = at,
        percent: String = "20",
        reset: Instant = end,
        primary: Boolean = false
    ): String {
        val weekly = """{"used_percent":$percent,"window_minutes":10080,"resets_at":${reset.epochSecond}}"""
        val five = """{"used_percent":40,"window_minutes":300,"resets_at":${time.plusSeconds(7200).epochSecond}}"""
        return """{"type":"event_msg","timestamp":"$time","payload":{"type":"token_count","rate_limits":{"limit_id":"codex","primary":${if (primary) weekly else five},"secondary":${if (primary) five else weekly}}}}"""
    }

    private fun reading(p: Double? = 20.0) =
        QuotaObservation("local", at, QuotaUsage("codex", p, end.epochSecond, "secondary"))

    @Test
    fun windowsAreIdentifiedByDurationAndMalformedSiblingDoesNotSuppressWeekly() {
        val issues = mutableSetOf<String>()
        val records = CodexAnalyticsParser(issues).read(JsonParser.parseString(quota()).asJsonObject)
        assertEquals(setOf(300, 10080), records.map { it.quota!!.windowMinutes }.toSet())
        val malformed = quota().replace("\"used_percent\":40", "\"used_percent\":\"invalid\"")
        val valid = CodexAnalyticsParser(issues).read(JsonParser.parseString(malformed).asJsonObject)
        assertEquals(10080, valid.single().quota!!.windowMinutes)
        assertTrue(issues.any { "Invalid quota" in it })
    }

    @Test
    fun fullWeekIncludesWeekendAndExactResetBelongsToNewWeekAfterRestart() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("week.jsonl")
        Files.writeString(
            source, listOf(
                TestLogs.meta(),
                TestLogs.event(1, start.minusNanos(1).toString()),
                TestLogs.event(11, start.toString()),
                TestLogs.event(31, "2026-10-31T12:00:00Z"),
                TestLogs.event(61, "2026-11-01T12:00:00Z"),
                TestLogs.event(101, end.minusNanos(1).toString()),
                TestLogs.event(151, end.toString()), quota()
            ).joinToString("\n")
        )
        val path = home.resolve("usage.sqlite")
        val tracker = TrackerDefinition(mode = PeriodMode.PROVIDER_WEEK, unit = SpanUnit.WEEK)
        UsageDatabase(path).use { database ->
            CodexUsageCollector(database).collect(Discovery(setOf(home), emptySet()), at)
            assertEquals(60, database.evaluate(tracker, at, ZoneOffset.UTC).total)
            assertEquals(100, database.evaluate(tracker, end.minusNanos(1), ZoneOffset.UTC).total)
            assertEquals(50, database.evaluate(tracker, end, ZoneOffset.UTC).total)
            assertTrue(database.evaluate(tracker, end, ZoneOffset.UTC).issues.any { "Scheduled" in it })
        }
        Files.delete(source)
        UsageDatabase(path).use { database ->
            assertEquals(50, database.evaluate(tracker, end.plusSeconds(10), ZoneOffset.UTC).total)
            assertNull(
                database.evaluate(
                    tracker,
                    at,
                    ZoneOffset.UTC,
                    AnalyticsDisplayOptions(quotaScope = "missing")
                ).total
            )
        }
    }

    @Test
    fun optedInDailyTotalRestartsAtWeeklyResetAndResumesCalendarBoundaries() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("day.jsonl")
        Files.writeString(
            source, listOf(
                TestLogs.meta(),
                TestLogs.event(100, end.minusNanos(1).toString()),
                TestLogs.event(150, end.toString()),
                TestLogs.event(175, end.plusSeconds(1).toString()),
                quota()
            ).joinToString("\n")
        )
        val tracker = TrackerDefinition(resetDailyAtWeeklyReset = true)
        val midnight = Instant.parse("2026-11-04T00:00:00Z")
        UsageDatabase(home.resolve("usage.sqlite")).use { database ->
            CodexUsageCollector(database).collect(Discovery(setOf(home), emptySet()), end.plusSeconds(1))
            val before = database.evaluate(tracker, end.minusNanos(1), ZoneOffset.UTC)
            assertEquals(100L, before.total)
            assertEquals(midnight, before.period.intervals.single().start)
            val exact = database.evaluate(tracker, end, ZoneOffset.UTC)
            assertEquals(50L, exact.total)
            assertEquals(end, exact.period.intervals.single().start)
            assertTrue(exact.issues.any { "scheduled weekly reset" in it })
            assertEquals(75L, database.evaluate(tracker, end.plusSeconds(1), ZoneOffset.UTC).total)
            assertEquals(150L, database.evaluate(tracker.copy(resetDailyAtWeeklyReset = false), end, ZoneOffset.UTC).total)

            val nextMidnight = midnight.plusSeconds(86400)
            val nextDay = database.evaluate(tracker, nextMidnight, ZoneOffset.UTC)
            assertEquals(0L, nextDay.total)
            assertEquals(nextMidnight, nextDay.period.intervals.single().start)
            val localMidnight = database.evaluate(tracker, end, ZoneOffset.ofHours(10))
            assertEquals(end, localMidnight.period.intervals.single().start)
            assertTrue(localMidnight.issues.isEmpty())

            val pinned = AnalyticsDisplayOptions(quotaScope = "missing")
            val fallback = database.evaluate(tracker, end, ZoneOffset.UTC, pinned)
            assertEquals(150L, fallback.total)
            assertEquals(midnight, fallback.period.intervals.single().start)
            assertTrue(fallback.issues.any { "no weekly reset" in it })
            val bound = database.evaluate(tracker.copy(quotaPoolId = "codex"), end, ZoneOffset.UTC, pinned)
            assertEquals(50L, bound.total)
            val missing = database.evaluate(tracker.copy(quotaPoolId = "missing"), end, ZoneOffset.UTC)
            assertEquals(150L, missing.total)
            assertTrue(missing.issues.any { "no weekly reset" in it })
        }
    }

    @Test
    fun resetOnDstSundayUsesAbsoluteTimeWithoutChangingWeekdaySelection() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("dst.jsonl")
        val zone = ZoneId.of("Europe/Budapest")
        val reset = Instant.parse("2026-10-25T01:30:00Z")
        Files.writeString(
            source, listOf(
                TestLogs.meta(),
                TestLogs.event(30, "2026-10-23T12:00:00Z"),
                TestLogs.event(70, "2026-10-25T00:30:00Z"),
                TestLogs.event(90, reset.toString()),
                quota(reset.minusSeconds(3600), reset = reset)
            ).joinToString("\n")
        )
        UsageDatabase(home.resolve("usage.sqlite")).use { database ->
            CodexUsageCollector(database).collect(Discovery(setOf(home), emptySet()), reset)
            val tracker = TrackerDefinition(resetDailyAtWeeklyReset = true)
            val daily = database.evaluate(tracker, reset, zone)
            assertEquals(20L, daily.total)
            assertEquals(reset, daily.period.intervals.single().start)
            val weekdays = database.evaluate(tracker.copy(selection = DaySelection.WORKING), reset, zone)
            assertEquals(30L, weekdays.total)
            assertEquals(Instant.parse("2026-10-22T22:00:00Z"), weekdays.period.intervals.single().start)
        }
    }

    @Test
    fun estimatePairsTokensAtObservationIncludesWeekendAndDoesNotUseLaterTokens() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("estimate.jsonl")
        Files.writeString(
            source, listOf(
                TestLogs.meta(),
                TestLogs.event(10_000_000, "2026-10-31T12:00:00Z"),
                TestLogs.event(70_460_000, at.toString()),
                quota(),
                TestLogs.event(90_000_000, at.plusSeconds(60).toString())
            ).joinToString("\n")
        )
        UsageDatabase(home.resolve("usage.sqlite")).use { database ->
            CodexUsageCollector(database).collect(Discovery(setOf(home), emptySet()), at.plusSeconds(120))
            val analytics = database.analytics(at.plusSeconds(120))
            val estimates = database.budgetEstimates(analytics, emptySet())
            val estimate = estimates.values.single()
            assertEquals(70_460_000, estimate.tokens)
            assertEquals(0, estimate.total!!.compareTo("352300000".toBigDecimal()))
            assertEquals(0, estimate.remaining!!.compareTo("281840000".toBigDecimal()))
            val snapshot = UsageSnapshot(
                loading = false, storedEvents = 1, evaluatedAt = at.plusSeconds(120),
                collection = database.collection(), analytics = analytics, budgetEstimates = estimates
            )
            val options = AnalyticsDisplayOptions(
                showQuota = true,
                quotaPercentMode = QuotaPercentMode.REMAINING,
                estimateBudget = true
            )
            assertTrue(WidgetText.text(snapshot, analyticsDisplay = options).contains("80%|~281.8M"))
            assertFalse(
                WidgetText.text(snapshot.copy(evaluatedAt = end), analyticsDisplay = options).contains("281.8M")
            )
        }
    }

    @Test
    fun percentageEligibilityAndObservedPriorResetControlEstimate() {
        for (p in listOf(null, 0.0, 0.999, Double.NaN, 101.0)) {
            val q = reading(p)
            assertNull(QuotaBudgetEstimate.calculate(q, 100, ResetWeekResolver.resolve(q, at)).total)
        }
        for ((p, budget) in listOf(1.0 to "10000", 20.0 to "500", 100.0 to "100")) {
            val q = reading(p)
            val estimate = QuotaBudgetEstimate.calculate(q, 100, ResetWeekResolver.resolve(q, at))
            assertEquals(0, estimate.total!!.compareTo(budget.toBigDecimal()))
        }
        assertNull(QuotaBudgetEstimate.calculate(reading(), 0, ResetWeekResolver.resolve(reading(), at)).total)
        val established = reading().copy(previousReset = start.plusSeconds(3600).epochSecond)
        val week = assertNotNull(ResetWeekResolver.resolve(established, at))
        assertFalse(week.inferred)
        assertEquals(start.plusSeconds(3600), week.interval.start)
        assertNull(ResetWeekResolver.resolve(reading().copy(usage = reading().usage.copy(resetsAt = null)), at))
    }

    @Test
    fun slotSwapKeepsWeeklyPinAndCounterDropInvalidatesEstimateUntilNewReset() {
        val home = temp.newFolder().toPath()
        val source = Files.createDirectories(home.resolve("sessions")).resolve("swap.jsonl")
        Files.writeString(
            source,
            listOf(TestLogs.meta(), TestLogs.event(100, at.toString()), quota()).joinToString("\n")
        )
        UsageDatabase(home.resolve("usage.sqlite")).use { database ->
            val collector = CodexUsageCollector(database)
            collector.collect(Discovery(setOf(home), emptySet()), at)
            val weekly = database.analytics(at).quotaObservations.single { it.usage.windowMinutes == 10080 }
            val legacyPin = "${weekly.source}/codex/secondary"
            Files.writeString(source, Files.readString(source) + "\n" + quota(at.plusSeconds(60), "10", primary = true))
            collector.collect(Discovery(setOf(home), emptySet()), at.plusSeconds(60))
            val analytics = database.analytics(at.plusSeconds(60))
            val snapshot = UsageSnapshot(
                collection = database.collection(),
                analytics = analytics,
                evaluatedAt = at.plusSeconds(60)
            )
            val selected = QuotaPresentation.select(snapshot, AnalyticsDisplayOptions(quotaScope = legacyPin)).reading!!
            assertEquals("primary", selected.usage.window)
            assertEquals(10080, selected.usage.windowMinutes)
            assertTrue(selected.discontinuity)
            assertNull(database.budgetEstimates(analytics, emptySet()).values.single().total)
            Files.writeString(
                source,
                Files.readString(source) + "\n" + quota(at.plusSeconds(120), "5", end.plusSeconds(7200), true)
            )
            collector.collect(Discovery(setOf(home), emptySet()), at.plusSeconds(120))
            assertNotNull(
                database.budgetEstimates(
                    database.analytics(at.plusSeconds(120)),
                    emptySet()
                ).values.single().total
            )
        }
    }
}
