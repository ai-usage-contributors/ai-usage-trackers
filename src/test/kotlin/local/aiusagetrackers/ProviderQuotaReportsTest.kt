package local.aiusagetrackers

import com.google.gson.JsonParser
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class ProviderQuotaReportsTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private fun fixture(name: String) = javaClass.getResourceAsStream("/provider-reports/$name.json")!!.bufferedReader().use { json(it.readText()) }

    @Test fun documentedReportsKeepProviderPoolsAndContextOccupancySeparate() {
        val claude = ProviderQuotaReports.claude(fixture("claude-statusline"), now)
        assertEquals(listOf(76.5, 58.8), claude.map { it.remainingPercent })
        val pools = listOf("gemini-weekly", "3p-weekly", "gemini-5h").map {
            QuotaPool(it, QuotaBoundaryKind.UNKNOWN, null, percentage = true, resetInstant = true)
        }
        val reports = ProviderQuotaReports.antigravity(fixture("antigravity-statusline"), pools, now)
        assertEquals(listOf(25.0, 75.0, 50.0), reports.map { it.remainingPercent })
        assertTrue(reports.all { it.observedAt == null && !it.pool.fixedWeek })
    }

    @Test fun claudeOptionalWindowsAndCaptureTimeDoNotEstablishFreshness() {
        val reports = ProviderQuotaReports.claude(json("""{"rate_limits":{"seven_day":{"used_percentage":41.2,"resets_at":1795003200}}}"""), now)
        assertEquals(1, reports.size)
        assertEquals(58.8, reports.single().remainingPercent!!, 0.00001)
        assertNull(reports.single().observedAt)
        assertTrue(reports.single().pool.fixedWeek)
        assertFalse(PlanCapabilities(reports.map { it.pool }).fiveHour)
        assertFalse(QuotaEligibility.budget(PlanCapabilities(reports.map { it.pool })))
        assertTrue(ProviderQuotaReports.claude(json("""{"rate_limits":null}"""), now).isEmpty())
    }

    @Test fun antigravityGroupsDoNotAcquireWeeklySemanticsFromTheirNames() {
        val pool = QuotaPool("gemini-weekly", QuotaBoundaryKind.UNKNOWN, null, percentage = true, resetInstant = true)
        val input = json("""{"quota":{"gemini-weekly":{"remaining_fraction":0.25,"reset_time":"2026-11-18T12:00:00Z"}}}""")
        val unknown = ProviderQuotaReports.antigravity(input, listOf(pool), now).single()
        assertEquals(25.0, unknown.remainingPercent)
        assertFalse(unknown.pool.fixedWeek)
        assertNull(QuotaEligibility.interval(unknown.pool, unknown.resetsAt!!))
        val fixed = pool.copy(boundary = QuotaBoundaryKind.FIXED_DURATION, duration = Duration.ofDays(7))
        assertEquals(now, QuotaEligibility.interval(fixed, unknown.resetsAt)!!.start)
    }

    @Test fun amountOnlyDisabledAndInvalidReportsNeverBecomeFullAllowance() {
        val pool = PlanCapabilities.CODEX_WEEKLY.pools.single()
        val amount = json("""{"quota":{"weekly":{"remaining_amount":10,"reset_in_seconds":600}}}""")
        val report = ProviderQuotaReports.antigravity(amount, listOf(pool), now).single()
        assertNull(report.remainingPercent); assertNull(report.resetsAt)
        assertTrue(ProviderQuotaReports.antigravity(json("""{"quota":{"weekly":{"disabled":true}}}"""), listOf(pool), now).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            ProviderQuotaReports.claude(json("""{"rate_limits":{"seven_day":{"used_percentage":101}}}"""), now)
        }
    }

    @Test fun onlyExactFixedWeeksEnableDerivedIntervals() {
        val week = PlanCapabilities.CODEX_WEEKLY.pools.single()
        for (kind in listOf(QuotaBoundaryKind.CALENDAR, QuotaBoundaryKind.ROLLING, QuotaBoundaryKind.UNKNOWN)) {
            val plan = PlanCapabilities(listOf(week.copy(boundary = kind)), true)
            assertFalse(QuotaEligibility.window(plan, 10080)); assertFalse(QuotaEligibility.budget(plan))
        }
        assertTrue(QuotaEligibility.budget(PlanCapabilities.CODEX_WEEKLY))
        assertFalse(QuotaEligibility.window(PlanCapabilities.CODEX_WEEKLY, 300))
    }

    @Test fun dormantFiveHourSettingsDoNotRenderWarningsForWeeklyOnlyPlans() {
        val options = AnalyticsDisplayOptions(showQuota = true, showFiveHourQuota = true, showFiveHourReset = true,
            planProfile = QuotaPlanProfile.CODEX_WEEKLY)
        val sample = AnalyticsSettingsPanel.sampleSnapshot(1)
        val values = WidgetText.values(sample, options = options)
        assertTrue(values.any { it.id == "quota:10080" })
        assertTrue(values.none { it.id == "quota:300" || it.id == "reset:300" })
        assertFalse(AnalyticsPresentation.describe(sample, DisplayPreferences(), java.time.ZoneOffset.UTC, options).contains("5h quota"))
        val unknown = options.copy(planProfile = QuotaPlanProfile.UNKNOWN)
        assertTrue(WidgetText.values(sample, options = unknown).none { it.id.startsWith("quota:") })
        assertFalse(QuotaMetricPresentation.select(sample, unknown).applicable)
    }
}
