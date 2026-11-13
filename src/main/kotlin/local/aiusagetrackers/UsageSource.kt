package local.aiusagetrackers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

data class ProviderTokenEvent(
    val key: String,
    val time: Instant,
    val counters: RequestCounters,
    val precision: String = "request"
)

data class ProviderBatch(
    val tokens: List<ProviderTokenEvent> = emptyList(),
    val quotas: List<ProviderQuotaReport> = emptyList()
)

/** Adapters return sanitized facts only. Storage and scheduling belong to the core. */
interface UsageSource {
    val vendor: UsageVendor
    fun parse(row: JsonObject, capturedAt: Instant, profile: ProviderProfile): ProviderBatch
}

object UsageSourceRegistry {
    private val sources = listOf(ClaudeUsageSource(), AntigravityUsageSource()).associateBy { it.vendor }
    fun source(vendor: UsageVendor): UsageSource = sources.getValue(vendor)
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_LINE_CHARS = 1024 * 1024
    fun read(file: Path, profile: ProviderProfile, checkCancelled: () -> Unit = {}): ProviderBatch {
        require(Files.size(file) <= MAX_FILE_BYTES) { "Report exceeds 32 MiB; split or rotate it" }
        val tokens = linkedMapOf<String, ProviderTokenEvent>()
        val quotas = mutableListOf<ProviderQuotaReport>()
        // A bounded snapshot is re-read only when source metadata changes. Facts and that
        // completed snapshot cursor commit together; partial trailing records are retried.
        Files.newBufferedReader(file).use { reader ->
            var line = StringBuilder()
            while (true) {
                checkCancelled()
                val c = reader.read()
                if (c == -1) {
                    consumeNonBlank(line, profile, tokens, quotas)
                    break
                }
                if (c == '\n'.code) {
                    consumeNonBlank(line, profile, tokens, quotas); line = StringBuilder()
                } else {
                    require(line.length < MAX_LINE_CHARS) { "Report record exceeds 1 MiB" }; line.append(c.toChar())
                }
            }
        }
        return ProviderBatch(tokens.values.toList(), quotas)
    }

    private fun consumeNonBlank(
        line: StringBuilder,
        profile: ProviderProfile,
        tokens: MutableMap<String, ProviderTokenEvent>,
        quotas: MutableList<ProviderQuotaReport>
    ) {
        if (line.isNotBlank()) consume(line.toString(), profile, tokens, quotas)
    }

    private fun consume(
        text: String,
        profile: ProviderProfile,
        tokens: MutableMap<String, ProviderTokenEvent>,
        quotas: MutableList<ProviderQuotaReport>
    ) {
        val row = JsonParser.parseString(text).asJsonObject
        val captured = row.get("captured_at")?.asString?.let(Instant::parse) ?: Instant.EPOCH
        val batch = source(profile.vendor).parse(row, captured, profile)
        batch.tokens.forEach { next ->
            val previous = tokens[next.key]
            require(previous == null || (previous.counters.input == next.counters.input && previous.counters.output <= next.counters.output)) { "Conflicting request revisions" }
            tokens[next.key] = next
        }
        quotas.addAll(batch.quotas)
    }
}

internal fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
}

internal fun JsonObject.count(key: String, default: Long? = null): Long {
    val field = get(key) ?: return default ?: error("Missing token counter $key")
    require(field.isJsonPrimitive && field.asJsonPrimitive.isNumber)
    return field.asBigDecimal.longValueExact().also { require(it >= 0) }
}

internal fun stableIdentity(value: String): String {
    require(value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)) { "Missing stable usage identity" }
    return value
}
