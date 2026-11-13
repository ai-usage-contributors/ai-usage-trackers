package local.aiusagetrackers

import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneOffset

/** Reads metadata only. This state never establishes or changes the period ledger's baseline. */
internal class CodexAnalyticsParser(private val issues: MutableSet<String>) {
    private var previous: RequestCounters? = null
    private var pendingExplicit: RequestCounters? = null
    private var pendingCumulative: RequestCounters? = null
    private var explicitTotal: Long? = null
    private var unverifiedAggregate = false

    fun read(row: JsonObject): List<AnalyticsRecord> {
        val result = mutableListOf<AnalyticsRecord>()
        val type = row.get("type")?.asString
        val payload = row.getAsJsonObject("payload") ?: return result
        if (type != "token_usage_record" &&
            !(type == "event_msg" && payload.get("type")?.asString == "token_count")
        ) return result
        val time = try {
            row.get("timestamp")?.asString?.let(Instant::parse)
                ?.also { require(it.atZone(ZoneOffset.UTC).year in 1..9999) }
        } catch (_: RuntimeException) {
            null
        }
        if (time == null) {
            issues += "Request or quota metadata without a valid timestamp excluded"
            return result
        }
        if (type == "token_usage_record") {
            readResponse(payload, time, result)
            return result
        }
        readQuotas(payload, time, result)
        readLegacy(payload, time, result)
        return result
    }


    private fun readResponse(payload: JsonObject, time: Instant, result: MutableList<AnalyticsRecord>) {
        try {
            val identity = payload.get("response_id")
            require(identity != null && identity.isJsonPrimitive && identity.asJsonPrimitive.isString)
            val responseId = identity.asString
            require(!responseId.isNullOrBlank() && responseId.length <= 512)
            val usage = counters(payload.getAsJsonObject("usage")) ?: error("Missing request usage")
            pendingExplicit = usage
            pendingCumulative = try {
                counters(payload.get("thread_token_usage")?.takeUnless { it.isJsonNull }?.asJsonObject)
            } catch (_: RuntimeException) {
                null
            }
            val aggregate = pendingCumulative
            if (aggregate == null) unverifiedAggregate = true
            else explicitTotal = maxOf(explicitTotal ?: 0, aggregate.total)
            result += AnalyticsRecord(
                "response:$responseId",
                "request",
                time,
                RequestUsage(usage, "response record")
            )
        } catch (_: RuntimeException) {
            issues += "Invalid request metadata excluded"
            result += activity(time)
        }
    }

    private fun readQuotas(payload: JsonObject, time: Instant, result: MutableList<AnalyticsRecord>) {
        // Quota-only records may have null or absent info. Parse them independently.
        try {
            payload.get("rate_limits")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { limits ->
                val bucket = limits.get("limit_id")?.takeUnless { it.isJsonNull }?.asString ?: "unspecified"
                require(bucket.length in 1..256 && bucket.none(Char::isISOControl))
                for (name in listOf("primary", "secondary")) {
                    readQuotaWindow(limits, bucket, name, time, result)
                }
            }
        } catch (_: RuntimeException) {
            issues += "Invalid quota metadata excluded"
        }
    }

    private fun readQuotaWindow(
        limits: JsonObject,
        bucket: String,
        name: String,
        time: Instant,
        result: MutableList<AnalyticsRecord>
    ) {
        try {
            val window = limits.get(name)?.takeUnless { it.isJsonNull }?.asJsonObject ?: return
            val duration = integer(window, "window_minutes")
            if (duration != 10080L && duration != 300L) return
            val used = window.get("used_percent")?.takeUnless { it.isJsonNull }?.let { element ->
                require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
                element.asDouble.also { require(it.isFinite() && it in 0.0..100.0) }
            }
            val reset = window.get("resets_at")?.takeUnless { it.isJsonNull }?.let {
                integer(window, "resets_at").also { seconds ->
                    require(Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).year in 1..9999)
                }
            }
            require(used != null || reset != null)
            result += AnalyticsRecord(
                "quota:$bucket:$name:$duration:${time.epochSecond}:${time.nano}", "quota", time,
                quota = QuotaUsage(bucket, used, reset, name, duration.toInt())
            )
        } catch (_: RuntimeException) {
            issues += "Invalid quota window metadata excluded"
        }
    }

    private fun readLegacy(payload: JsonObject, time: Instant, result: MutableList<AnalyticsRecord>) {
        try {
            val info = payload.get("info")?.takeUnless { it.isJsonNull }?.asJsonObject ?: return
            val cumulative = counters(info.get("total_token_usage")?.takeUnless { it.isJsonNull }?.asJsonObject)
            val last = counters(info.get("last_token_usage")?.takeUnless { it.isJsonNull }?.asJsonObject)
            val baseline = previous
            previous = cumulative
            if (cumulative != null && cumulative == baseline) return
            if (cumulative != null && last != null &&
                pendingCumulative == cumulative && pendingExplicit == last
            ) {
                pendingExplicit = null
                pendingCumulative = null
                return
            }
            pendingExplicit = null
            pendingCumulative = null
            if (cumulative != null || last != null) result += activity(time)
            // A legacy report is usable only when every field reconciles to one new response.
            // No inference from the first cumulative reading, resets, fallback-only or mixed sequences.
            if (cumulative != null && baseline != null && last != null && cumulative.total > baseline.total &&
                delta(cumulative, baseline) == last
            ) {
                val key = listOf(
                    cumulative.total,
                    cumulative.input,
                    cumulative.cached,
                    cumulative.output,
                    cumulative.reasoning
                )
                    .joinToString(":")
                result += AnalyticsRecord(
                    "legacy:$key:$time",
                    "request",
                    time,
                    RequestUsage(last, "cross-checked legacy report")
                )
            }
        } catch (_: RuntimeException) {
            previous = null
            pendingExplicit = null
            pendingCumulative = null
            issues += "Invalid request metadata excluded"
            result += activity(time)
        }
    }

    fun finish() {
        val reported = explicitTotal
        if (unverifiedAggregate || (reported != null && (previous == null || reported > previous!!.total))) {
            issues += "Request records are not fully reconciled with period totals; period coverage incomplete"
        }
    }

    private fun activity(time: Instant) = AnalyticsRecord("activity:$time", "activity", time)

    private fun delta(a: RequestCounters, b: RequestCounters) = RequestCounters(
        a.total - b.total, a.input - b.input,
        if (a.cached != null && b.cached != null) a.cached - b.cached else null,
        a.output - b.output,
        if (a.reasoning != null && b.reasoning != null) a.reasoning - b.reasoning else null,
    )

    private fun counters(value: JsonObject?): RequestCounters? {
        if (value == null) return null
        val result = RequestCounters(
            integer(value, "total_tokens"),
            integer(value, "input_tokens"),
            optionalCounter(value, "cached_input_tokens"),
            integer(value, "output_tokens"),
            optionalCounter(value, "reasoning_output_tokens")
        )
        require((result.cached ?: 0) <= result.input && (result.reasoning ?: 0) <= result.output)
        require(Math.addExact(result.input, result.output) == result.total)
        return result
    }

    private fun optionalCounter(value: JsonObject, key: String): Long? =
        if (value.has(key)) integer(value, key) else null

    private fun integer(value: JsonObject, key: String): Long {
        val element = value.get(key) ?: error("Missing counter")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
        val text = element.asString
        require(text.isNotEmpty() && text.all { it in '0'..'9' })
        return text.toLong()
    }
}
