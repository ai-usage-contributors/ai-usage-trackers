@file:Suppress("SqlResolve") // Private runtime SQLite schema and connection-local staging; exercised by integration tests.

package local.aiusagetrackers

import com.google.gson.Gson
import java.sql.Connection
import java.time.Instant

/** Uses its owner's import transaction and connection; no independent IO or background jobs. */
internal class UsageAnalyticsStore(private val connection: Connection) {
    private val gson = Gson()
    private var cached: UsageAnalytics? = null
    private var cachedRevision: String? = null
    private var cachedAt: Instant? = null
    private var nextFuture: Instant? = null

    private fun discontinuity(session: String, usage: QuotaUsage, time: Instant): Boolean = connection.prepareStatement(
        """WITH samples AS (
           SELECT JSON_EXTRACT(data,'$.usedPercent') AS p,
             LAG(JSON_EXTRACT(data,'$.usedPercent')) OVER(ORDER BY seconds,nanos,key) AS previous
           FROM analytics WHERE session_key=? AND kind='quota' AND conflict=0
             AND JSON_EXTRACT(data,'$.bucket')=?
             AND COALESCE(JSON_EXTRACT(data,'$.windowMinutes'),10080)=?
             AND JSON_EXTRACT(data,'$.resetsAt')=? AND (seconds,nanos)<=(?,?)
           ) SELECT 1 FROM samples WHERE p<previous LIMIT 1""".trimIndent()
    ).use {
        it.setString(1, session); it.setString(2, usage.bucket); it.setInt(3, usage.windowMinutes)
        if (usage.resetsAt == null) it.setNull(4, java.sql.Types.BIGINT) else it.setLong(4, usage.resetsAt)
        it.setLong(5, time.epochSecond); it.setInt(6, time.nano)
        it.executeQuery().use { rows -> rows.next() }
    }

    private fun previousReset(session: String, usage: QuotaUsage, time: Instant): Long? = connection.prepareStatement(
        """SELECT MAX(CAST(JSON_EXTRACT(data,'$.resetsAt') AS INTEGER)) FROM analytics
           WHERE session_key=? AND kind='quota' AND conflict=0
           AND JSON_EXTRACT(data,'$.bucket')=?
           AND COALESCE(JSON_EXTRACT(data,'$.windowMinutes'),10080)=?
           AND (seconds,nanos)<(?,?) AND JSON_EXTRACT(data,'$.resetsAt')<=?""".trimIndent()
    ).use {
        it.setString(1, session); it.setString(2, usage.bucket); it.setInt(3, usage.windowMinutes)
        it.setLong(4, time.epochSecond); it.setInt(5, time.nano); it.setLong(6, time.epochSecond)
        it.executeQuery().use { rows -> rows.next(); rows.getLong(1).takeUnless { rows.wasNull() } }
    }

    fun prepare() = connection.createStatement().use {
        it.execute("CREATE TEMP TABLE staged_analytics (key TEXT PRIMARY KEY,kind TEXT NOT NULL,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,data TEXT NOT NULL,conflict INTEGER NOT NULL DEFAULT 0)")
    }

    fun clear() =
        connection.createStatement().use { it.executeUpdate("DELETE FROM staged_analytics WHERE key IS NOT NULL") }

    fun stage(record: AnalyticsRecord) {
        val data = when (record.kind) {
            "request" -> gson.toJson(record.request)
            "quota" -> gson.toJson(record.quota)
            else -> "{}"
        }
        // Request identity is stable; all other keys include the observation time. Replays never advance it.
        connection.prepareStatement(
            """
            INSERT INTO staged_analytics(key,kind,seconds,nanos,data) VALUES(?,?,?,?,?)
            ON CONFLICT(key) DO UPDATE SET conflict=MAX(conflict,
                CASE WHEN kind<>'activity' AND data<>excluded.data THEN 1 ELSE 0 END)
        """.trimIndent()
        ).use {
            it.setString(1, record.key); it.setString(2, record.kind); it.setLong(3, record.time.epochSecond)
            it.setInt(4, record.time.nano); it.setString(5, data); it.executeUpdate()
        }
    }

    fun accept(session: String) {
        connection.prepareStatement(
            """
            INSERT INTO analytics(session_key,key,kind,seconds,nanos,data,conflict)
            SELECT ?,key,kind,seconds,nanos,data,conflict FROM staged_analytics WHERE TRUE
            ON CONFLICT(session_key,key) DO UPDATE SET conflict=MAX(analytics.conflict,excluded.conflict,
                CASE WHEN analytics.kind<>'activity' AND analytics.data<>excluded.data THEN 1 ELSE 0 END)
        """.trimIndent()
        ).use {
            it.setString(1, session); it.executeUpdate()
        }
    }

