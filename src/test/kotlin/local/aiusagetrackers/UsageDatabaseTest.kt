package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.test.*

internal object TestLogs {
    fun meta(id: String = "synthetic") = """{"type":"session_meta","payload":{"id":"$id"}}"""
    fun event(total: Long, time: String = "2026-10-28T10:00:00Z", fallback: Boolean = false) =
        """{"type":"event_msg","timestamp":"$time","payload":{"type":"token_count","info":{"${if (fallback) "last_token_usage" else "total_token_usage"}":{"total_tokens":$total,"input_tokens":$total,"output_tokens":0}}}}"""

    fun import(database: UsageDatabase, path: Path) =
        database.importFile(path.toRealPath(), Files.readAttributes(path, BasicFileAttributes::class.java))
}

class UsageDatabaseTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val now = Instant.parse("2026-10-28T14:00:00Z")
    private val utc = ZoneOffset.UTC
    private fun path() = temp.newFolder().toPath().resolve("usage.sqlite")
    private fun log(text: String) =
        temp.newFile("source-${System.nanoTime()}.jsonl").toPath().also { Files.writeString(it, text) }

    private fun total(database: UsageDatabase, tracker: TrackerDefinition = TrackerDefinition()): Long? =
        database.evaluate(tracker, now, utc).total

    @Test
    fun historySurvivesRestartAndSourceDeletion() {
        val path = path()
        val source = log(TestLogs.meta() + "\n" + TestLogs.event(100))
        UsageDatabase(path).use { database -> TestLogs.import(database, source); assertEquals(100, total(database)) }
        Files.delete(source)
        UsageDatabase(path).use { database ->
            CodexUsageCollector(database).collect(Discovery(emptySet(), emptySet()), now)
            assertEquals(100, total(database))
            assertEquals(1, database.eventCount())
            assertEquals(0, database.collection().homes)
        }
    }

    @Test
    fun copiesArchiveMovesAndAppendAreIdempotent() {
        UsageDatabase(path()).use { database ->
            val source = log(TestLogs.meta() + "\n" + TestLogs.event(100))
            TestLogs.import(database, source); TestLogs.import(database, source)
            val copy = source.resolveSibling("archive.jsonl")
            Files.copy(source, copy); TestLogs.import(database, copy)
            assertEquals(100, total(database))
            Files.delete(source)
            Files.writeString(
                copy,
                TestLogs.meta() + "\n" + TestLogs.event(100) + "\n" + TestLogs.event(150, "2026-10-28T11:00:00Z")
            )
            TestLogs.import(database, copy)
            assertEquals(150, total(database)); assertEquals(2, database.eventCount())
            TestLogs.import(database, copy); assertEquals(150, total(database))
            assertTrue(
                database.unchanged(
                    copy.toRealPath(),
                    Files.readAttributes(copy, BasicFileAttributes::class.java)
                )
            )
        }
    }

    @Test
    fun truncatedAndConflictingHistoriesNeverReplaceAcceptedUsage() {
        UsageDatabase(path()).use { database ->
            val first = TestLogs.meta() + "\n" + TestLogs.event(100)
            val source = log(first + "\n" + TestLogs.event(150, "2026-10-28T11:00:00Z"))
            TestLogs.import(database, source)
            Files.writeString(source, first); TestLogs.import(database, source)
            assertEquals(150, total(database)); assertTrue(database.collection().issues.any { "Truncated" in it })
            Files.writeString(source, TestLogs.meta() + "\n" + TestLogs.event(999)); TestLogs.import(database, source)
            assertEquals(150, total(database)); assertTrue(database.collection().issues.any { "Conflicting" in it })
            Files.writeString(source, first + "\n" + TestLogs.event(150, "2026-10-28T11:00:00Z")); TestLogs.import(
            database,
            source
        )
            assertTrue(database.collection().issues.isEmpty())
        }
    }

    @Test
    fun partialWritesRetryWithoutDuplicatingAndCallbacksPropagate() {
        UsageDatabase(path()).use { database ->
            val first = TestLogs.meta() + "\n" + TestLogs.event(100)
            val next = TestLogs.event(150, "2026-10-28T11:00:00Z")
            val source = log(first + "\n" + next.take(40))
            TestLogs.import(database, source)
            assertEquals(100, total(database)); assertTrue(database.collection().issues.isNotEmpty())
            assertFalse(
                database.unchanged(
                    source.toRealPath(),
                    Files.readAttributes(source, BasicFileAttributes::class.java)
                )
            )
            Files.writeString(source, first + "\n" + next); TestLogs.import(database, source)
            assertEquals(150, total(database)); assertTrue(database.collection().issues.isEmpty())
            assertFailsWith<IllegalStateException> { CodexSessionParser().stream(source) { error("Callback failure") } }
        }
    }

    @Test
    fun cancellationRollsBackEventsAndSourceFingerprint() {
        val path = path()
        val source = log(TestLogs.meta() + "\n" + (1..300).joinToString("\n") { TestLogs.event(it.toLong()) })
        UsageDatabase(path).use { database ->
            var checks = 0
            assertFailsWith<CancellationException> {
                database.importFile(
                    source,
                    Files.readAttributes(source, BasicFileAttributes::class.java)
                ) { if (++checks == 270) throw CancellationException() }
            }
            assertEquals(0, database.eventCount())
            assertFalse(database.unchanged(source, Files.readAttributes(source, BasicFileAttributes::class.java)))
        }
        UsageDatabase(path).use { database -> TestLogs.import(database, source); assertEquals(300, total(database)) }
    }

    @Test
    fun changedWhileReadingRollsBackImport() {
        UsageDatabase(path()).use { database ->
            val source = log(TestLogs.meta() + "\n" + TestLogs.event(100))
            val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
            Files.writeString(source, TestLogs.meta() + "\n" + TestLogs.event(1000))
            assertFailsWith<IllegalStateException> { database.importFile(source, attrs) }
            assertEquals(0, database.eventCount())
        }
    }

    @Test
    fun separateReaderRemainsAvailableDuringUncommittedImport() {
        val location = path()
        UsageDatabase(location).use { writer ->
            UsageDatabase(location, true).use { reader ->
                val source = log(TestLogs.meta() + "\n" + TestLogs.event(100))
                var checked = false
                writer.importFile(source, Files.readAttributes(source, BasicFileAttributes::class.java)) {
                    assertEquals(0, reader.eventCount()); checked = true
                }
                assertTrue(checked); assertEquals(100, total(reader))
            }
        }
    }

    @Test
    fun timestampsRetainNanosecondsAndRangesAreHalfOpen() {
        UsageDatabase(path()).use { database ->
            val source = log(
                TestLogs.meta() + "\n" + listOf(
                    TestLogs.event(10, "2026-10-28T10:00:00.000000001Z"),
                    TestLogs.event(20, "2026-10-28T10:00:00.000000002Z"),
                    TestLogs.event(30, "2026-10-28T10:00:00.000000003Z")
                ).joinToString("\n")
            )
            TestLogs.import(database, source)
            assertEquals(
                10,
                database.total(
                    listOf(
                        UsageInterval(
                            Instant.parse("2026-10-28T10:00:00.000000002Z"),
                            Instant.parse("2026-10-28T10:00:00.000000003Z")
                        )
                    ), now
                )
            )
            assertEquals(
                20,
                database.total(
                    listOf(
                        UsageInterval(
                            Instant.parse("2026-10-28T10:00:00Z"),
                            Instant.parse("2026-10-28T10:00:01Z")
                        )
                    ), Instant.parse("2026-10-28T10:00:00.000000002Z")
                )
            )
        }
    }

    @Test
    fun selectedDaysAndWeeklyTotalsUseStoredHistory() {
        UsageDatabase(path()).use { database ->
            val source = log(
                TestLogs.meta() + "\n" + listOf(
                    TestLogs.event(10, "2026-10-23T10:00:00Z"), TestLogs.event(20, "2026-10-24T10:00:00Z"),
                    TestLogs.event(30, "2026-10-26T10:00:00Z"), TestLogs.event(40, "2026-10-28T10:00:00Z")
                ).joinToString("\n")
            )
            TestLogs.import(database, source)
            assertEquals(20, total(database, TrackerDefinition(unit = SpanUnit.WEEK)))
            assertEquals(10, total(database, TrackerDefinition(amount = 2)))
            assertEquals(20, total(database, TrackerDefinition(amount = 2, selection = DaySelection.ACTIVE)))
            val monday = Instant.parse("2026-10-26T14:00:00Z")
            assertEquals(
                20,
                database.evaluate(TrackerDefinition(amount = 2, selection = DaySelection.WORKING), monday, utc).total
            )
            assertEquals(
                20,
                database.evaluate(
                    TrackerDefinition(
                        amount = 2,
                        selection = DaySelection.ACTIVE,
                        includeCurrent = false
                    ), now, utc
                ).total
            )
            assertTrue(
                database.evaluate(
                    TrackerDefinition(amount = 10, selection = DaySelection.ACTIVE),
                    now,
                    utc
                ).issues.isNotEmpty()
            )
        }
    }

    @Test
    fun activeDaysRespectConfiguredWindowAndFutureExclusion() {
        UsageDatabase(path()).use { database ->
            val source = log(
                TestLogs.meta() + "\n" + listOf(
                    TestLogs.event(10, "2026-10-26T10:00:00Z"), TestLogs.event(20, "2026-10-27T20:00:00Z"),
                    TestLogs.event(30, "2026-10-28T13:00:00Z"), TestLogs.event(40, "2026-10-29T13:00:00Z")
                ).joinToString("\n")
            )
            TestLogs.import(database, source)
            val definition =
                TrackerDefinition(amount = 2, selection = DaySelection.ACTIVE, startTime = "09:30", endTime = "17:45")
            val result = database.evaluate(definition, now, utc)
            assertEquals(20, result.total)
            assertEquals(
                listOf("2026-10-26", "2026-10-28"),
                result.period.intervals.map { it.start.atZone(utc).toLocalDate().toString() })
            assertTrue(database.hasFuture(now))
        }
    }

    @Test
    fun zoneChangesRequireNoLogReimport() {
        UsageDatabase(path()).use { database ->
            val source = log(TestLogs.meta() + "\n" + TestLogs.event(100, "2026-10-27T23:30:00Z"))
            TestLogs.import(database, source); Files.delete(source)
            assertEquals(0, total(database))
            assertEquals(100, database.evaluate(TrackerDefinition(), now, ZoneId.of("Europe/Budapest")).total)
        }
    }

    @Test
    fun overflowIsUnavailableAndNeverConvertedToFloatingPoint() {
        UsageDatabase(path()).use { database ->
            TestLogs.import(database, log(TestLogs.meta("one") + "\n" + TestLogs.event(Long.MAX_VALUE)))
            TestLogs.import(database, log(TestLogs.meta("two") + "\n" + TestLogs.event(1)))
            val result = database.evaluate(TrackerDefinition(), now, utc)
            assertNull(result.total); assertTrue(result.issues.any { "integer range" in it })
        }
    }

    @Test
    fun schemaCreationIsReopenableAndNewerSchemaIsPreserved() {
        val location = path()
        UsageDatabase(location).use { assertEquals(0, it.eventCount()) }
        JDBC().connect("jdbc:sqlite:$location", Properties()).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=999") }
        }
        val before = Files.readAllBytes(location)
        assertFailsWith<IllegalArgumentException> { UsageDatabase(location).close() }
        assertTrue(before.contentEquals(Files.readAllBytes(location)))
    }

    @Test
    fun corruptedDatabaseIsPreserved() {
        val location = path()
        Files.writeString(location, "synthetic invalid database")
        val before = Files.readAllBytes(location)
        assertFailsWith<java.sql.SQLException> { UsageDatabase(location).close() }
        assertTrue(before.contentEquals(Files.readAllBytes(location)))
    }

    @Test
    fun readOnlyPreviewCannotCreateMissingDatabaseOrReadUnsupportedSchema() {
        val location = path()
        assertFailsWith<java.sql.SQLException> { UsageDatabase(location, true).close() }
        assertFalse(Files.exists(location))
        UsageDatabase(location).close()
        JDBC().connect("jdbc:sqlite:$location", Properties()).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version=999") }
        }
        assertFailsWith<IllegalArgumentException> { UsageDatabase(location, true).close() }
    }

    @Test
    fun identityChangesAndForksAreExcluded() {
        UsageDatabase(path()).use { database ->
            val source = log(TestLogs.meta() + "\n" + TestLogs.event(100))
            TestLogs.import(database, source)
            Files.writeString(
                source,
                TestLogs.meta("different") + "\n" + TestLogs.event(200)
            ); TestLogs.import(database, source)
            assertEquals(
                100,
                total(database)
            ); assertTrue(database.collection().issues.any { "identity changed" in it })
            val fork = log(
                TestLogs.meta("fork").replace(
                    "\"id\":\"fork\"",
                    "\"id\":\"fork\",\"forked_from_id\":\"synthetic\""
                ) + "\n" + TestLogs.event(999)
            )
            TestLogs.import(database, fork); assertEquals(100, total(database))
        }
    }

    @Test
    fun completedScansAndProblemFilesPersistAndClearOnRepair() {
        val location = path()
        val file = temp.newFile("rollout-2026-10-23-malformed.jsonl").toPath()
        Files.writeString(file, TestLogs.meta() + "\n" + TestLogs.event(100) + "\n{broken")
        UsageDatabase(location).use { database ->
            TestLogs.import(database, file)
            database.recordScan(1, 1, emptySet(), now)
            assertEquals(now, database.collection().lastCompleted)
            assertNull(database.collection().lastSuccess)
            assertEquals(java.time.LocalDate.parse("2026-10-23"), database.collection().sourceProblems.single().date)
        }
        UsageDatabase(location).use { database ->
            assertEquals(now, database.collection().lastCompleted)
            assertEquals(file.fileName.toString(), database.collection().sourceProblems.single().fileName)
            Files.writeString(file, TestLogs.meta() + "\n" + TestLogs.event(100))
            TestLogs.import(database, file)
            database.recordScan(1, 1, emptySet(), now.plusSeconds(30))
            assertTrue(database.collection().sourceProblems.isEmpty())
            assertEquals(now.plusSeconds(30), database.collection().lastSuccess)
            database.recordScan(1, 1, setOf("Import failed"), now.plusSeconds(60))
            assertEquals(now.plusSeconds(60), database.collection().lastCompleted)
            assertEquals(now.plusSeconds(30), database.collection().lastSuccess)
            assertEquals(100, total(database))
        }
    }

    @Test
    fun ambiguousCopiesAreListedIndividuallyEvenAfterSourceRemoval() {
        val location = path()
        val original = log(TestLogs.event(100))
        val copy = temp.newFile("rollout-2026-10-11-copy.jsonl").toPath()
        Files.writeString(copy, TestLogs.event(100) + "\n" + TestLogs.event(200, "2026-10-28T11:00:00Z"))
        UsageDatabase(location).use { database ->
            TestLogs.import(database, original); TestLogs.import(database, copy)
            val problem = database.collection().sourceProblems.single()
            assertEquals(copy.fileName.toString(), problem.fileName)
            assertTrue(problem.issues.any { "ambiguous" in it })
            assertEquals(java.time.LocalDate.parse("2026-10-11"), problem.date)
            Files.delete(copy)
        }
        UsageDatabase(location).use { database ->
            assertEquals(copy.fileName.toString(), database.collection().sourceProblems.single().fileName)
            assertEquals(100, total(database))
        }
    }

    @Test
    fun scannerRetainsHistoryOnMissingSourcesAndReportsFailures() {
        UsageDatabase(path()).use { database ->
            val home = temp.newFolder().toPath()
            val source = Files.createDirectories(home.resolve("sessions/old-date")).resolve("source.jsonl")
            Files.writeString(source, TestLogs.meta() + "\n" + TestLogs.event(100))
            val collector = CodexUsageCollector(database)
            collector.collect(Discovery(setOf(home), emptySet()), now)
            Files.delete(source)
            collector.collect(Discovery(emptySet(), setOf("Cannot read home")), now)
            assertEquals(100, total(database)); assertTrue(database.collection().issues.isNotEmpty())
            assertEquals(now, database.collection().lastSuccess)
        }
    }

    @Test
    fun completedTimestampUsesCompletionClockAndCancellationDoesNotAdvanceIt() {
        UsageDatabase(path()).use { database ->
            val collector = CodexUsageCollector(database)
            val completed = now.plusSeconds(120)
            collector.collect(Discovery(emptySet(), emptySet()), now, completionTime = { completed })
            assertEquals(completed, database.collection().lastCompleted)
            assertFailsWith<CancellationException> {
                collector.collect(
                    Discovery(emptySet(), emptySet()),
                    completed.plusSeconds(30)
                ) { throw CancellationException() }
            }
            assertEquals(completed, database.collection().lastCompleted)
        }
    }

    @Test
    fun missingIdentityCopiesAreConservativeAndNeverMultiplyUsage() {
        UsageDatabase(path()).use { database ->
            val source = log(TestLogs.event(100))
            TestLogs.import(database, source)
            val exact = log(TestLogs.event(100))
            TestLogs.import(database, exact)
            assertEquals(100, total(database))
            val ambiguous = log(TestLogs.event(100) + "\n" + TestLogs.event(200, "2026-10-28T11:00:00Z"))
            TestLogs.import(database, ambiguous)
            assertEquals(100, total(database))
            assertTrue(database.collection().issues.any { "identity missing" in it })
            repeat(3) {
                TestLogs.import(database, ambiguous)
                assertEquals(100, total(database))
                assertTrue(database.collection().issues.any { "identity missing" in it })
            }
            Files.writeString(source, TestLogs.event(100) + "\n" + TestLogs.event(200, "2026-10-28T11:00:00Z"))
            TestLogs.import(database, source)
            assertEquals(200, total(database))
        }
    }

    @Test
    fun provisionalIdentitySurvivesRestartAndCannotTrustAnotherProvisionalCopy() {
        val location = path()
        val original = log(TestLogs.event(100))
        val ambiguous = log(TestLogs.event(100) + "\n" + TestLogs.event(200, "2026-10-28T11:00:00Z"))
        UsageDatabase(location).use { database ->
            TestLogs.import(database, original); TestLogs.import(
            database,
            ambiguous
        )
        }
        UsageDatabase(location).use { database ->
            repeat(2) { TestLogs.import(database, ambiguous) }
            val another = log(Files.readString(ambiguous))
            TestLogs.import(database, another); TestLogs.import(database, another)
            assertEquals(100, total(database))
            assertTrue(database.collection().issues.any { "ambiguous" in it })
            // Changing the first event does not make a previously rejected association trustworthy.
            Files.writeString(ambiguous, TestLogs.event(999))
            TestLogs.import(database, ambiguous)
            assertEquals(100, total(database))
        }
    }

    @Test
    fun provisionalIdentityCanBeResolvedByExactAcceptedContent() {
        UsageDatabase(path()).use { database ->
            val original = log(TestLogs.event(100))
            val ambiguous = log(TestLogs.event(100) + "\n" + TestLogs.event(200, "2026-10-28T11:00:00Z"))
            TestLogs.import(database, original); TestLogs.import(database, ambiguous)
            Files.writeString(ambiguous, Files.readString(original))
            TestLogs.import(database, ambiguous)
            assertEquals(100, total(database))
            assertTrue(database.collection().issues.isEmpty())
            Files.writeString(ambiguous, TestLogs.event(100) + "\n" + TestLogs.event(150, "2026-10-28T11:00:00Z"))
            TestLogs.import(database, ambiguous)
            assertEquals(150, total(database))
        }
    }

    @Test
    fun olderSchemasAreRejectedAndHistoryIsPreserved() {
        for (version in 1..3) {
            val location = temp.newFolder().toPath().resolve("usage.sqlite")
            UsageDatabase(location).use { TestLogs.import(it, log(TestLogs.meta() + "\n" + TestLogs.event(100))) }
            JDBC().connect("jdbc:sqlite:$location", Properties()).use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version=$version") }
            }
            assertFailsWith<IllegalArgumentException> { UsageDatabase(location).close() }
            JDBC().connect("jdbc:sqlite:$location", Properties()).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeQuery("PRAGMA user_version")
                        .use { row -> row.next(); assertEquals(version, row.getInt(1)) }
                    sql.executeQuery("SELECT sum(total) FROM events")
                        .use { row -> row.next(); assertEquals(100, row.getLong(1)) }
                }
            }
        }
    }

    @Test
    fun collapsedDstWindowCountsZeroAndIsExcludedFromActiveDays() {
        UsageDatabase(path()).use { database ->
            val source = log(
                TestLogs.meta() + "\n" + TestLogs.event(100, "2026-03-28T01:45:00Z") + "\n" + TestLogs.event(
                    200,
                    "2026-03-29T01:10:00Z"
                )
            )
            TestLogs.import(database, source)
            val tracker = TrackerDefinition(startTime = "02:30", endTime = "03:15", zoneId = "Europe/Budapest")
            val at = Instant.parse("2026-03-29T12:00:00Z")
            val result = database.evaluate(tracker, at, utc)
            assertEquals(0, result.total)
            assertTrue(result.issues.any { "collapses" in it })
            assertTrue(PeriodPresentation.describe(result, at).contains("no usage counted"))
            val active = database.evaluate(tracker.copy(selection = DaySelection.ACTIVE), at, utc)
            assertEquals(100, active.total)
            assertEquals(
                "2026-03-28",
                active.period.intervals.single().start.atZone(ZoneId.of(tracker.zoneId)).toLocalDate().toString()
            )
        }
    }

    @Test
    fun streamingHistoryHasNoFormerDailyEventCap() {
        val source = temp.newFile().toPath()
        Files.newBufferedWriter(source).use { writer ->
            writer.appendLine(TestLogs.meta())
            repeat(100_001) { writer.appendLine(TestLogs.event(it.toLong() + 1)) }
        }
        var count = 0
        var total = 0L
        val summary = CodexSessionParser().stream(source) { count++; total += it.total }
        assertEquals(100_001, count)
        assertEquals(100_001, total)
        assertTrue(summary.issues.isEmpty())
    }

    @Test
    fun historicalImportMatchesUpstreamFixture() {
        UsageDatabase(path()).use { database ->
            val source = log(checkNotNull(javaClass.getResource("/normal-session.jsonl")).readText())
            TestLogs.import(database, source)
            val utcDay = Instant.parse("2026-10-30T12:00:00Z")
            assertEquals(500, database.evaluate(TrackerDefinition(), utcDay, utc).total)
            assertEquals(1500, database.evaluate(TrackerDefinition(unit = SpanUnit.WEEK), utcDay, utc).total)
        }
    }
}
