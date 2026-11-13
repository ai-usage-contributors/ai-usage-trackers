package local.aiusagetrackers

import com.google.gson.JsonObject
import java.time.Instant

/** Native Claude assistant messages and the optional sanitized status-line envelope. */
class ClaudeUsageSource : UsageSource {
    override val vendor = UsageVendor.CLAUDE
    override fun parse(row: JsonObject, capturedAt: Instant, profile: ProviderProfile): ProviderBatch {
        if (row.text("format") == "usage-capture-v1") {
            require(row.text("vendor") == "claude")
            return ProviderBatch(quotas = ProviderQuotaReports.claude(row.getAsJsonObject("report"), capturedAt))
        }
        if (row.text("type") != "assistant") return ProviderBatch()
        val message = row.getAsJsonObject("message") ?: return ProviderBatch()
        val usage = message.getAsJsonObject("usage") ?: return ProviderBatch()
        val input = Math.addExact(usage.count("input_tokens"), Math.addExact(usage.count("cache_read_input_tokens", 0), usage.count("cache_creation_input_tokens", 0)))
        val output = usage.count("output_tokens")
        val id = stableIdentity(message.text("id") ?: error("Assistant message has no ID"))
        val time = Instant.parse(row.text("timestamp") ?: error("Assistant usage has no timestamp"))
        return ProviderBatch(tokens = listOf(ProviderTokenEvent("message:$id", time,
            RequestCounters(Math.addExact(input, output), input, usage.count("cache_read_input_tokens", 0), output, null))))
    }
}
