package local.aiusagetrackers

import com.google.gson.JsonObject
import java.time.Instant

/** Documented stream-json DONE steps wrapped by the capture helper with arrival time. */
class AntigravityUsageSource : UsageSource {
    override val vendor = UsageVendor.ANTIGRAVITY
    override fun parse(row: JsonObject, capturedAt: Instant, profile: ProviderProfile): ProviderBatch {
        require(row.text("format") == "usage-capture-v1") { "Antigravity requires sanitized capture envelopes; raw native transcripts are unverified" }
        require(row.text("vendor") == "antigravity")
        val report = row.getAsJsonObject("report") ?: return ProviderBatch()
        if (report.text("event") == "step_update") {
            val step = report.getAsJsonObject("step_update") ?: return ProviderBatch()
            if (step.text("state") != "DONE") return ProviderBatch()
            val usage = step.getAsJsonObject("usage") ?: return ProviderBatch()
            require(capturedAt != Instant.EPOCH) { "Captured step needs an arrival timestamp" }
            val input = usage.count("input_tokens"); val output = usage.count("output_tokens")
            val total = usage.count("total_tokens")
            require(Math.addExact(input, output) == total)
            val cached = usage.count("cache_read_tokens", 0); val thinking = usage.count("thinking_tokens", 0)
            // Cache categories can be outside input in some stream versions; preserve
            // the provider total rather than adding the cache or reasoning categories.
            val conversation = stableIdentity(step.text("conversation_id") ?: error("Missing conversation ID"))
            return ProviderBatch(tokens = listOf(ProviderTokenEvent("step:$conversation:${step.count("step_index")}", capturedAt,
                RequestCounters(total, input, cached, output, thinking), "captured step completion")))
        }
        val pools = report.getAsJsonObject("quota")?.keySet()?.map { id ->
            QuotaPool(stableIdentity(id), QuotaBoundaryKind.UNKNOWN, null, percentage = true, resetInstant = true)
        }.orEmpty()
        return ProviderBatch(quotas = ProviderQuotaReports.antigravity(report, pools, capturedAt, includeDisabled = true))
    }
}
