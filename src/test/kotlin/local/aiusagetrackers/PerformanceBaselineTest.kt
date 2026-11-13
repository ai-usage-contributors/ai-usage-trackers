package local.aiusagetrackers

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.sun.management.OperatingSystemMXBean
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/** Explicit performanceBaseline task only. Generated logs, databases and JFR stay under build/. */
class PerformanceBaselineTest {
    private data class Sample(val stage: String, val wallMs: Double, val cpuMs: Double)
    private val samples = mutableListOf<Sample>()
    private val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val start = now.minusSeconds(100L * 86400)
    private val zone = ZoneOffset.UTC
    private val complete = TrackerDefinition(
        name = "All synthetic events", mode = PeriodMode.FIXED,
        fixedStart = start.atZone(zone).toLocalDateTime().toString(),
        fixedEnd = now.plusSeconds(1).atZone(zone).toLocalDateTime().toString(),
    )

    @Test
    fun recordBaseline() {
        val counts = (System.getenv("CODEX_BENCHMARK_SESSIONS") ?: "100,1000")
            .split(',').map(String::toInt).also { require(it.all { count -> count in 1..10000 }) }
        val events = parameter("CODEX_BENCHMARK_EVENTS", 1000, 10..10000)
        val repetitions = parameter("CODEX_BENCHMARK_SAMPLES", 20, 5..100)
        val imports = parameter("CODEX_BENCHMARK_IMPORTS", 3, 1..5)
        val output = Path.of("build", "phase-a", "performance")
        Files.createDirectories(output)
        for (count in counts) runWorkload(output, count, events, repetitions, imports)
    }

    private fun parameter(name: String, default: Int, range: IntRange): Int =
        (System.getenv(name)?.toInt() ?: default).also { require(it in range) }

