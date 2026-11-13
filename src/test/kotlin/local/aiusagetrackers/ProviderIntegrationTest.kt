package local.aiusagetrackers

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class ProviderIntegrationTest {
    @get:Rule
    val temp = TemporaryFolder()
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private fun claude(id: String = "request-1", output: Int = 20, time: Instant = now) =
        """{"type":"assistant","timestamp":"$time","sessionId":"synthetic-session","message":{"id":"$id","usage":{"input_tokens":100,"cache_read_input_tokens":40,"cache_creation_input_tokens":10,"output_tokens":$output}}}"""

    private fun capture(vendor: String, report: String, at: Instant = now) =
        """{"format":"usage-capture-v1","vendor":"$vendor","captured_at":"$at","report":$report}"""

    private fun quota(used: Int = 20) =
        """{"rate_limits":{"seven_day":{"used_percentage":$used,"resets_at":${now.plusSeconds(86400).epochSecond}}},"context_window":{"used_percentage":99}}"""

    private fun profile(id: String = "claude-a") = ProviderProfile(
        id = id,
        vendor = UsageVendor.CLAUDE,
        verified = true,
        locations = temp.root.absolutePath,
        analytics = AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CLAUDE_WEEKLY)
    )

    private fun import(db: UsageDatabase, text: String, p: ProviderProfile) {
        val file = temp.newFile().toPath()
        Files.writeString(file, text)
        db.importProvider(file, Files.readAttributes(file, BasicFileAttributes::class.java), p)
    }

    @Test
    fun nativeClaudeCountsCacheOnceAndFinalRevisionOnceAcrossCopiedSubagents() {
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            val p = profile(); db.bindAccount(p)
            import(db, claude(output = 2) + "\n" + claude(output = 20), p)
            assertEquals(170L, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
            import(db, claude(), p)
            assertEquals(170L, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
            import(db, claude(id = "subagent-request"), p)
            assertEquals(340L, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
            assertEquals(2, db.eventCount())
            assertEquals(150, db.analytics(now).lastRequest!!.usage.counters.input)
        }
    }

    @Test
    fun revisedUsageMovesToFinalTimestampAndConflictingReportsRollbackTheirCursor() {
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            val p = profile()
            import(db, claude(output = 2, time = now.minusSeconds(3600)), p)
            import(db, claude(output = 20), p)
            assertEquals(170, db.total(listOf(UsageInterval(now, now.plusSeconds(1))), now))
            assertFailsWith<IllegalArgumentException> { import(db, claude(output = 1), p) }
            assertEquals(170, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
        }
    }

    @Test
    fun accountLedgersRejectRebindingAndSameRequestIdsRemainIndependent() {
        for ((id, output) in listOf("a" to 20, "b" to 50)) {
            UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
                val p = profile(id); db.bindAccount(p); import(db, claude(output = output), p)
                assertEquals(150L + output, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
                assertFailsWith<IllegalArgumentException> { db.bindAccount(profile("different")) }
                assertFailsWith<IllegalArgumentException> {
                    import(
                        db,
                        claude("different-request"),
                        profile("different")
                    )
                }
                assertEquals(150L + output, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
            }
        }
    }

    @Test
    fun repeatedQuotaDoesNotRenewAgeAndChangedValuesUseOnePoolScope() {
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            val p = profile()
            import(db, capture("claude", quota()), p)
            import(db, capture("claude", quota(), now.plusSeconds(120)), p)
            var reading = db.analytics(now.plusSeconds(120)).quotaObservations.single()
            assertEquals(now, reading.time)
            assertTrue(reading.usage.freshnessUnknown)
            assertEquals(20.0, reading.usage.usedPercent)
            import(db, capture("claude", quota(30), now.plusSeconds(180)), p)
            reading = db.analytics(now.plusSeconds(180)).quotaObservations.single()
            assertEquals(30.0, reading.usage.usedPercent)
            assertEquals(now.plusSeconds(180), reading.time)
            val selection = QuotaSelection(reading, now.plusSeconds(180), false, evaluatedAt = now.plusSeconds(180))
            assertTrue(selection.stale)
            assertContains(selection.warning(false)!!, "unknown")
            assertNull(QuotaSelection(reading, now.plusSeconds(86400), false).percentage(QuotaPercentMode.REMAINING))
        }
    }

    @Test
    fun antigravityUsesDoneStepsAndNeverAddsCumulativeResultOrContextUsage() {
        val p = profile().copy(
            vendor = UsageVendor.ANTIGRAVITY,
            analytics = AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.ANTIGRAVITY_REPORTED)
        )
        val usage =
            """{"input_tokens":100,"output_tokens":20,"cache_read_tokens":80,"thinking_tokens":10,"total_tokens":120}"""
        val active = capture(
            "antigravity",
            """{"event":"step_update","step_update":{"conversation_id":"conversation-1","step_index":3,"state":"ACTIVE","usage":$usage}}"""
        )
        val done = active.replace("ACTIVE", "DONE")
        val result = capture(
            "antigravity",
            """{"event":"result","result":{"conversation_id":"conversation-1","usage":$usage}}"""
        )
        val quota = capture(
            "antigravity",
            """{"quota":{"gemini-weekly":{"remaining_fraction":0.75,"reset_time":"2026-11-12T12:00:00Z"}},"context_window":{"total_input_tokens":999999}}"""
        )
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            import(db, listOf(active, done, result, quota).joinToString("\n"), p)
            assertEquals(120, db.evaluate(TrackerDefinition(), now, ZoneOffset.UTC).total)
            assertEquals(
                "captured step completion",
                db.analytics(now).lastRequest!!.usage.evidence.substringAfter("; ").substringBefore(";")
            )
            val pool = db.analytics(now).quotaObservations.single()
            assertEquals(0, pool.usage.windowMinutes)
            assertEquals(25.0, pool.usage.usedPercent)
            assertFalse(p.analytics.capabilities().weekly)
            val snapshot = UsageSnapshot(
                loading = false,
                evaluatedAt = now,
                collection = CollectionSnapshot(lastCompleted = now),
                analytics = db.analytics(now)
            )
            assertContains(
                WidgetText.values(snapshot, options = p.analytics.copy(showReportedPools = true)).first().text, "25%"
            )
            import(
                db,
                capture("antigravity", """{"quota":{"gemini-weekly":{"disabled":true}}}""", now.plusSeconds(60)),
                p
            )
            val disabled = db.analytics(now.plusSeconds(60)).quotaObservations.single()
            assertTrue(disabled.usage.disabled)
            assertNull(QuotaSelection(disabled, now.plusSeconds(60), false, 0).percentage(QuotaPercentMode.REMAINING))
        }
    }

    @Test
    fun malformedSourceAndCancellationDoNotCommitAnyPartialFacts() {
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            assertFails { import(db, claude() + "\n{", profile()) }
            assertEquals(0, db.eventCount())
            val file = temp.newFile().toPath(); Files.writeString(file, claude() + "\n" + claude("request-2"))
            assertFailsWith<java.util.concurrent.CancellationException> {
                db.importProvider(file, Files.readAttributes(file, BasicFileAttributes::class.java), profile()) {
                    if (db.eventCount() > 0) throw java.util.concurrent.CancellationException()
                }
            }
            assertFalse(db.providerUnchanged(file, Files.readAttributes(file, BasicFileAttributes::class.java)))
            assertEquals(0, db.eventCount())
        }
    }

    @Test
    fun portableBindingsRequireExplicitMappingAndExcludeLocalIdentifiers() {
        val config = TrackerConfiguration(
            listOf(TrackerDefinition(accountId = "private-local-id")),
            bindings = listOf(PortableAccountBinding("private-local-id", UsageVendor.CLAUDE, "Personal")),
            general = PortableVendorDisplay(
                mapOf(UsageVendor.CLAUDE to "My plan"),
                VendorDisplayState(selected = UsageVendor.CLAUDE)
            )
        )
        val text = TrackerConfigurationJson.encode(config)
        assertFalse(text.contains("private-local-id"))
        assertFalse(text.contains("locations"))
        val decoded = TrackerConfigurationJson.decode(text)
        assertEquals("unresolved_0", decoded.trackers.single().accountId)
        assertEquals("new-local-id", decoded.remap(mapOf("unresolved_0" to "new-local-id")).trackers.single().accountId)
        assertEquals("My plan", decoded.general!!.labels[UsageVendor.CLAUDE])
        assertEquals(
            ProviderProfile.LEGACY_CODEX,
            TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(TrackerDefinition.defaults()))).trackers.first().accountId
        )
    }

    @Test
    fun collectionDisplayAndMembershipAreIndependentAndSettingsAreDeepCopied() {
        val config = ProviderConfiguration()
        config.profiles.first { it.vendor == UsageVendor.CLAUDE }
            .also { it.verified = true; it.locations = temp.root.absolutePath }
        config.vendors.first { it.vendor == UsageVendor.CLAUDE }.tracking = true
        assertEquals(setOf(UsageVendor.CODEX, UsageVendor.CLAUDE), config.collecting().map { it.vendor }.toSet())
        config.display.selected = UsageVendor.CLAUDE
        assertEquals(2, config.collecting().size)
        config.display = config.display.enable(true, config.eligible())
        config.display = config.display.toggle(UsageVendor.CODEX, config.eligible())
        assertEquals(listOf(UsageVendor.CLAUDE), config.display.visible(config.eligible()))
        assertEquals(2, config.collecting().size)
        assertFailsWith<IllegalArgumentException> { config.display.toggle(UsageVendor.CLAUDE, config.eligible()) }
        config.vendors.first { it.vendor == UsageVendor.CLAUDE }.tracking = false
        assertEquals(listOf(UsageVendor.CODEX), config.collecting().map { it.vendor })
        assertEquals(UsageVendor.CODEX, config.reconciled().display.selected)
        val copy = config.isolatedCopy(); copy.profiles.first().analytics.showQuota = true
        assertFalse(config.profiles.first().analytics.showQuota)
        val roundtrip = XmlSerializer.deserialize(XmlSerializer.serialize(config), ProviderConfiguration::class.java)
        assertEquals(config, roundtrip)
    }

    @Test
    fun carouselDwellSurvivesRefreshAndPausesWithoutChangingSingleSelection() {
        var time = 0L
        val motion = VendorCarouselMotion { time }
        val state = VendorDisplayState(carousel = true, verticalSeconds = 8)
        val vendors = listOf(UsageVendor.CODEX, UsageVendor.CLAUDE)
        assertEquals(UsageVendor.CODEX, motion.advance(vendors, state, false))
        time = 7_000_000_000L; assertEquals(UsageVendor.CODEX, motion.advance(vendors, state, false))
        time = 8_000_000_000L; assertEquals(UsageVendor.CLAUDE, motion.advance(vendors, state, false))
        assertEquals(UsageVendor.CODEX, state.selected)
        time = 16_000_000_000L; assertEquals(UsageVendor.CLAUDE, motion.advance(vendors, state, true))
        time = 23_000_000_000L; assertEquals(UsageVendor.CLAUDE, motion.advance(vendors, state, false))
        time = 24_000_000_000L; assertEquals(UsageVendor.CODEX, motion.advance(vendors, state, false))
        state.verticalSeconds = 0; time = 100_000_000_000L
        assertEquals(UsageVendor.CODEX, motion.advance(vendors, state, false))
        assertEquals(UsageVendor.CLAUDE, motion.advance(vendors, state, false, 1))
    }

    @Test
    fun percentageResetAndBudgetCapabilitiesAreIndependent() {
        val plan = PlanCapabilities(
            listOf(
                QuotaPool(
                    "reset-only",
                    QuotaBoundaryKind.FIXED_DURATION,
                    java.time.Duration.ofDays(7),
                    percentage = false,
                    resetInstant = true
                )
            )
        )
        assertTrue(QuotaEligibility.metric(plan, 10080, reset = true).enabled)
        assertFalse(QuotaEligibility.metric(plan, 10080, reset = false).enabled)
        assertFalse(QuotaEligibility.budget(plan))
        val dormant = TrackerDefinition(
            mode = PeriodMode.PROVIDER_WEEK,
            unit = SpanUnit.WEEK,
            showPercentUsed = true,
            colorByQuotaGoal = true
        )
        val value = StatusValue("tracker", "Week", "100", java.math.BigDecimal(100))
        assertEquals(
            value,
            QuotaMetricPresentation(null, "Unavailable", applicable = false).tracker(
                value,
                dormant,
                DisplayPreferences(weeklyPacing = true)
            )
        )
    }

    @Test
    fun explicitPoolControlsTheWeekAndSurvivesPortableExportWithoutFallback() {
        val file = temp.newFile().toPath()
        fun quotaRow(pool: String, days: Long) =
            """{"type":"event_msg","timestamp":"$now","payload":{"type":"token_count","rate_limits":{"limit_id":"$pool","secondary":{"used_percent":20,"window_minutes":10080,"resets_at":${
                now.plusSeconds(days * 86400).epochSecond
            }}}}}"""
        Files.writeString(
            file, listOf(
                TestLogs.meta(), TestLogs.event(100, now.minusSeconds(5 * 86400).toString()),
                TestLogs.event(110, now.toString()), quotaRow("pool-a", 1), quotaRow("pool-b", 3)
            ).joinToString("\n")
        )
        UsageDatabase(temp.newFolder().toPath().resolve("usage.sqlite")).use { db ->
            TestLogs.import(db, file)
            db.recordScan(1, 1, emptySet(), now)
            val a = TrackerDefinition(unit = SpanUnit.WEEK, mode = PeriodMode.PROVIDER_WEEK, quotaPoolId = "pool-a")
            val b = a.copy(quotaPoolId = "pool-b")
            val pinB =
                QuotaPresentation.scope(db.analytics(now).quotaObservations.first { it.usage.bucket == "pool-b" })
            assertEquals(110, db.evaluate(a, now, ZoneOffset.UTC, AnalyticsDisplayOptions(quotaScope = pinB)).total)
            assertEquals(10, db.evaluate(b, now, ZoneOffset.UTC).total)
            assertNull(db.evaluate(a.copy(quotaPoolId = "missing-pool"), now, ZoneOffset.UTC).total)
            val exported =
                TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(listOf(a, b))))
            assertEquals(listOf("pool-a", "pool-b"), exported.trackers.map { it.quotaPoolId })
        }
    }
}
