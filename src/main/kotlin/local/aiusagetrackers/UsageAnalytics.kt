package local.aiusagetrackers

import java.time.Instant

data class RequestCounters(val total: Long, val input: Long, val cached: Long?, val output: Long, val reasoning: Long?)
data class RequestUsage(val counters: RequestCounters, val evidence: String)
data class QuotaUsage(
    val bucket: String,
    val usedPercent: Double?,
    val resetsAt: Long?,
    val window: String,
    val windowMinutes: Int = 10080,
    val freshnessUnknown: Boolean = false,
    val capturedAt: String? = null,
    val disabled: Boolean = false,
)

data class RequestReading(val time: Instant, val usage: RequestUsage)
data class QuotaObservation(
    val source: String,
    val time: Instant,
    val usage: QuotaUsage,
    val previousReset: Long? = null,
    val discontinuity: Boolean = false
)

data class UsageAnalytics(
    val lastRequest: RequestReading? = null,
    val latestActivity: Instant? = null,
    val quotaObservations: List<QuotaObservation> = emptyList(),
    val simultaneousRequests: Boolean = false,
    val issues: Set<String> = emptySet(),
    val operationalIssues: Set<String> = issues,
    val integrityIssues: Set<String> = emptySet(),
)

internal data class AnalyticsRecord(
    val key: String,
    val kind: String,
    val time: Instant,
    val request: RequestUsage? = null,
    val quota: QuotaUsage? = null,
)
