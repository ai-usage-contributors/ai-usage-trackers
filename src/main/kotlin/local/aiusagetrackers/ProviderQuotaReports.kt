package local.aiusagetrackers

import com.google.gson.JsonObject
import java.time.Instant

data class ProviderQuotaReport(
    val pool: QuotaPool,
    val remainingPercent: Double?,
    val resetsAt: Instant?,
    /** Null when upstream does not establish when the provider last checked this value. */
    val observedAt: Instant?,
    val capturedAt: Instant,
    val disabled: Boolean = false,
)

/** Normalizes documented status-line fields only. Never interprets context occupancy as quota. */
object ProviderQuotaReports {
    fun claude(input: JsonObject, capturedAt: Instant, observedAt: Instant? = null): List<ProviderQuotaReport> {
        val limits = input.get("rate_limits")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return listOf("five_hour" to 300L, "seven_day" to 10080L).mapNotNull { (key, minutes) ->
            val value = limits.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val used = number(value, "used_percentage")?.also { require(it in 0.0..100.0) }
            val reset = value.get("resets_at")?.takeUnless { it.isJsonNull }?.let {
                val seconds = it.asBigDecimal.longValueExact()
                Instant.ofEpochSecond(seconds)
            }
            ProviderQuotaReport(QuotaPool(key, QuotaBoundaryKind.FIXED_DURATION,
                java.time.Duration.ofMinutes(minutes), used != null, reset != null),
                used?.let { 100.0 - it }, reset, observedAt, capturedAt)
        }
    }

    /** Bucket semantics are supplied by a verified adapter profile, never inferred from an ID. */
    fun antigravity(input: JsonObject, pools: List<QuotaPool>, capturedAt: Instant,
                    observedAt: Instant? = null, includeDisabled: Boolean = false): List<ProviderQuotaReport> {
        val quotas = input.get("quota")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        return pools.mapNotNull { pool ->
            val value = quotas.get(pool.id)?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (value.get("disabled")?.takeUnless { it.isJsonNull }?.asBoolean == true) {
                return@mapNotNull if (includeDisabled) ProviderQuotaReport(pool, null, null, observedAt, capturedAt, disabled = true) else null
            }
            val remaining = number(value, "remaining_fraction")?.also { require(it in 0.0..1.0) }
            // remaining_amount alone has no denominator and cannot establish a percentage.
            val reset = value.get("reset_time")?.takeUnless { it.isJsonNull }?.asString?.let(Instant::parse)
            ProviderQuotaReport(pool.copy(percentage = remaining != null, resetInstant = reset != null),
                remaining?.times(100), reset, observedAt, capturedAt)
        }
    }

    private fun number(value: JsonObject, key: String): Double? =
        value.get(key)?.takeUnless { it.isJsonNull }?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "Expected numeric quota field" }
            it.asDouble.also { number -> require(number.isFinite()) }
        }
}
