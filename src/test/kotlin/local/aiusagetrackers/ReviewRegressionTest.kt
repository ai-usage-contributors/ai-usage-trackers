package local.aiusagetrackers

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class ReviewRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-13T12:00:00Z")
    private fun profile() = ProviderProfile(id = "review", vendor = UsageVendor.CLAUDE, verified = true,
        locations = temp.root.absolutePath, analytics = AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CLAUDE_WEEKLY))
    private fun import(db: UsageDatabase, text: String, profile: ProviderProfile) {
        val file = temp.newFile().toPath()
        Files.writeString(file, text)
        db.importProvider(file, Files.readAttributes(file, BasicFileAttributes::class.java), profile)
    }

    @Test fun quotaReturnsToEarlierValuesAndDisabledPoolsCanRecoverWithoutRenewingCachedAge() {
        val p = profile().copy(vendor = UsageVendor.ANTIGRAVITY)
        fun quota(at: Instant, value: String) = """{"format":"usage-capture-v1","vendor":"antigravity","captured_at":"$at","report":{"quota":{"pool":$value}}}"""
        val available = """{"remaining_fraction":0.7,"reset_time":"2026-11-14T12:00:00Z"}"""
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            // Import out of order as directory traversal need not follow acquisition time.
            import(db, quota(now.plusSeconds(120), available), p)
            import(db, quota(now, available), p)
            assertEquals(now, db.analytics(now.plusSeconds(120)).quotaObservations.single().time)
            import(db, quota(now.plusSeconds(180), """{"disabled":true}"""), p)
            assertTrue(db.analytics(now.plusSeconds(180)).quotaObservations.single().usage.disabled)
            import(db, quota(now.plusSeconds(240), available), p)
            import(db, quota(now.plusSeconds(300), available), p)
            val recovered = db.analytics(now.plusSeconds(300)).quotaObservations.single()
            assertFalse(recovered.usage.disabled)
            assertEquals(30.0, recovered.usage.usedPercent!!, 0.00001)
            assertEquals(now.plusSeconds(240), recovered.time)
            assertTrue(recovered.usage.freshnessUnknown)
        }
    }

    @Test fun providerFilesBeyond32MiBAreImportedAndFinalRequestRevisionsAreStagedOnce() {
        val source = temp.newFile().toPath()
        Files.newBufferedWriter(source).use { writer ->
            val padding = " ".repeat(32768) + "\n"
            repeat(1025) { writer.write(padding) }
            for (output in listOf(2, 20)) writer.write("""{"type":"assistant","timestamp":"$now","message":{"id":"request","usage":{"input_tokens":100,"output_tokens":$output}}}""" + "\n")
        }
        assertTrue(Files.size(source) > 32L * 1024 * 1024)
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            db.importProvider(source, Files.readAttributes(source, BasicFileAttributes::class.java), profile())
            assertEquals(1, db.eventCount())
            assertEquals(120, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
        }
    }

    @Test fun collectionMetadataUsesTheCompletionClock() {
        val source = temp.newFile("usage.jsonl").toPath()
        Files.writeString(source, """{"type":"assistant","timestamp":"$now","message":{"id":"request","usage":{"input_tokens":100,"output_tokens":20}}}""")
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            ProviderUsageCollector(db).collect(profile().copy(locations = source.toString()), now,
                completionTime = { now.plusSeconds(90) })
            assertEquals(now.plusSeconds(90), db.collection().lastCompleted)
        }
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            CodexUsageCollector(db).collect(Discovery(emptySet(), emptySet()), now, { now.plusSeconds(90) })
            assertEquals(now.plusSeconds(90), db.collection().lastCompleted)
        }
    }

    @Test fun monitorSuppliesItsClockAfterCustomCollectionFinishes() = runBlocking(Dispatchers.IO) {
        val clock = MutableClock(now)
        val job = SupervisorJob()
        try {
            val monitor = UsageMonitor(CoroutineScope(job + Dispatchers.IO), clock, { ZoneOffset.UTC },
                { temp.root.toPath().resolve("monitor.sqlite") }, { TrackerDefinition.defaults() },
                { Discovery(emptySet(), emptySet()) }, { AnalyticsDisplayOptions() },
                customCollect = { db, startedAt, completedAt, cancelled ->
                    clock.time = startedAt.plusSeconds(90)
                    CodexUsageCollector(db).collect(Discovery(emptySet(), emptySet()), startedAt, completedAt, cancelled)
                }, releaseDriverOnClose = false)
            val result = withTimeout(30.seconds) { monitor.snapshots.first { it.collection.lastCompleted != null } }
            assertEquals(now.plusSeconds(90), result.collection.lastCompleted)
        } finally { job.cancelAndJoin() }
    }

    @Test fun removingAccountsProtectsBindingsAndDefaultAccounts() {
        val config = ProviderConfiguration()
        val extra = profile()
        config.profiles.add(extra)
        config.vendors.first { it.vendor == UsageVendor.CLAUDE }.accountId = extra.id
        assertFailsWith<IllegalArgumentException> { config.removeAccount(extra.id, true) }
        assertFailsWith<IllegalArgumentException> { config.removeAccount(ProviderProfile.LEGACY_CODEX, false) }
        config.removeAccount(extra.id, false)
        config.validate()
        assertEquals("claude", config.account(UsageVendor.CLAUDE)?.id)
        assertFailsWith<IllegalArgumentException> { config.removeAccount("claude", false) }
    }

    @Test fun importedMarkupIsLiteralInAccountChoicesAndMetricTooltips() = SwingUtilities.invokeAndWait {
        val markup = "<html><b>External label</b></html>"
        val imported = TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(listOf(TrackerDefinition(name = markup)))))
        val button = RibbonValueButton(StatusValue("sample", imported.trackers.single().name, "100"), Font("Dialog", Font.PLAIN, 12), Color.BLACK, false) {}
        val tooltip = button.createToolTip().apply { tipText = button.toolTipText }
        assertNull(tooltip.getClientProperty("html"))
        val combo = JComboBox<AccountChoice>()
        updateSettingsChoices(combo, listOf(AccountChoice("a", markup)), AccountChoice("a", markup))
        val rendered = combo.renderer.getListCellRendererComponent(JList(), combo.selectedItem as AccountChoice, 0, false, false)
        assertNull((rendered as javax.swing.JComponent).getClientProperty("html"))
        val config = ProviderConfiguration()
        config.vendors.first().label = markup
        assertFalse(config.menuLabel(UsageVendor.CODEX).startsWith("<html>", ignoreCase = true))
        assertContains(config.menuLabel(UsageVendor.CODEX), markup)
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.encode(TrackerConfiguration(TrackerDefinition.defaults(),
                general = PortableVendorDisplay(mapOf(UsageVendor.CODEX to markup))))
        }
        val panel = ProviderSettingsPanel({}, {})
        panel.reset(config)
        val boxes = controls(panel).filterIsInstance<javax.swing.JCheckBox>().filter { markup in it.text }
        assertTrue(boxes.isNotEmpty())
        assertTrue(boxes.all { it.getClientProperty("html") == null })
    }

    @Test fun accountEditorPreventsAddingBeyondTheSupportedLimit() = SwingUtilities.invokeAndWait {
        val config = ProviderConfiguration()
        repeat(29) { config.profiles.add(ProviderProfile(id = "extra-$it")) }
        val panel = ProviderSettingsPanel({}, {})
        panel.reset(config)
        val add = controls(panel.advancedPanel).filterIsInstance<javax.swing.JButton>().single { it.text == "Add account" }
        assertFalse(add.isEnabled)
    }

    private fun controls(root: java.awt.Container): List<java.awt.Component> = root.components.toList() + root.components.filterIsInstance<java.awt.Container>().flatMap(::controls)

    private class MutableClock(@Volatile var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(time, zone)
    }
}
