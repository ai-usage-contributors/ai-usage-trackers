package local.aiusagetrackers


import com.google.gson.Gson
import com.google.gson.JsonParser
import org.sqlite.JDBC
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.*

/** Each instance has one owner coroutine; readers use a separate connection from the importer. */
class UsageDatabase private constructor(readOnly: Boolean, private val connection: Connection) :
    AutoCloseable by connection {
    constructor(path: Path, readOnly: Boolean = false) : this(readOnly, openConnection(path, readOnly))

    private val gson = Gson()
    private val resolver = UsagePeriodResolver()
    private val analytics: UsageAnalyticsStore

    init {
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=2000")
                statement.execute("PRAGMA foreign_keys=ON")
            }
            if (!readOnly) initialize()
            else connection.createStatement().use { sql ->
                val version = sql.executeQuery("PRAGMA user_version").use { row -> row.next(); row.getInt(1) }
                require(version == SCHEMA_VERSION) { "Unsupported usage database schema; existing history preserved" }
                sql.execute("PRAGMA query_only=ON")
            }
            analytics = UsageAnalyticsStore(connection)
            if (!readOnly) analytics.prepare()
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }

    private fun initialize() {
        val version = connection.createStatement()
            .use { it.executeQuery("PRAGMA user_version").use { row -> row.next(); row.getInt(1) } }
        require(version == 0 || version == SCHEMA_VERSION) { "Unsupported usage database version $version; existing database preserved" }
        if (version == 0) {
            val populated = connection.createStatement().use {
                it.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
                    .use { row -> row.next(); row.getInt(1) > 0 }
            }
            require(!populated) { "Unrecognized usage database; existing database preserved" }
            transaction {
                connection.createStatement().use { sql ->
                    sql.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    sql.execute("CREATE TABLE sessions (key TEXT PRIMARY KEY, digest TEXT NOT NULL, issues TEXT NOT NULL, event_count INTEGER NOT NULL)")
                    sql.execute("CREATE TABLE sources (path TEXT PRIMARY KEY, size INTEGER NOT NULL, modified TEXT NOT NULL, digest TEXT NOT NULL, session_key TEXT NOT NULL REFERENCES sessions(key), issues TEXT NOT NULL, parser_version INTEGER NOT NULL, identity_accepted INTEGER NOT NULL DEFAULT 1 CHECK(identity_accepted IN (0,1)))")
                    sql.execute("CREATE TABLE events (session_key TEXT NOT NULL REFERENCES sessions(key), seq INTEGER NOT NULL, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, total INTEGER NOT NULL CHECK(total>0), signature TEXT NOT NULL, PRIMARY KEY(session_key,seq))")
                    sql.execute("CREATE INDEX events_time ON events(seconds,nanos)")
                    sql.execute("CREATE INDEX events_first ON events(seq,signature)")
                    sql.execute("CREATE INDEX source_digest ON sources(digest)")
                    UsageAnalyticsStore.createSchema(connection)
                    createProviderSchema()
                    sql.execute("PRAGMA user_version=$SCHEMA_VERSION")
                }
            }
        }
        // WAL permits date/zone refresh queries while a large file is being imported.
        connection.createStatement()
            .use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA synchronous=FULL") }
        connection.createStatement().use {
            it.execute("PRAGMA temp_store=FILE")
            it.execute("CREATE TEMP TABLE staged (seq INTEGER PRIMARY KEY, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, total INTEGER NOT NULL, signature TEXT NOT NULL)")
            it.execute("CREATE TEMP TABLE staged_provider (key TEXT PRIMARY KEY,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,data TEXT NOT NULL,precision TEXT NOT NULL)")
        }
    }

    fun acknowledgedIssues(page: String): Set<String> =
        decodeIssues(meta("acknowledged_$page") ?: "[]")

    private fun createProviderSchema() = connection.createStatement().use {
        it.execute("CREATE TABLE provider_files(path TEXT PRIMARY KEY, size INTEGER NOT NULL, modified TEXT NOT NULL)")
    }

    /** Each account owns its ledger. Never attach the same ledger to another account. */
    fun bindAccount(profile: ProviderProfile) = transaction {
        val identity = "${profile.vendor.name}/${profile.id}"
        val previous = meta("provider_account")
        require(previous == null || previous == identity) { "Ledger belongs to another account" }
        setMeta("provider_account", identity)
    }

    @Suppress("SqlResolve") // Runtime ledger schema, exercised by migration/import integration tests.
    fun providerUnchanged(file: Path, attrs: BasicFileAttributes): Boolean = connection.prepareStatement(
        "SELECT 1 FROM provider_files WHERE path=? AND size=? AND modified=?"
    ).use {
        it.setString(1, file.toString()); it.setLong(2, attrs.size()); it.setString(
        3,
        attrs.lastModifiedTime().toString()
    )
        it.executeQuery().use { row -> row.next() }
    }

    @Suppress("SqlResolve") // Runtime ledger schema; statements execute transactionally in integration tests.
    fun importProvider(
        file: Path,
        attrs: BasicFileAttributes,
        profile: ProviderProfile,
        checkCancelled: () -> Unit = {}
    ) {
        if (meta("provider_account") == null) bindAccount(profile)
        require(meta("provider_account") == "${profile.vendor.name}/${profile.id}") { "Ledger belongs to another account" }
        transaction {
            connection.createStatement().use { it.executeUpdate("DELETE FROM staged_provider WHERE key IS NOT NULL") }
            UsageSourceRegistry.stream(file, profile, checkCancelled) { batch ->
                batch.tokens.forEach(::stageProviderToken)
                importProviderQuotas(batch.quotas, checkCancelled)
            }
            connection.createStatement().use { query ->
                query.executeQuery("SELECT key,seconds,nanos,data,precision FROM staged_provider").use { rows ->
                    while (rows.next()) importToken(ProviderTokenEvent(rows.getString(1),
                        Instant.ofEpochSecond(rows.getLong(2), rows.getInt(3).toLong()),
                        gson.fromJson(rows.getString(4), RequestCounters::class.java), rows.getString(5)), profile, checkCancelled)
                }
            }
            val after = Files.readAttributes(file, BasicFileAttributes::class.java)
            check(after.size() == attrs.size() && after.lastModifiedTime() == attrs.lastModifiedTime()) { "Report changed during reading" }
            connection.prepareStatement("INSERT INTO provider_files VALUES(?,?,?) ON CONFLICT(path) DO UPDATE SET size=excluded.size,modified=excluded.modified")
                .use {
                    it.setString(1, file.toString()); it.setLong(2, attrs.size()); it.setString(3, attrs.lastModifiedTime().toString()); it.executeUpdate()
                }
            setMeta("analytics_revision", Math.addExact(meta("analytics_revision")?.toLong() ?: 0L, 1L).toString())
            checkCancelled()
        }
    }

    @Suppress("SqlResolve") // Connection-local staging uses the same import transaction.
    private fun stageProviderToken(event: ProviderTokenEvent) {
        val previous = stringQuery("SELECT data FROM staged_provider WHERE key=?", event.key)
            ?.let { gson.fromJson(it, RequestCounters::class.java) }
        require(previous == null || (previous.input == event.counters.input && previous.cached == event.counters.cached && previous.output <= event.counters.output)) { "Conflicting request revisions" }
        connection.prepareStatement("INSERT INTO staged_provider VALUES(?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET seconds=excluded.seconds,nanos=excluded.nanos,data=excluded.data,precision=excluded.precision").use {
            it.setString(1, event.key); it.setLong(2, event.time.epochSecond); it.setInt(3, event.time.nano)
            it.setString(4, gson.toJson(event.counters)); it.setString(5, event.precision); it.executeUpdate()
        }
    }

    @Suppress("SqlResolve") // Private ledger schema, called inside the file transaction.
    private fun importProviderQuotas(quotas: List<ProviderQuotaReport>, checkCancelled: () -> Unit) {
            for ((pool, remainingPercent, resetsAt, observedAt, capturedAt, disabled) in quotas) {
                checkCancelled()
                require(capturedAt != Instant.EPOCH) { "Quota capture has no acquisition timestamp" }
                val duration = pool.duration?.toMinutes()?.toInt() ?: 0
                val usage = QuotaUsage(
                    pool.id, remainingPercent?.let { 100 - it }, resetsAt?.epochSecond,
                    pool.id, duration, freshnessUnknown = observedAt == null, disabled = disabled
                )
                val fingerprint = hash(gson.toJson(usage)) + ":$capturedAt"
                val key = "provider-quota:${hash(pool.id)}"
                connection.prepareStatement("INSERT OR IGNORE INTO sessions VALUES(?,?,?,0)").use {
                    it.setString(1, key); it.setString(2, fingerprint); it.setString(
                    3,
                    "[]"
                ); it.executeUpdate()
                }
                // Acquisition identity preserves transitions back to a prior value.
                // Snapshot selection coalesces only consecutive equal provider values.
                analytics.clear()
                analytics.stage(
                    AnalyticsRecord(
                        fingerprint,
                        "quota",
                        observedAt ?: capturedAt,
                        quota = usage.copy(capturedAt = capturedAt.toString())
                    )
                )
                if (stringQuery(
                        "SELECT key FROM analytics WHERE session_key=? AND key=?",
                        key,
                        fingerprint
                    ) == null
                ) analytics.accept(key)
            }
    }

    @Suppress("SqlResolve") // Runtime ledger schema; statements execute transactionally in integration tests.
    private fun importToken(event: ProviderTokenEvent, profile: ProviderProfile, checkCancelled: () -> Unit) {
        val (eventKey, time, counters, precision) = event
        checkCancelled()
        if (counters.total == 0L) return
        val key = "provider:${hash(eventKey)}"
        val data = gson.toJson(counters)
        val previous = stringQuery("SELECT digest FROM sessions WHERE key=?", key)
        val old = previous?.let { gson.fromJson(it, RequestCounters::class.java) }
        if (old != null && old != counters) {
            require(old.input == counters.input && old.output <= counters.output && old.cached == counters.cached) { "Conflicting usage identity; previous history retained" }
        }
        if (old == counters) return
        connection.prepareStatement("INSERT INTO sessions VALUES(?,?,?,1) ON CONFLICT(key) DO UPDATE SET digest=excluded.digest")
            .use {
                it.setString(1, key); it.setString(2, data); it.setString(3, "[]"); it.executeUpdate()
            }
        connection.prepareStatement("INSERT INTO events VALUES(?,0,?,?,?,?) ON CONFLICT(session_key,seq) DO UPDATE SET seconds=excluded.seconds,nanos=excluded.nanos,total=excluded.total,signature=excluded.signature")
            .use {
                it.setString(1, key); it.setLong(2, time.epochSecond); it.setInt(3, time.nano); it.setLong(
                4,
                counters.total
            ); it.setString(5, hash(data)); it.executeUpdate()
            }
        // Finalized revisions replace the same request, not an additional request.
        connection.prepareStatement("DELETE FROM analytics WHERE session_key=?")
            .use { it.setString(1, key); it.executeUpdate() }
        analytics.clear()
        analytics.stage(
            AnalyticsRecord(
                eventKey,
                "request",
                time,
                RequestUsage(counters, "${profile.vendor}; $precision; local coverage only")
            )
        )
        analytics.accept(key)
    }

    fun acknowledgeIssues(page: String, issues: Set<String>) {
        require(page in setOf("warnings", "integrity"))
        transaction { setMeta("acknowledged_$page", gson.toJson(acknowledgedIssues(page) + issues)) }
    }

    fun unchanged(file: Path, attrs: BasicFileAttributes): Boolean = connection.prepareStatement(
        "SELECT 1 FROM sources s JOIN sessions h ON h.key=s.session_key WHERE path=? AND size=? AND modified=? AND s.issues='[]' AND h.issues='[]' AND parser_version=? AND identity_accepted=1"
    ).use { query ->
        query.setString(1, file.toString()); query.setLong(2, attrs.size()); query.setString(
        3,
        attrs.lastModifiedTime().toString()
    ); query.setInt(4, PARSER_VERSION)
        query.executeQuery().use { it.next() }
    }

    fun importFile(file: Path, attrs: BasicFileAttributes, checkCancelled: () -> Unit = {}) {
        transaction {
            connection.createStatement().use { it.executeUpdate("DELETE FROM staged WHERE seq>=0") }
            analytics.clear()
            val (summary, count) = stageCodexFile(file, checkCancelled)
            checkCancelled()
            val after = Files.readAttributes(file, BasicFileAttributes::class.java)
            check(after.size() == attrs.size() && after.lastModifiedTime() == attrs.lastModifiedTime()) { "Log changed during import; previous reading retained" }
            val previousKey =
                stringQuery("SELECT session_key FROM sources WHERE path=? AND identity_accepted=1", file.toString())
            val provisionalKey =
                stringQuery("SELECT session_key FROM sources WHERE path=? AND identity_accepted=0", file.toString())
            val exactCopy = stringQuery(
                "SELECT session_key FROM sources WHERE digest=? AND identity_accepted=1 LIMIT 1",
                summary.digest
            )
            val firstSignature = stringQuery("SELECT signature FROM staged WHERE seq=0")
            val candidate = candidateSessionKey(summary, previousKey, exactCopy, firstSignature)
            val ambiguous = isAmbiguousIdentity(summary, previousKey, exactCopy, provisionalKey, candidate)
            val key =
                summary.id?.takeIf(String::isNotBlank)?.let { "session:$it" } ?: previousKey ?: exactCopy
                ?: provisionalKey ?: candidate
                ?: "content:${summary.digest}"
            val oldCount = stringQuery("SELECT event_count FROM sessions WHERE key=?", key)?.toLong() ?: 0L
            val issues = summary.issues.toMutableSet()
            val differs =
                connection.prepareStatement("SELECT 1 FROM events e JOIN staged s ON e.seq=s.seq WHERE e.session_key=? AND e.signature<>s.signature LIMIT 1")
                    .use {
                        it.setString(1, key); it.executeQuery().use { row -> row.next() }
                    }
            val rejected = rejectImport(previousKey, key, ambiguous, summary, differs, count < oldCount, issues)
            val sourceKey = if (previousKey != null && previousKey != key) previousKey else key
            connection.prepareStatement("INSERT OR IGNORE INTO sessions VALUES(?,?,?,0)").use {
                it.setString(1, sourceKey); it.setString(2, summary.digest); it.setString(
                3,
                gson.toJson(issues)
            ); it.executeUpdate()
            }
            if (!rejected) {
                analytics.accept(key)
                setMeta("analytics_revision", Math.addExact(meta("analytics_revision")?.toLong() ?: 0L, 1L).toString())
                connection.prepareStatement("INSERT INTO events SELECT ?,seq,seconds,nanos,total,signature FROM staged WHERE seq>=?")
                    .use {
                        it.setString(1, key); it.setLong(2, oldCount); it.executeUpdate()
                    }
                connection.prepareStatement("UPDATE sessions SET digest=?,issues=?,event_count=? WHERE key=?").use {
                    it.setString(1, summary.digest); it.setString(2, gson.toJson(issues)); it.setLong(
                    3,
                    count
                ); it.setString(4, key); it.executeUpdate()
                }
            }
            connection.prepareStatement("INSERT INTO sources (path,size,modified,digest,session_key,issues,parser_version,identity_accepted) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET size=excluded.size,modified=excluded.modified,digest=excluded.digest,session_key=excluded.session_key,issues=excluded.issues,parser_version=excluded.parser_version,identity_accepted=excluded.identity_accepted")
                .use {
                    it.setString(1, file.toString()); it.setLong(2, attrs.size()); it.setString(
                    3,
                    attrs.lastModifiedTime().toString()
                ); it.setString(4, summary.digest)
                    it.setString(5, sourceKey); it.setString(6, gson.toJson(issues)); it.setInt(
                    7,
                    PARSER_VERSION
                ); it.setInt(8, if (ambiguous) 0 else 1); it.executeUpdate()
                }
            checkCancelled()
        }
    }

    private fun stageCodexFile(file: Path, checkCancelled: () -> Unit): Pair<CodexSessionParser.Summary, Long> {
        var count = 0L
        var chain = ""
        val summary = connection.prepareStatement("INSERT INTO staged VALUES(?,?,?,?,?)").use { insert ->
            val parsed = CodexSessionParser().stream(file, checkCancelled, analytics::stage) { (time, total) ->
                chain = hash("$chain:${time.epochSecond}:${time.nano}:$total")
                insert.setLong(1, count++); insert.setLong(2, time.epochSecond); insert.setInt(
                3,
                time.nano
            ); insert.setLong(4, total); insert.setString(5, chain)
                insert.addBatch()
                if (count % 256 == 0L) insert.executeBatch()
            }
            insert.executeBatch()
            parsed
        }
        return summary to count
    }

    private fun isAmbiguousIdentity(
        summary: CodexSessionParser.Summary, previousKey: String?, exactCopy: String?, provisionalKey: String?, candidate: String?
    ): Boolean = summary.id.isNullOrBlank() && previousKey == null && exactCopy == null &&
        (provisionalKey != null || candidate != null)

    private fun candidateSessionKey(
        summary: CodexSessionParser.Summary, previousKey: String?, exactCopy: String?, firstSignature: String?
    ): String? =
        if (summary.id.isNullOrBlank() && previousKey == null && exactCopy == null && firstSignature != null)
            stringQuery(
                "SELECT session_key FROM events WHERE seq=0 AND signature=? ORDER BY session_key LIMIT 1",
                firstSignature
            ) else null

    private fun rejectImport(
        previousKey: String?,
        key: String,
        ambiguous: Boolean,
        summary: CodexSessionParser.Summary,
        differs: Boolean,
        truncated: Boolean,
        issues: MutableSet<String>
    ): Boolean {
        return when {
            previousKey != null && previousKey != key -> {
                issues += "Source identity changed; previous history retained"; true
            }

            ambiguous -> {
                issues += AMBIGUOUS_IDENTITY; true
            }

            summary.forked -> true
            differs -> {
                issues += "Conflicting session history excluded; previous history retained"; true
            }

            truncated -> {
                issues += "Truncated session; previously imported usage retained"; true
            }

            summary.issues.any { "identities" in it } -> true
            else -> false
        }
    }

    fun recordScan(
        homes: Int,
        files: Int,
        issues: Set<String>,
        now: Instant,
        integrityIssues: Set<String> = emptySet()
    ) = transaction {
        setMeta("homes", homes.toString()); setMeta("files", files.toString()); setMeta(
        "issues",
        gson.toJson(issues + integrityIssues)
    )
        setMeta("operational_issues", gson.toJson(issues)); setMeta("integrity_issues", gson.toJson(integrityIssues))
        setMeta("last_scan", now.toString())
        if (issues.isEmpty() && integrityIssues.isEmpty() && persistentIssues().isEmpty()) setMeta(
            "last_success",
            now.toString()
        )
    }

    fun collection(): CollectionSnapshot {
        val storedScan = decodeIssues(meta("issues") ?: "[]")
        val operational = meta("operational_issues")?.let(::decodeIssues) ?: emptySet()
        val integrity = persistentIssues() + (meta("integrity_issues")?.let(::decodeIssues) ?: storedScan)
        return CollectionSnapshot(
            homes = meta("homes")?.toInt() ?: 0,
            files = meta("files")?.toInt() ?: 0,
            issues = integrity + storedScan,
            lastSuccess = meta("last_success")?.let(Instant::parse),
            lastCompleted = meta("last_scan")?.let(Instant::parse),
            sourceProblems = sourceProblems(),
            operationalIssues = operational,
            integrityIssues = integrity,
        )
    }

    private fun sourceProblems(): List<SourceProblem> = connection.createStatement().use { query ->
        query.executeQuery("SELECT path,s.issues,h.issues FROM sources s JOIN sessions h ON h.key=s.session_key WHERE s.issues<>'[]' OR h.issues<>'[]' ORDER BY path")
            .use { rows ->
                buildList {
                    while (rows.next()) {
                        val fileName = Path.of(rows.getString(1)).fileName.toString()
                        // A rollout's filename date identifies the log, not every day of its usage.
                        // Never substitute file modification time for an unknown session date.
                        val date = Regex("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])").find(fileName)?.value?.let {
                            try {
                                LocalDate.parse(it)
                            } catch (_: java.time.DateTimeException) {
                                null
                            }
                        }
                        add(
                            SourceProblem(
                                fileName,
                                date,
                                decodeIssues(rows.getString(2)) + decodeIssues(rows.getString(3))
                            )
                        )
                    }
                }
            }
    }

    fun eventCount(): Long = stringQuery("SELECT coalesce(sum(event_count),0) FROM sessions")!!.toLong()
    fun analytics(now: Instant): UsageAnalytics = analytics.snapshot(now)
    fun earliest(): Instant? = connection.createStatement().use { sql ->
        sql.executeQuery("SELECT seconds,nanos FROM events ORDER BY seconds,nanos LIMIT 1")
            .use { if (it.next()) Instant.ofEpochSecond(it.getLong(1), it.getLong(2)) else null }
    }

    fun hasFuture(now: Instant): Boolean =
        connection.prepareStatement("SELECT 1 FROM events WHERE (seconds,nanos)>(?,?) LIMIT 1").use {
            it.setLong(1, now.epochSecond); it.setInt(2, now.nano); it.executeQuery().use { rows -> rows.next() }
        }

    fun total(intervals: List<UsageInterval>, now: Instant, checkCancelled: () -> Unit = {}): Long {
        var total = 0L
        connection.prepareStatement("SELECT COALESCE(SUM(total),0) FROM events WHERE (seconds,nanos)>=(?,?) AND (seconds,nanos)<(?,?) AND (seconds,nanos)<=(?,?)")
            .use { query ->
                for ((start, end) in intervals) {
                    checkCancelled()
                    query.setLong(1, start.epochSecond); query.setInt(2, start.nano); query.setLong(
                        3,
                        end.epochSecond
                    ); query.setInt(4, end.nano)
                    query.setLong(5, now.epochSecond); query.setInt(6, now.nano)
                    query.executeQuery().use { row -> row.next(); total = Math.addExact(total, row.getLong(1)) }
                }
            }
        return total
    }

    fun activeDates(
        definition: TrackerDefinition,
        zone: ZoneId,
        now: Instant,
        checkCancelled: () -> Unit = {}
    ): List<LocalDate> {
        val latestDate = resolver.dayOf(now, definition, zone).minusDays(if (definition.includeCurrent) 0 else 1)
        val dates = linkedSetOf<LocalDate>()
        var cutoff = minOf(now, resolver.dayInterval(latestDate, definition, zone).end.minusNanos(1))
        connection.prepareStatement("SELECT seconds,nanos FROM events WHERE (seconds,nanos)<=(?,?) ORDER BY seconds DESC,nanos DESC LIMIT 1")
            .use { latest ->
                connection.prepareStatement("SELECT 1 FROM events WHERE (seconds,nanos)>=(?,?) AND (seconds,nanos)<(?,?) AND (seconds,nanos)<=(?,?) LIMIT 1")
                    .use { exists ->
                        while (dates.size < definition.amount) {
                            checkCancelled()
                            latest.setLong(1, cutoff.epochSecond); latest.setInt(2, cutoff.nano)
                            val time = latest.executeQuery()
                                .use { if (it.next()) Instant.ofEpochSecond(it.getLong(1), it.getLong(2)) else null }
                                ?: break
                            val date = resolver.dayOf(time, definition, zone)
                            val interval = resolver.dayInterval(date, definition, zone)
                            exists.setLong(1, interval.start.epochSecond); exists.setInt(2, interval.start.nano)
                            exists.setLong(3, interval.end.epochSecond); exists.setInt(4, interval.end.nano)
                            exists.setLong(5, now.epochSecond); exists.setInt(6, now.nano)
                            if (date <= latestDate && exists.executeQuery().use { it.next() }) dates += date
                            // Seek directly to the preceding day instead of walking every event in a busy day.
                            cutoff = interval.start.minusNanos(1)
                        }
                    }
            }
        return dates.toList()
    }

    fun evaluate(
        definition: TrackerDefinition,
        now: Instant,
        zone: ZoneId,
        options: AnalyticsDisplayOptions = AnalyticsDisplayOptions(),
        checkCancelled: () -> Unit = {}
    ): TrackerResult {
        val windowMinutes = definition.mode.resetWindowMinutes
        if (windowMinutes != null) {
            val label = if (windowMinutes == 300) "five-hour" else "weekly"
            if (!QuotaEligibility.resetAligned(options.capabilities(), windowMinutes)) return TrackerResult(
                definition,
                ResolvedPeriod(emptyList(), zone), null, setOf("The selected plan has no verified fixed $label quota")
            )
            resolver.validate(definition)
            val selectedZone = definition.zoneId.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: zone
            val window = resolveResetWindow(definition, now, options, windowMinutes)
                ?: return TrackerResult(
                    definition,
                    ResolvedPeriod(emptyList(), selectedZone),
                    null,
                    setOf("No $label reset observation available for the selected quota scope")
                )
            val period = ResolvedPeriod(listOf(window.interval), selectedZone, window.issues)
            return evaluateResolved(definition, period, now, checkCancelled)
        }
        val period = resolver.resolve(definition, now, zone) { tracker, selectedZone, instant ->
            activeDates(
                tracker,
                selectedZone,
                instant,
                checkCancelled
            )
        }
        val effectivePeriod = if (definition.resetDailyAtWeeklyReset && definition.supportsDailyWeeklyReset()) {
            alignDailyPeriod(definition, period, now, options)
        } else period
        return evaluateResolved(definition, effectivePeriod, now, checkCancelled)
    }

    private fun resolveResetWindow(
        definition: TrackerDefinition,
        now: Instant,
        options: AnalyticsDisplayOptions,
        windowMinutes: Int,
    ): ResetWindow? {
        val collected = collection()
        val snapshot = UsageSnapshot(
            collection = collected,
            analytics = analytics(collected.lastCompleted ?: now),
            evaluatedAt = now
        )
        val scopeOptions = if (definition.quotaPoolId.isBlank()) options else options.copy(quotaScope = "", fiveHourScope = "")
        val reading = QuotaPresentation.select(
            QuotaPresentation.forPool(snapshot, definition.quotaPoolId), scopeOptions, windowMinutes
        ).reading
        return ResetWindowResolver.resolve(reading, now, windowMinutes)
    }

    private fun alignDailyPeriod(
        definition: TrackerDefinition,
        period: ResolvedPeriod,
        now: Instant,
        options: AnalyticsDisplayOptions,
    ): ResolvedPeriod {
        val week = if (options.capabilities().weekly) resolveResetWindow(definition, now, options, 10080) else null
        if (week == null) {
            return period.copy(issues = period.issues +
                "Daily usage uses its normal boundary because no weekly reset is available for the selected account and quota pool")
        }

        val reset = week.interval.start
        val intervals = period.intervals.map { interval ->
            if (reset > interval.start && reset < interval.end) interval.copy(start = reset) else interval
        }
        if (intervals == period.intervals) return period
        val alignment = when {
            week.scheduled -> "Daily usage starts at the scheduled weekly reset; awaiting a fresh provider observation"
            week.inferred -> "Daily usage starts at the inferred weekly reset"
            else -> "Daily usage starts at the weekly reset"
        }
        return period.copy(intervals = intervals, issues = period.issues + alignment)
    }

    private fun evaluateResolved(
        definition: TrackerDefinition,
        period: ResolvedPeriod,
        now: Instant,
        checkCancelled: () -> Unit
    ): TrackerResult = try {
        TrackerResult(definition, period, total(period.intervals, now, checkCancelled), period.issues)
    } catch (_: ArithmeticException) {
        TrackerResult(definition, period, null, period.issues + "Total exceeds supported integer range")
    } catch (failure: java.sql.SQLException) {
        if (failure.message?.contains("integer overflow") != true) throw failure
        TrackerResult(definition, period, null, period.issues + "Total exceeds supported integer range")
    }

    fun budgetEstimates(
        analytics: UsageAnalytics,
        issues: Set<String>,
        capabilities: PlanCapabilities = AnalyticsDisplayOptions().capabilities(),
    ): Map<String, QuotaBudgetEstimate> =
        analytics.quotaObservations.filter { QuotaEligibility.budget(capabilities, it.usage.windowMinutes) }
            .groupBy(QuotaPresentation::scope).mapValues { (_, readings) ->
                val reading = readings.maxBy { it.time }
                val window = ResetWindowResolver.forBudget(reading)
                val tokens = try {
                    window?.let { total(listOf(it.interval), reading.time) }
                } catch (_: ArithmeticException) {
                    null
                } catch (failure: java.sql.SQLException) {
                    if (failure.message?.contains("integer overflow") != true) throw failure
                    null
                }
                QuotaBudgetEstimate.calculate(reading, tokens, window, issues)
            }

    /** Daily and weekly totals in one consistent database read snapshot. */
    fun <T> readSnapshot(block: () -> T): T = transaction(block)

    private fun persistentIssues(): Set<String> = connection.createStatement().use { query ->
        query.executeQuery("SELECT issues FROM sessions WHERE issues<>'[]' UNION SELECT issues FROM sources WHERE issues<>'[]'")
            .use { rows ->
                buildSet { while (rows.next()) addAll(decodeIssues(rows.getString(1))) }
            }
    }

    private fun decodeIssues(text: String): Set<String> =
        JsonParser.parseString(text).asJsonArray.map { it.asString }.toSet()

    private fun meta(key: String): String? = stringQuery("SELECT value FROM meta WHERE key=?", key)
    private fun setMeta(key: String, value: String) =
        connection.prepareStatement("INSERT INTO meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .use {
                it.setString(1, key); it.setString(2, value); it.executeUpdate()
            }

    private fun stringQuery(sql: String, vararg args: String): String? = connection.prepareStatement(sql).use { query ->
        args.forEachIndexed { index, value -> query.setString(index + 1, value) }
        query.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    private fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "Nested usage transaction" }
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            try {
                connection.rollback()
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    companion object {
        private fun openConnection(path: Path, readOnly: Boolean): Connection {
            if (!readOnly) Files.createDirectories(path.toAbsolutePath().parent)
            val properties = SQLiteConfig().apply { setReadOnly(readOnly) }.toProperties()
            return JDBC().connect("jdbc:sqlite:${path.toAbsolutePath()}", properties)
        }

        const val SCHEMA_VERSION = 4
        private const val AMBIGUOUS_IDENTITY = "Session identity missing; ambiguous copy excluded"
        private const val PARSER_VERSION = 3
        private fun hash(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

        fun releaseDriver() {
            DriverManager.getDrivers().toList().filter { it.javaClass.classLoader == JDBC::class.java.classLoader }
                .forEach(DriverManager::deregisterDriver)
        }
    }
}
