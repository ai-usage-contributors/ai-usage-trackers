@file:Suppress("SqlResolve") // Tests create and migrate isolated SQLite databases at runtime.

package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.test.*

class CodexAnalyticsTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-03T14:00:00Z")
    private val before = "2026-11-03T13:00:00Z"
    private val after = "2026-11-03T13:30:00Z"
    private val utc = ZoneOffset.UTC
    private fun databasePath() = temp.newFolder().toPath().resolve("usage.sqlite")
    private fun log(vararg rows: String): Path =
        temp.newFile().toPath().also { Files.writeString(it, rows.joinToString("\n")) }

    private fun counts(total: Long, detailed: Boolean = true) =
        """{"total_tokens":$total,"input_tokens":${total * 9 / 10},"output_tokens":${total / 10}${if (detailed) ",\"cached_input_tokens\":${total / 2},\"reasoning_output_tokens\":${total / 20}" else ""}}"""

    private fun request(
        id: String,
        amount: Long = 100,
        at: String = before,
        detailed: Boolean = true,
        threadTotal: Long = amount
    ) =
        """{"type":"token_usage_record","timestamp":"$at","payload":{"response_id":"$id","usage":${
            counts(
                amount,
                detailed
            )
        },"thread_token_usage":${counts(threadTotal)}}}"""

    private fun report(total: Long, last: Long, at: String = before) =
        """{"type":"event_msg","timestamp":"$at","payload":{"type":"token_count","info":{"total_token_usage":${
            counts(
                total
            )
        },"last_token_usage":${counts(last)}}}}"""

    private fun quota(
        percent: String = "23.5",
        at: String = before,
        reset: String = now.plusSeconds(3600).epochSecond.toString(),
        window: Long = 10080
    ) =
        """{"type":"event_msg","timestamp":"$at","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex","secondary":{"used_percent":$percent,"window_minutes":$window,"resets_at":$reset}}}}"""

    @Test
    fun explicitRequestsPersistAndRepeatedIdentityNeverAdvancesTime() {
        val path = databasePath()
        val source = log(TestLogs.meta(), TestLogs.event(100, before), request("one"), request("one", at = after))
        UsageDatabase(path).use {
            TestLogs.import(it, source)
            assertEquals(Instant.parse(before), it.analytics(now).lastRequest?.time)
            assertEquals(100, it.analytics(now).lastRequest?.usage?.counters?.total)
            assertEquals(100, it.evaluate(TrackerDefinition(), now, utc).total)
            TestLogs.import(it, source)
            val copy = log(Files.readString(source))
            TestLogs.import(it, copy)
            assertFalse(it.analytics(now).simultaneousRequests)
        }
        Files.delete(source)
        UsageDatabase(path).use {
            assertEquals(100, it.analytics(now).lastRequest?.usage?.counters?.total)
            assertEquals(100, it.evaluate(TrackerDefinition(), now, utc).total)
        }
    }

    @Test
    fun legacyRequestsRequireAllCountersToReconcileAndIgnoreQuotaRebroadcasts() {
        UsageDatabase(databasePath()).use {
            val source = log(
                TestLogs.meta(), report(100, 100), report(200, 100, after),
                report(200, 100, "2026-11-03T13:59:00Z"), quota()
            )
            TestLogs.import(it, source)
            val reading = assertNotNull(it.analytics(now).lastRequest)
            assertEquals(Instant.parse(after), reading.time)
            assertEquals("cross-checked legacy report", reading.usage.evidence)
            assertEquals(200, it.evaluate(TrackerDefinition(), now, utc).total)
            assertEquals(Instant.parse(after), it.analytics(now).latestActivity)
            Files.writeString(source, Files.readString(source) + "\n" + report(400, 100, "2026-11-03T13:45:00Z"))
            TestLogs.import(it, source)
            assertEquals(Instant.parse(after), it.analytics(now).lastRequest?.time)
            assertTrue(it.analytics(now).latestActivity!! > reading.time)
        }
    }

    @Test
    fun firstSnapshotsResetsAndFallbackOnlyCannotInventRequests() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it,
                log(TestLogs.meta(), report(100, 100), report(50, 50, after), TestLogs.event(30, after, true))
            )
            assertNull(it.analytics(now).lastRequest)
        }
    }

    @Test
    fun explicitRecordAndMatchingLegacyUpdateAreOneRequest() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it,
                log(
                    TestLogs.meta(),
                    report(100, 100),
                    request("one", at = after, threadTotal = 200),
                    report(200, 100, after)
                )
            )
            val analytics = it.analytics(now)
            assertFalse(analytics.simultaneousRequests)
            assertEquals("response record", analytics.lastRequest?.usage?.evidence)
        }
    }

    @Test
    fun equalCountersInDifferentRequestsRemainDistinctAndZeroUsageIsAvailable() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(it, log(TestLogs.meta(), request("a"), request("b", at = after)))
            assertEquals(Instant.parse(after), it.analytics(now).lastRequest?.time)
            TestLogs.import(it, log(TestLogs.meta("other"), request("zero", 0, "2026-11-03T13:59:00Z")))
            assertEquals(0, it.analytics(now).lastRequest?.usage?.counters?.total)
        }
    }

    @Test
    fun simultaneousRequestsAreExplicitAndOptionalBreakdownsStayUnknown() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(it, log(TestLogs.meta(), request("a", detailed = false), request("b", detailed = false)))
            assertTrue(it.analytics(now).simultaneousRequests)
            assertNull(it.analytics(now).lastRequest?.usage?.counters?.cached)
            val text = describe(it.analytics(now))
            assertTrue("their order or overlap is unknown" in text)
            assertTrue("cache-read tokens (overlap follows provider contract): unavailable" in text)
        }
    }

    @Test
    fun weeklyQuotaWithoutUsagePersistsAndIsNotAssumedToBeSecondary() {
        val path = databasePath()
        UsageDatabase(path).use {
            TestLogs.import(it, log(TestLogs.meta(), quota(), quota("99", at = after, window = 300)))
            val result = it.analytics(now).quotaObservations.single { q -> q.usage.windowMinutes == 10080 }
            assertEquals(23.5, result.usage.usedPercent)
            assertEquals(0, it.eventCount())
            assertTrue(it.collection().issues.isEmpty())
        }
        UsageDatabase(path).use {
            TestLogs.import(it, log(TestLogs.meta("primary"), quota("45").replace("secondary", "primary")))
            assertEquals(3, it.analytics(now).quotaObservations.size)
            assertTrue(it.analytics(now).quotaObservations.any { q -> q.usage.window == "primary" })
        }
    }

    @Test
    fun quotaSelectionHandlesOutOfOrderFutureAndBackwardClockWithoutMergingSources() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it,
                log(TestLogs.meta(), quota("40", after), quota("20"), quota("90", "2026-11-04T00:00:00Z"))
            )
            assertEquals(40.0, it.analytics(now).quotaObservations.single().usage.usedPercent)
            assertEquals(
                20.0,
                it.analytics(Instant.parse("2026-11-03T13:15:00Z")).quotaObservations.single().usage.usedPercent
            )
            TestLogs.import(it, log(TestLogs.meta("different-account-possible"), quota("70")))
            assertEquals(2, it.analytics(now).quotaObservations.map { q -> q.source }.distinct().size)
        }
    }

    @Test
    fun quotaStalenessExpiryAndFormattingNeverInventAResetOrZero() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(it, log(TestLogs.meta(), quota("23.5")))
            assertTrue("stale; last observed values" in describe(it.analytics(now)))
            val expired = describe(it.analytics(now), now.plusSeconds(3600))
            assertTrue("expired; awaiting a new provider observation" in expired)
            assertTrue("Weekly quota: unavailable" in expired)
            val fresh = describe(it.analytics(now), Instant.parse(before).plusSeconds(5))
            assertTrue("observed within the last 15 minutes" in fresh)
            val custom = AnalyticsPresentation.describe(
                UsageSnapshot(
                    analytics = it.analytics(now),
                    evaluatedAt = now,
                    collection = CollectionSnapshot(lastCompleted = now)
                ),
                DisplayPreferences("dd/MM/yyyy", ClockFormat.TWELVE_HOUR), ZoneId.of("Europe/Budapest")
            )
            assertTrue("03/11/2026" in custom && "PM" in custom && "Europe/Budapest" in custom)
            TestLogs.import(it, log(TestLogs.meta("unknown-reset"), quota(reset = "null")))
            assertTrue(
                "Weekly reset: unknown" in AnalyticsPresentation.describe(
                    UsageSnapshot(analytics = it.analytics(now), collection = CollectionSnapshot(lastCompleted = now)),
                    DisplayPreferences(), utc,
                    AnalyticsDisplayOptions(quotaScope = QuotaPresentation.scope(it.analytics(now).quotaObservations.first { q -> q.usage.resetsAt == null })),
                )
            )
        }
    }

    @Test
    fun malformedQuotaDoesNotDiscardValidTokensAndMissingRequestUsageIsNotZero() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it,
                log(
                    TestLogs.meta(),
                    TestLogs.event(100, before),
                    quota("101"),
                    request("bad").replace("\"total_tokens\":100", "\"total_tokens\":101")
                )
            )
            assertEquals(100, it.evaluate(TrackerDefinition(), now, utc).total)
            assertTrue(it.analytics(now).quotaObservations.isEmpty())
            assertNull(it.analytics(now).lastRequest)
            assertTrue(it.collection().issues.any { issue -> "metadata" in issue })
        }
    }

    @Test
    fun conflictsAndRejectedHistoriesNeverReplaceAcceptedReadings() {
        UsageDatabase(databasePath()).use {
            val source = log(TestLogs.meta(), TestLogs.event(100, before), request("a"), quota())
            TestLogs.import(it, source)
            Files.writeString(source, Files.readString(source) + "\n" + request("a", 200))
            TestLogs.import(it, source)
            assertNull(it.analytics(now).lastRequest)
            assertTrue(it.analytics(now).issues.isNotEmpty())
            TestLogs.import(
                it,
                log(TestLogs.meta(), TestLogs.event(999, before), request("rejected", 990, after), quota("99", after))
            )
            assertEquals(23.5, it.analytics(now).quotaObservations.single().usage.usedPercent)
            assertEquals(100, it.evaluate(TrackerDefinition(), now, utc).total)
        }
    }

    @Test
    fun forkedAndAmbiguousSourcesCannotSupplyAnalytics() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it,
                log(
                    TestLogs.meta().replace("\"synthetic\"", "\"fork\",\"parent_session_id\":\"parent\""),
                    request("a"),
                    quota()
                )
            )
            assertNull(it.analytics(now).lastRequest)
            assertTrue(it.analytics(now).quotaObservations.isEmpty())
            TestLogs.import(it, log(TestLogs.event(100, before)))
            TestLogs.import(
                it,
                log(TestLogs.event(100, before), TestLogs.event(200, after), request("ambiguous", at = after), quota())
            )
            assertNull(it.analytics(now).lastRequest)
            assertTrue(it.analytics(now).quotaObservations.isEmpty())
        }
    }

    @Test
    fun cancellationRollsBackAnalyticsAlongsideLedger() {
        UsageDatabase(databasePath()).use {
            val source = log(TestLogs.meta(), request("a"), quota())
            var checks = 0
            assertFailsWith<CancellationException> {
                it.importFile(
                    source.toRealPath(),
                    Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java)
                ) {
                    if (++checks >= 6) throw CancellationException()
                }
            }
            assertNull(it.analytics(now).lastRequest)
            assertTrue(it.analytics(now).quotaObservations.isEmpty())
        }
    }

    @Test
    fun statusIsOptInAndInfoIncludesLastKnownQualifier() {
        val analytics = UsageAnalytics(
            RequestReading(
                Instant.parse(before),
                RequestUsage(RequestCounters(30_243_111, 30_243_111, null, 0, null), "response record")
            ), Instant.parse(after)
        )
        val snapshot = UsageSnapshot(loading = false, storedEvents = 1, evaluatedAt = now, analytics = analytics)
        assertFalse("request:" in WidgetText.text(snapshot))
        assertTrue("Last request: 30.2M" in WidgetText.text(snapshot, showLastRequest = true))
        val info = UsageInfoText.describe(snapshot, DisplayPreferences(), utc)
        assertTrue("Last known request: 30243111 (30.2M) tokens" in info)
        assertTrue("Weekly quota: unavailable" in info)
        val settings = UsageSettings()
        settings.update(emptyList(), TrackerDefinition.defaults(), showLastRequest = true)
        val restored = UsageSettings().also { it.loadState(settings.state) }
        assertTrue(restored.showLastRequest)
        restored.update(emptyList(), TrackerDefinition.defaults())
        assertTrue(restored.showLastRequest)
    }

    @Test
    fun cachedReaderObservesCommitsFutureBoundariesAndClockRollback() {
        val path = databasePath()
        UsageDatabase(path).use { writer ->
            UsageDatabase(path, true).use { reader ->
                assertNull(reader.readSnapshot { reader.analytics(now).lastRequest })
                TestLogs.import(
                    writer,
                    log(TestLogs.meta(), request("a"), request("future", 200, "2026-11-04T00:00:00Z"), quota())
                )
                assertEquals(100, reader.readSnapshot { reader.analytics(now).lastRequest?.usage?.counters?.total })
                assertEquals(
                    200,
                    reader.readSnapshot { reader.analytics(Instant.parse("2026-11-04T00:00:00Z")).lastRequest?.usage?.counters?.total })
                assertEquals(100, reader.readSnapshot { reader.analytics(now).lastRequest?.usage?.counters?.total })
                TestLogs.import(writer, log(TestLogs.meta("new"), request("latest", 300, after)))
                assertEquals(300, reader.readSnapshot { reader.analytics(now).lastRequest?.usage?.counters?.total })
            }
        }
    }

    @Test
    fun resetOnlyQuotaAndInvalidExtremeDatesAreHandledWithoutInventingUsage() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(
                it, log(
                    TestLogs.meta(), quota(percent = "null"),
                    quota(at = after, reset = Instant.MAX.epochSecond.toString())
                )
            )
            assertNull(it.analytics(now).quotaObservations.single().usage.usedPercent)
            val text = describe(it.analytics(now))
            assertTrue("Weekly quota: unavailable" in text)
            assertTrue("Weekly reset: 2026-11-03" in text)
            assertTrue(it.collection().issues.any { issue -> "Invalid quota" in issue })
        }
    }

    @Test
    fun aLaterLegacyRequestWithEqualCountersIsNotMistakenForAnOlderExplicitRequest() {
        UsageDatabase(databasePath()).use {
            TestLogs.import(it, log(TestLogs.meta(), report(100, 100), request("one"), report(200, 100, after)))
            assertEquals(Instant.parse(after), it.analytics(now).lastRequest?.time)
        }
    }

    @Test
    fun requestOnlyLogsWarnAboutPeriodCoverageUntilTheAggregateArrives() {
        UsageDatabase(databasePath()).use {
            val source = log(TestLogs.meta(), request("one"))
            TestLogs.import(it, source)
            assertEquals(100, it.analytics(now).lastRequest?.usage?.counters?.total)
            assertTrue(it.collection().issues.any { issue -> "period coverage incomplete" in issue })
            Files.writeString(source, Files.readString(source) + "\n" + report(100, 100))
            TestLogs.import(it, source)
            assertTrue(it.collection().issues.isEmpty())
            assertEquals(100, it.evaluate(TrackerDefinition(), now, utc).total)
        }
    }

    private fun describe(analytics: UsageAnalytics, at: Instant = now) =
        AnalyticsPresentation.describe(
            UsageSnapshot(analytics = analytics, evaluatedAt = at, collection = CollectionSnapshot(lastCompleted = at)),
            DisplayPreferences(),
            utc
        )
}
