package local.aiusagetrackers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.*

// Collection semantics adapted from Codex Usage; see THIRD_PARTY_NOTICES.txt.
class CodexSessionParser {
    data class Summary(
        val id: String?,
        val digest: String,
        val issues: Set<String>,
        val latest: Instant?,
        val forked: Boolean
    )

    private data class Counters(
        val total: Long,
        val input: Long,
        val cached: Long,
        val output: Long,
        val reasoning: Long
    ) {
        fun decreases(previous: Counters): Boolean = total < previous.total || input < previous.input ||
                cached < previous.cached || output < previous.output || reasoning < previous.reasoning
    }

    fun parse(path: Path, day: DayWindow, checkCancelled: () -> Unit = {}): ParsedSession {
        val increments = ArrayList<Increment>()
        val summary = stream(path, checkCancelled) { increment ->
            if (increment.time >= day.start && increment.time < day.end) increments += increment
        }
        return ParsedSession(
            summary.id,
            summary.digest,
            if (summary.forked) emptyList() else increments,
            summary.issues,
            summary.latest
        )
    }

    internal fun stream(
        path: Path,
        checkCancelled: () -> Unit = {},
        onObservation: (AnalyticsRecord) -> Unit = {},
        onIncrement: (Increment) -> Unit,
    ): Summary {
        val digest = MessageDigest.getInstance("SHA-256")
        val issues = mutableSetOf<String>()
        val analytics = CodexAnalyticsParser(issues)
        val state = StreamState(issues)
        DigestInputStream(Files.newInputStream(path), digest).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                checkCancelled()
                val line = boundedLine(reader, checkCancelled) ?: break
                val row = parseRow(line, issues) ?: continue
                val observations = readObservations(analytics, row, issues)
                // Database errors and cancellation from either callback must propagate.
                observations.forEach(onObservation)
                val increment = state.increment(row)
                // Callers' database failures and cancellation must propagate, not become malformed rows.
                if (increment != null) onIncrement(increment)
            }
        }
        analytics.finish()
        if (state.fork) {
            issues += "Forked session history excluded; coverage incomplete"
        }
        return Summary(state.id, HexFormat.of().formatHex(digest.digest()), issues, state.latest, state.fork)
    }

    private fun parseRow(line: String, issues: MutableSet<String>): JsonObject? {
        if (line.isBlank()) return null
        return try {
            JsonParser.parseString(line).asJsonObject
        } catch (_: RuntimeException) {
            issues += "Malformed or incomplete log records; retried on refresh"
            null
        }
    }

    private fun readObservations(
        analytics: CodexAnalyticsParser,
        row: JsonObject,
        issues: MutableSet<String>,
    ): List<AnalyticsRecord> = try {
        analytics.read(row)
    } catch (_: RuntimeException) {
        issues += "Invalid request or quota metadata excluded"
        emptyList()
    }

    private inner class StreamState(private val issues: MutableSet<String>) {
        var id: String? = null
        var previous: Counters? = null
        var fallbackSinceCumulative = false
        var latest: Instant? = null
        var fork = false
        fun increment(row: JsonObject): Increment? {
            return try {
                val payload = row.getAsJsonObject("payload") ?: return null
                if (row.get("type")?.asString == "session_meta") {
                    readIdentity(payload); return null
                }
                if (row.get("type")?.asString != "event_msg" || payload.get("type")?.asString != "token_count") return null
                val info = payload.get("info")?.takeUnless { it.isJsonNull }?.asJsonObject ?: return null
                val cumulative = counters(info.get("total_token_usage")?.takeUnless { it.isJsonNull }?.asJsonObject)
                val last = counters(info.get("last_token_usage")?.takeUnless { it.isJsonNull }?.asJsonObject)
                val amount = amount(cumulative, last) ?: return null
                val time = try {
                    row.get("timestamp")?.asString?.let(Instant::parse)
                } catch (_: Exception) {
                    null
                }
                if (time == null) {
                    issues += "Usage without a valid timestamp excluded"
                    return null
                }
                if (latest == null || time > latest) latest = time
                if (amount > 0) Increment(time, amount) else null
            } catch (_: RuntimeException) {
                issues += "Malformed or incomplete log records; retried on refresh"
                // A later cumulative record still establishes usage since the last valid baseline.
                null
            }
        }

        private fun readIdentity(payload: JsonObject) {
            val nextId = payload.get("id")?.asString
            if (id != null && id != nextId) issues += "Conflicting identities in one log"
            id = nextId
            fork = fork || listOf(
                "forked_from_id",
                "forked_from",
                "parent_session_id"
            ).any { payload.has(it) && !payload.get(it).isJsonNull }
        }

        private fun amount(cumulative: Counters?, last: Counters?): Long? {
            val baseline = previous
            val amount = if (cumulative != null) {
                previous = cumulative
                when {
                    fallbackSinceCumulative || (baseline != null && cumulative.decreases(baseline)) -> {
                        issues += "Counter reset or mixed reporting; coverage incomplete"
                        last?.total ?: 0L
                    }

                    else -> cumulative.total - (baseline?.total ?: 0L)
                }.also { fallbackSinceCumulative = false }
            } else if (last != null) {
                fallbackSinceCumulative = true
                last.total
            } else {
                // Rate-limit events may contain no usage yet.
                return null
            }
            return amount
        }
    }

    private fun counters(value: JsonObject?): Counters? {
        if (value == null) return null


        val result = Counters(
            field(value, "total_tokens"), field(value, "input_tokens"), field(value, "cached_input_tokens", true),
            field(value, "output_tokens"), field(value, "reasoning_output_tokens", true)
        )
        require(result.cached <= result.input && result.reasoning <= result.output)
        require(Math.addExact(result.input, result.output) == result.total)
        return result
    }

    private fun field(value: JsonObject, name: String, optional: Boolean = false): Long {
        val element = value.get(name) ?: if (optional) return 0 else error("Missing counter")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isNumber)
        val text = element.asString
        require(text.isNotEmpty() && text.all { it in '0'..'9' })
        return text.toLong()
    }

    private fun boundedLine(reader: Reader, checkCancelled: () -> Unit): String? {
        val line = StringBuilder()
        var oversized = false
        var readSinceCheck = 0
        while (true) {
            if (++readSinceCheck >= 8192) {
                checkCancelled(); readSinceCheck = 0
            }
            val c = reader.read()
            if (c == -1 || c == '\n'.code) return completedLine(line, oversized, c == -1)
            if (line.length < MAX_LINE) line.append(c.toChar()) else oversized = true
        }
    }

    private fun completedLine(line: StringBuilder, oversized: Boolean, endOfFile: Boolean): String? = when {
        oversized -> "!oversized"
        endOfFile && line.isEmpty() -> null
        else -> line.toString()
    }

    companion object {
        private const val MAX_LINE = 4 * 1024 * 1024
    }
}
