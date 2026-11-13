package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class UsageTest {
    @Test
    fun upstreamComparisonFixture() {
        val fixture = checkNotNull(javaClass.getResource("/normal-session.jsonl")).readText()
        assertEquals(500, sum(parse(fixture)))
    }

    @get:Rule
    val temp = TemporaryFolder()
    private val utc = ZoneOffset.UTC
    private val day = DayWindow(LocalDate.of(2026, 10, 30), utc)
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun counters(total: Long): String =
        """{"total_tokens":$total,"input_tokens":${total - total / 10},"cached_input_tokens":${total / 2},"output_tokens":${total / 10},"reasoning_output_tokens":${total / 20}}"""

    private fun event(total: Long, time: String = "2026-10-30T10:00:00Z", field: String = "total_token_usage"): String =
        """{"type":"event_msg","timestamp":"$time","payload":{"type":"token_count","info":{"$field":${counters(total)}}}}"""

    private fun meta(id: String = "session", extra: String = ""): String =
        """{"type":"session_meta","payload":{"id":"$id"$extra}}"""

    private fun file(text: String): Path = temp.newFile().toPath().also { Files.writeString(it, text) }
    private fun parse(text: String, window: DayWindow = day): ParsedSession =
        CodexSessionParser().parse(file(text), window)

    private fun sum(session: ParsedSession): Long = DailyUsageCalculator(Clock.fixed(now, utc)) { utc }
        .calculate(CollectionSnapshot(listOf(session))).total

    @Test
    fun cumulativeRepeatsAndSubsets() {
        val parsed = parse(listOf(event(1000), event(1000), event(1500)).joinToString("\n"))
        assertEquals(listOf(1000L, 500L), parsed.increments.map { it.total })
        assertEquals(1500, sum(parsed))
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun midnightBaselineAndRestart() {
        val text = event(1000, "2026-10-29T23:59:59Z") + "\n" + event(1100, "2026-10-30T00:00:00Z")
        assertEquals(100, sum(parse(text)))
        assertEquals(100, sum(parse(text)))
    }

    @Test
    fun emptyAndFallback() {
        assertEquals(0, sum(parse("")))
        assertEquals(
            30,
            sum(parse(event(10, field = "last_token_usage") + "\n" + event(20, field = "last_token_usage")))
        )
    }

    @Test
    fun nullCumulativeUsesFallbackAndMissingTimeIsFlagged() {
        val fallback =
            event(50, field = "last_token_usage").replace("\"info\":{", "\"info\":{\"total_token_usage\":null,")
        val parsed = parse(fallback)
        assertEquals(50, sum(parsed))
        assertTrue(parsed.issues.isEmpty())
        assertTrue(parse(event(50).replace("\"timestamp\":\"2026-10-30T10:00:00Z\",", "")).issues.isNotEmpty())
    }

    @Test
    fun streamingCancellationPropagates() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            CodexSessionParser().parse(file(event(100)), day) { throw java.util.concurrent.CancellationException() }
        }
    }

    @Test
    fun lineTerminatorsAndEmptyLinesPreserveFinalRecord() {
        for (ending in listOf("", "\n", "\r\n")) {
            val parsed = parse("\n\r\n" + event(100) + ending)
            assertEquals(listOf(100L), parsed.increments.map { it.total })
            assertTrue(parsed.issues.isEmpty())
        }
    }

    @Test
    fun lineLimitAcceptsExactBoundaryAndDrainsOversizedRecords() {
        val record = event(100)
        val exact = record + " ".repeat(4 * 1024 * 1024 - record.length)
        for (ending in listOf("", "\n")) {
            val accepted = parse(exact + ending)
            assertEquals(listOf(100L), accepted.increments.map { it.total })
            assertTrue(accepted.issues.isEmpty())
            val rejected = parse("$exact $ending")
            assertTrue(rejected.increments.isEmpty())
            assertTrue(rejected.issues.isNotEmpty())
        }
        val resumed = parse(exact + " \n" + event(150))
        assertEquals(listOf(150L), resumed.increments.map { it.total })
        assertTrue(resumed.issues.isNotEmpty())
    }

    @Test
    fun resetAndMixedSequencesAreIncomplete() {
        val parsed =
            parse(listOf(event(100), event(10), event(20, field = "last_token_usage"), event(200)).joinToString("\n"))
        assertEquals(120, sum(parsed))
        assertTrue(parsed.issues.any { "reset" in it })
        val resetWithLast = event(10).replace("\"info\":{", "\"info\":{\"last_token_usage\":${counters(5)},")
        assertEquals(105, sum(parse(event(100) + "\n" + resetWithLast)))
    }

    @Test
    fun malformedTrailingWriteRetried() {
        val complete = event(150)
        val path = file(event(100) + "\n" + complete.take(50))
        val parser = CodexSessionParser()
        assertTrue(parser.parse(path, day).issues.isNotEmpty())
        Files.writeString(path, event(100) + "\n" + complete)
        val parsed = parser.parse(path, day)
        assertEquals(150, sum(parsed))
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun invalidCountsTimesAndOverflow() {
        for (bad in listOf("-1", "1.5", "9223372036854775808", "\"100\"")) {
            assertTrue(parse(event(100).replace("\"total_tokens\":100", "\"total_tokens\":$bad")).issues.isNotEmpty())
        }
        assertEquals(5_000_000_000, sum(parse(event(5_000_000_000))))
        assertTrue(
            parse(
                event(100).replace(
                    "\"cached_input_tokens\":50",
                    "\"cached_input_tokens\":500"
                )
            ).issues.isNotEmpty()
        )
        val invalidTime = parse(event(100, "invalid") + "\n" + event(150))
        assertEquals(50, sum(invalidTime))
        assertTrue(invalidTime.issues.isNotEmpty())
    }

    @Test
    fun forkHistoryExcluded() {
        val parsed = parse(meta(extra = ",\"forked_from_id\":\"parent\"") + "\n" + event(100))
        assertEquals(0, sum(parsed))
        assertTrue(parsed.issues.isNotEmpty())
    }

    @Test
    fun exactBoundariesAndFutureRecords() {
        val parsed = parse(
            listOf(
                event(10, "2026-10-30T00:00:00Z"), event(20, "2026-10-30T13:00:00Z"),
                event(30, "2026-10-31T00:00:00Z")
            ).joinToString("\n")
        )
        val snapshot = DailyUsageCalculator(Clock.fixed(now, utc)) { utc }.calculate(CollectionSnapshot(listOf(parsed)))
        assertEquals(10, snapshot.total)
        assertTrue(snapshot.issues.any { "Future" in it })
        assertEquals(2, parsed.increments.size)
    }

    @Test
    fun dstAndLocalDate() {
        val zone = ZoneId.of("Europe/Budapest")
        val spring = DayWindow(LocalDate.of(2026, 3, 29), zone)
        val autumn = DayWindow(LocalDate.of(2026, 10, 25), zone)
        assertEquals(23, Duration.between(spring.start, spring.end).toHours())
        assertEquals(25, Duration.between(autumn.start, autumn.end).toHours())
        val clock = Clock.fixed(Instant.parse("2026-10-29T23:30:00Z"), utc)
        assertEquals(day.date, DayWindow.current(clock, zone).date)
    }

    @Test
    fun clockJumpsWakeZoneChangeAndMidnightWithoutNewLogs() {
        val clock = MutableClock(Instant.parse("2026-10-30T23:59:59Z"))
        var zone: ZoneId = utc
        val calculator = DailyUsageCalculator(clock) { zone }
        val collection = CollectionSnapshot(listOf(parse(event(100, "2026-10-30T09:00:00Z"))))
        assertEquals(100, calculator.calculate(collection).total)
        clock.time = Instant.parse("2026-10-31T00:00:00Z")
        assertEquals(0, calculator.calculate(collection).total)
        clock.time = now
        assertEquals(100, calculator.calculate(collection).total)
        zone = ZoneId.of("Pacific/Kiritimati")
        assertEquals(0, calculator.calculate(collection).total)
        clock.time = now.plusSeconds(3 * 86400)
        assertEquals(0, calculator.calculate(collection).total)
    }

    @Test
    fun discoveryCanonicalizesHomesAndFindsJetBrainsAndEnvironment() {
        val home = temp.newFolder().toPath()
        val standard = Files.createDirectory(home.resolve(".codex"))
        val ide = Files.createDirectories(home.resolve("AppData/Local/JetBrains/Idea/aia/codex"))
        val discovered = CodexHomeDiscovery(home, mapOf("CODEX_HOME" to standard.toString()), "Windows")
            .discover(listOf(standard.resolve(".").toString()))
        assertEquals(setOf(standard.toRealPath(), ide.toRealPath()), discovered.homes)
        assertTrue(discovered.issues.isEmpty())
    }

    @Test
    fun overflowIsDisclosed() {
        val session =
            ParsedSession(null, "synthetic", listOf(Increment(now, Long.MAX_VALUE), Increment(now, 1)), emptySet(), now)
        val snapshot =
            DailyUsageCalculator(Clock.fixed(now, utc)) { utc }.calculate(CollectionSnapshot(listOf(session)))
        assertTrue(snapshot.issues.any { "integer range" in it })
    }

    private class MutableClock(@Volatile var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
    }
}
