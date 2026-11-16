package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Fixtures are also compared byte-for-byte with the Python generator's output. */
class SyntheticUsageGeneratorTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-13T12:00:00Z")

    @Test
    fun generatedFilesImportWithoutDoubleCountingAndRespectTimeBoundaries() {
        val examples = listOf(
            Triple(UsageVendor.CODEX, 12000L, 2000L),
            Triple(UsageVendor.CLAUDE, 6000L, 1000L),
            Triple(UsageVendor.ANTIGRAVITY, 2400L, 400L)
        )
        for ((vendor, latestTotal, cached) in examples) {
            val name = vendor.name.lowercase()
            val relative = if (vendor == UsageVendor.CODEX) "$name/sessions/rollout-demo.jsonl" else "$name/demo.jsonl"
            val file = Path.of(assertNotNull(javaClass.getResource("/synthetic-usage/$relative")).toURI())
            val profile = ProviderProfile(id = "demo-$name", vendor = vendor, verified = true)
            UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { database ->
                database.bindAccount(profile)
                repeat(2) {
                    val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
                    if (vendor == UsageVendor.CODEX) database.importFile(file, attrs)
                    else database.importProvider(file, attrs, profile)
                }
                val midnight = now.truncatedTo(ChronoUnit.DAYS)
                fun total(start: Instant, end: Instant) = database.total(listOf(UsageInterval(start, end)), now)
                assertEquals(latestTotal, total(now.minusSeconds(3600), now.plusSeconds(1)), name)
                assertEquals(latestTotal * 3, total(midnight, midnight.plusSeconds(86400)), name)
                assertEquals(latestTotal * 6, total(now.minusSeconds(7 * 86400), now.plusSeconds(1)), name)
                // The end is exclusive, even when it coincides with the newest request.
                assertEquals(0L, total(now.minusSeconds(3600), now.minusSeconds(300)), name)
                val analytics = database.analytics(now)
                val request = assertNotNull(analytics.lastRequest, name)
                assertEquals(latestTotal, request.usage.counters.total, name)
                assertEquals(cached, request.usage.counters.cached, name)
                assertEquals(now.minusSeconds(300), request.time, name)
                val expectedPercentages = when (vendor) {
                    UsageVendor.CODEX -> setOf(42.0, 18.0)
                    UsageVendor.CLAUDE -> setOf(65.0, 27.0)
                    UsageVendor.ANTIGRAVITY -> setOf(35.0)
                }
                assertEquals(expectedPercentages, analytics.quotaObservations.map { it.usage.usedPercent }.toSet(), name)
            }
        }
    }
}