    private fun runWorkload(output: Path, count: Int, events: Int, repetitions: Int, imports: Int) {
        samples.clear()
        val root = Files.createTempDirectory(output, "synthetic-$count-")
        val home = root.resolve("home")
        val sessions = Files.createDirectories(home.resolve("sessions"))
        repeat(count) { index ->
            Files.newBufferedWriter(sessions.resolve("session-$index.jsonl")).use { writer ->
                writer.appendLine(TestLogs.meta("benchmark-$index"))
                repeat(events) { event ->
                    writer.appendLine(TestLogs.event((event + 1L) * 10,
                        start.plusSeconds((index % 90L) * 86400 + event * 60L).toString()))
                }
            }
        }
        val logBytes = Files.list(sessions).use { files -> files.mapToLong(Files::size).sum() }
        val discovery = Discovery(setOf(home), emptySet())
        var expected = count.toLong() * events * 10
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.isValid }
        pools.forEach { it.resetPeakUsage() }
        val ioBefore = processIo()
        val recordingPath = root.resolve("profile.jfr")
        Recording().use { recording ->
            recording.enable("jdk.FileRead").withThreshold(Duration.ZERO)
            recording.enable("jdk.FileWrite").withThreshold(Duration.ZERO)
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20))
            recording.start()
            var databasePath = root.resolve("history-0.sqlite")
            repeat(imports) { trial ->
                databasePath = root.resolve("history-$trial.sqlite")
                measure("initial-import") {
                    UsageDatabase(databasePath).use { database ->
                        CodexUsageCollector(database).collect(discovery, now)
                        assertEquals(expected, database.evaluate(complete, now, zone).total)
                        assertEquals(count.toLong() * events, database.eventCount())
                    }
                }
            }
            UsageDatabase(databasePath).use { database ->
                val collector = CodexUsageCollector(database)
                repeat(repetitions) { measure("unchanged-refresh") { collector.collect(discovery, now) } }
                for (trackerCount in listOf(5, 32)) {
                    val trackers = definitions(trackerCount)
                    repeat(3) { evaluate(database, trackers) }
                    repeat(repetitions) { measure("evaluate-$trackerCount-trackers") { evaluate(database, trackers) } }
                }
                repeat(5) { append ->
                    val file = sessions.resolve("session-0.jsonl")
                    Files.writeString(file, TestLogs.event((events + append + 1L) * 10,
                        start.plusSeconds((events + append) * 60L).toString()) + "\n", StandardOpenOption.APPEND)
                    measure("active-append-refresh") { collector.collect(discovery, now) }
                    expected += 10
                    assertEquals(expected, database.evaluate(complete, now, zone).total)
                }
                val checks = AtomicInteger()
                measure("cancelled-reimport") {
                    assertFailsWith<CancellationException> {
                        database.importFile(sessions.resolve("session-0.jsonl"),
                            Files.readAttributes(sessions.resolve("session-0.jsonl"), java.nio.file.attribute.BasicFileAttributes::class.java)) {
                            if (checks.incrementAndGet() == 5) throw CancellationException("Synthetic cancellation")
                        }
                    }
                }
                assertEquals(expected, database.evaluate(complete, now, zone).total)
            }
            repeat(5) {
                measure("restart-read") {
                    UsageDatabase(databasePath).use { database ->
                        assertEquals(expected, database.evaluate(complete, now, zone).total)
                    }
                }
            }
            measureMonitor(databasePath, discovery)
            recording.stop()
            recording.dump(recordingPath)
        }
        val ioAfter = processIo()
        val summary = linkedMapOf<String, Any>(
            "sessions" to count, "eventsPerSession" to events, "sourceBytes" to logBytes,
            "expectedTokensAfterAppends" to expected,
            "os" to System.getProperty("os.name"), "java" to System.getProperty("java.version"),
            "processors" to os.availableProcessors, "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "poolPeakBytes" to pools.associate { it.name to it.peakUsage.used },
            "heapUsedAtEndBytes" to ManagementFactory.getMemoryMXBean().heapMemoryUsage.used,
            "samples" to samples.toList(),
            "stages" to samples.groupBy { it.stage }.mapValues { (_, values) ->
                val sorted = values.map { it.wallMs }.sorted()
                mapOf("count" to sorted.size, "p50Ms" to sorted[ceil(sorted.size * .50).toInt() - 1],
                    "p95Ms" to sorted[ceil(sorted.size * .95).toInt() - 1],
                    "totalCpuMs" to values.sumOf { it.cpuMs })
            },
            "processIoBefore" to ioBefore, "processIoAfter" to ioAfter,
            "jfr" to summarizeRecording(recordingPath),
            "limitations" to listOf("Filesystem caches are not flushed: fresh DB is not cold physical disk.",
                "Win32 process IO includes file/device/other transfer, not physical disk throughput.",
                "JFR file events omit native SQLite IO; use process IO counters as complementary evidence.",
                "Pool peaks are separate per-pool maxima, not simultaneous process peak RSS.",
                "Two concurrent snapshot readers exercise shared headless monitor delivery, not native IDE windows.",
                "Clock/zone jumps simulate resume evaluation; no physical sleep or EDT latency claim.",
                "Small sample counts are descriptive, not performance acceptance thresholds."),
        )
        Files.writeString(output.resolve("baseline-$count.json"), GsonBuilder().setPrettyPrinting().create().toJson(summary))
        println("Synthetic baseline: $count sessions x $events events; results in build/phase-a/performance/baseline-$count.json")
    }

    private fun definitions(count: Int) = List(count) { index ->
        TrackerDefinition(name = "Synthetic $index", amount = index + 1,
            unit = if (index % 3 == 0) SpanUnit.DAY else SpanUnit.WEEK,
            selection = if (index % 3 == 0) DaySelection.WORKING else DaySelection.ALL)
    }

    private fun evaluate(database: UsageDatabase, trackers: List<TrackerDefinition>) = database.readSnapshot {
        trackers.forEach { assertTrue(database.evaluate(it, now, zone).total != null) }
        database.collection(); database.eventCount(); database.earliest(); database.hasFuture(now); database.analytics(now)
    }

    private fun measureMonitor(path: Path, discovery: Discovery) = runBlocking {
        val time = AtomicReference(now)
        val selectedZone = AtomicReference<ZoneId>(zone)
        val clock = object : Clock() {
            override fun instant(): Instant = time.get()
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)
        }
        val job = SupervisorJob()
        try {
            val monitor = UsageMonitor(CoroutineScope(job + Dispatchers.IO), clock, selectedZone::get,
                { path }, { definitions(32) }, { discovery })
            withTimeout(120.seconds) { monitor.snapshots.first { !it.loading && it.trackers.size == 32 } }
            repeat(5) {
                val target = time.get().plusSeconds(1)
                measure("shared-monitor-two-readers") {
                    val first = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(120.seconds) { monitor.snapshots.first { it.evaluatedAt == target } }
                    }
                    val second = async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(120.seconds) { monitor.snapshots.first { it.evaluatedAt == target } }
                    }
                    time.set(target)
                    assertSame(first.await(), second.await())
                }
            }
            measure("simulated-resume-zone-change") {
                val target = now.plusSeconds(86400)
                selectedZone.set(ZoneId.of("Europe/Budapest")); time.set(target)
                withTimeout(120.seconds) {
                    monitor.snapshots.first { it.evaluatedAt == target && it.trackers.all { tracker -> tracker.period.zone == selectedZone.get() } }
                }
            }
        } finally {
            measure("monitor-disposal") { job.cancelAndJoin() }
        }
    }

    private inline fun <T> measure(stage: String, work: () -> T): T {
        val cpu = os.processCpuTime
        val started = System.nanoTime()
        return work().also {
            samples += Sample(stage, (System.nanoTime() - started) / 1e6, (os.processCpuTime - cpu) / 1e6)
        }
    }

    private fun processIo(): Map<String, String> {
        if (!System.getProperty("os.name").startsWith("Windows")) return mapOf("unavailable" to "Windows counters only")
        val pid = ProcessHandle.current().pid()
        val command = "Get-CimInstance Win32_Process -Filter 'ProcessId = $pid' | Select-Object ReadTransferCount,WriteTransferCount,ReadOperationCount,WriteOperationCount,WorkingSetSize,PeakWorkingSetSize | ConvertTo-Json -Compress"
        return try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command).redirectErrorStream(true).start()
            try {
                check(process.waitFor(10, TimeUnit.SECONDS)) { "Process counter query timed out" }
                val text = process.inputStream.bufferedReader().use { it.readText() }
                check(process.exitValue() == 0)
                JsonParser.parseString(text).asJsonObject.entrySet()
                    .filter { !it.value.isJsonNull }.associate { it.key to it.value.asString }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
            }
        } catch (_: Exception) { mapOf("unavailable" to "Process IO counters could not be queried") }
    }

    private fun summarizeRecording(path: Path): Map<String, Any> {
        var readBytes = 0L
        var writeBytes = 0L
        val topFrames = mutableMapOf<String, Int>()
        RecordingFile(path).use { recording ->
            while (recording.hasMoreEvents()) {
                val event = recording.readEvent()
                when (event.eventType.name) {
                    "jdk.FileRead" -> readBytes += event.getLong("bytesRead").coerceAtLeast(0)
                    "jdk.FileWrite" -> writeBytes += event.getLong("bytesWritten").coerceAtLeast(0)
                    "jdk.ExecutionSample" -> event.stackTrace?.frames?.firstOrNull()?.method?.let {
                        val name = "${it.type.name}.${it.name}"
                        topFrames[name] = (topFrames[name] ?: 0) + 1
                    }
                }
            }
        }
        return mapOf("javaFileReadBytes" to readBytes, "javaFileWriteBytes" to writeBytes,
            "topSampledFrames" to topFrames.entries.sortedByDescending { it.value }.take(15).associate { it.toPair() })
    }
}