    fun snapshot(now: Instant): UsageAnalytics {
        // Read the revision in the same SQLite snapshot as the data, including during a WAL import.
        // Avoid repeating history-wide quota selection on the monitor's 250 ms clock ticks.
        val revision = connection.createStatement().use {
            it.executeQuery("SELECT value FROM meta WHERE key='analytics_revision'")
                .use { rows -> if (rows.next()) rows.getString(1) else null }
        }
        val at = cachedAt
        val future = nextFuture
        if (cachedRevision == revision && at != null && now >= at && (future == null || now < future)) {
            cached?.let { return it }
        }
        val requests = mutableListOf<RequestReading>()
        connection.prepareStatement(
            """
            SELECT seconds,nanos,data FROM analytics
            WHERE kind='request' AND conflict=0 AND (seconds,nanos)<=(?,?)
            ORDER BY seconds DESC,nanos DESC,CASE WHEN key LIKE 'response:%' THEN 0 ELSE 1 END,session_key,key LIMIT 2
        """.trimIndent()
        ).use {
            it.setLong(1, now.epochSecond); it.setInt(2, now.nano)
            it.executeQuery().use { rows ->
                while (rows.next()) requests += RequestReading(
                    Instant.ofEpochSecond(rows.getLong(1), rows.getLong(2)),
                    gson.fromJson(rows.getString(3), RequestUsage::class.java)
                )
            }
        }
        val latestActivity = latestActivity(now)
        val quotas = quotaObservations(now)
        val conflicts = connection.createStatement().use {
            it.executeQuery("SELECT 1 FROM analytics WHERE conflict=1 LIMIT 1").use { rows -> rows.next() }
        }
        val result = UsageAnalytics(
            requests.firstOrNull(), latestActivity, quotas,
            requests.size > 1 && requests[0].time == requests[1].time,
            if (conflicts) setOf("Conflicting request or quota observations excluded") else emptySet(),
            operationalIssues = emptySet(),
            integrityIssues = if (conflicts) setOf("Conflicting request or quota observations excluded") else emptySet()
        )
        nextFuture =
            connection.prepareStatement("SELECT seconds,nanos FROM analytics WHERE (seconds,nanos)>(?,?) ORDER BY seconds,nanos LIMIT 1")
                .use {
                    it.setLong(1, now.epochSecond); it.setInt(2, now.nano)
                    it.executeQuery().use { rows ->
                        if (rows.next()) Instant.ofEpochSecond(
                            rows.getLong(1),
                            rows.getLong(2)
                        ) else null
                    }
                }
        cached = result; cachedRevision = revision; cachedAt = now
        return result
    }

    private fun latestActivity(now: Instant): Instant? {
        return connection.prepareStatement("SELECT seconds,nanos FROM analytics WHERE (kind='activity' OR conflict=1) AND (seconds,nanos)<=(?,?) ORDER BY seconds DESC,nanos DESC LIMIT 1")
            .use {
                it.setLong(1, now.epochSecond); it.setInt(2, now.nano)
                it.executeQuery().use { rows ->
                    if (rows.next()) Instant.ofEpochSecond(
                        rows.getLong(1),
                        rows.getLong(2)
                    ) else null
                }
            }
    }

    private fun quotaObservations(now: Instant): List<QuotaObservation> {
        val quotas = mutableListOf<QuotaObservation>()
        connection.prepareStatement(
            """
            WITH observations AS (
                SELECT *,JSON_REMOVE(data,'$.capturedAt') AS semantic FROM analytics
                WHERE kind='quota' AND conflict=0 AND (seconds,nanos)<=(?,?)
            ), changes AS (
                SELECT *,CASE WHEN session_key LIKE 'provider-quota:%' AND
                    LAG(semantic) OVER(
                        PARTITION BY session_key,JSON_EXTRACT(data,'$.bucket'),COALESCE(JSON_EXTRACT(data,'$.windowMinutes'),10080),JSON_EXTRACT(data,'$.window')
                        ORDER BY seconds,nanos,key)=semantic THEN 0 ELSE 1 END AS changed
                FROM observations
            ), runs AS (
                SELECT *,SUM(changed) OVER(
                    PARTITION BY session_key,JSON_EXTRACT(data,'$.bucket'),COALESCE(JSON_EXTRACT(data,'$.windowMinutes'),10080),JSON_EXTRACT(data,'$.window')
                    ORDER BY seconds,nanos,key ROWS UNBOUNDED PRECEDING) AS run
                FROM changes
            ), ranked AS (
                SELECT session_key,seconds,nanos,data,ROW_NUMBER() OVER(
                    PARTITION BY session_key,JSON_EXTRACT(data,'$.bucket'),COALESCE(JSON_EXTRACT(data,'$.windowMinutes'),10080),JSON_EXTRACT(data,'$.window')
                    ORDER BY run DESC,seconds,nanos,key) AS rank
                FROM runs
            ) SELECT session_key,seconds,nanos,data FROM ranked WHERE rank=1 ORDER BY session_key,data
        """.trimIndent()
        ).use {
            it.setLong(1, now.epochSecond); it.setInt(2, now.nano)
            it.executeQuery().use { rows ->
                while (rows.next()) {
                    val time = Instant.ofEpochSecond(rows.getLong(2), rows.getLong(3))
                    val usage = gson.fromJson(rows.getString(4), QuotaUsage::class.java).let { usage ->
                        if (usage.windowMinutes == 0 && !rows.getString(1).startsWith("provider-")) usage.copy(
                            windowMinutes = 10080
                        ) else usage
                    }
                    quotas += QuotaObservation(
                        // A session is an observation scope, never proof that two sources share an account.
                        scopeLabel(rows.getString(1)), time, usage, previousReset(rows.getString(1), usage, time),
                        discontinuity(rows.getString(1), usage, time)
                    )
                }
            }
        }
        return quotas
    }

    companion object {
        fun createSchema(connection: Connection) = connection.createStatement().use {
            it.execute("CREATE TABLE analytics (session_key TEXT NOT NULL REFERENCES sessions(key),key TEXT NOT NULL,kind TEXT NOT NULL,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,data TEXT NOT NULL,conflict INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(session_key,key))")
            it.execute("CREATE INDEX analytics_time ON analytics(kind,seconds,nanos)")
            it.execute("CREATE INDEX analytics_observed ON analytics(seconds,nanos)")
        }

        private fun scopeLabel(key: String): String = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        ).take(12)
    }
}
