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
    const val MAX_LINE_CHARS = 1024 * 1024
    fun stream(file: Path, profile: ProviderProfile, checkCancelled: () -> Unit = {}, consume: (ProviderBatch) -> Unit) {
        // One bounded record at a time; the caller stages facts transactionally on disk.
        Files.newBufferedReader(file).use { reader ->
            var line = StringBuilder()
            while (true) {
                checkCancelled()
                val c = reader.read()
                when (c) {
                    -1 -> {
                        consumeNonBlank(line, profile, consume)
                        break
                    }
                    '\n'.code -> {
                        consumeNonBlank(line, profile, consume)
                        line = StringBuilder()
                    }
                    else -> {
                        require(line.length < MAX_LINE_CHARS) { "Report record exceeds 1 MiB" }
                        line.append(c.toChar())
                    }
                }
            }
        }
    }

    private fun consumeNonBlank(
        line: StringBuilder,
        profile: ProviderProfile,
        consume: (ProviderBatch) -> Unit
    ) {
        if (line.isBlank()) return
        val row = JsonParser.parseString(line.toString()).asJsonObject
        val captured = row.get("captured_at")?.asString?.let(Instant::parse) ?: Instant.EPOCH
        consume(source(profile.vendor).parse(row, captured, profile))
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
