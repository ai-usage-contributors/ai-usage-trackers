package local.aiusagetrackers

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProviderMonitorTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun bothConfiguredAccountsCollectWhileDisplaySelectsOnlyOne() = runBlocking(Dispatchers.IO) {
        val now = Instant.parse("2026-11-11T12:00:00Z")
        val config = ProviderConfiguration()
        val profiles = listOf("a", "b").mapIndexed { index, id ->
            val directory = temp.newFolder().toPath()
            Files.writeString(directory.resolve("synthetic.jsonl"), """{"type":"assistant","timestamp":"$now","message":{"id":"same-id","usage":{"input_tokens":100,"output_tokens":${index + 1}}}}""")
            ProviderProfile(id = id, vendor = UsageVendor.CLAUDE, verified = true, locations = directory.toString())
        }
        config.profiles.addAll(profiles)
        config.vendors.first { it.vendor == UsageVendor.CLAUDE }.also { it.tracking = true; it.accountId = "a" }
        assertEquals(UsageVendor.CODEX, config.display.selected)
        assertTrue(config.collecting().containsAll(profiles))
        val job = SupervisorJob()
        try {
            val monitors = profiles.map { profile ->
                UsageMonitor(CoroutineScope(job + Dispatchers.IO), Clock.fixed(now, ZoneOffset.UTC), { ZoneOffset.UTC },
                    { temp.root.toPath().resolve("${profile.id}.sqlite") }, { listOf(TrackerDefinition(accountId = profile.id)) },
                    { Discovery(emptySet(), emptySet()) }, { profile.analytics },
                    customCollect = { database, at, completedAt, cancelled -> ProviderUsageCollector(database).collect(profile, at, cancelled, completedAt) }, releaseDriverOnClose = false)
            }
            val snapshots = monitors.map { monitor -> withTimeout(30.seconds) { monitor.snapshots.first { !it.loading && it.storedEvents == 1L } } }
            assertEquals(listOf(101L, 102L), snapshots.map { it.trackers.single().total })
            assertTrue(snapshots.all { it.budgetEstimates.isEmpty() })
            config.display.selected = UsageVendor.CLAUDE
            assertTrue(config.collecting().containsAll(profiles))
            config.vendors.first { it.vendor == UsageVendor.CLAUDE }.tracking = false
            assertFalse(config.collecting().any { it.vendor == UsageVendor.CLAUDE })
        } finally { job.cancelAndJoin() }
    }
}
