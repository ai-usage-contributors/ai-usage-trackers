package local.aiusagetrackers

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.APP)
class UsageService(private val scope: CoroutineScope) {
    internal var ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO
    private val databasePath get() = PathManager.getConfigDir().resolve("codexUsage/usage.sqlite")
    private val settings get() = service<UsageSettings>()
    private val consent get() = service<UsageConsent>()
    private val monitors = java.util.concurrent.ConcurrentHashMap<String, UsageMonitor>()
    private val accounts = MutableStateFlow<Map<String, UsageSnapshot>>(emptyMap())
    fun snapshot(accountId: String): UsageSnapshot = if (!consent.accepted)
        UsageSnapshot(agreementRequired = true, loading = false, issues = setOf(UsageConsent.REQUIRED_MESSAGE))
        else accounts.value[accountId] ?: UsageSnapshot()
    fun profilePath(profile: ProviderProfile): Path {
        require(profile.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid account reference" }
        return if (profile.id == ProviderProfile.LEGACY_CODEX && profile.vendor == UsageVendor.CODEX) databasePath
        else databasePath.parent.resolve("accounts").resolve("${profile.vendor.name.lowercase()}-${profile.id}.sqlite")
    }

    init {
        scope.launch(ioDispatcher) {
            val jobs = mutableMapOf<String, Job>()
            try {
                while (isActive) {
                    val config = settings.providers
                    synchronized(accounts) { accounts.value = accounts.value.filterKeys { id -> config.profiles.any { it.id == id } } }
                    val collecting = if (consent.accepted) config.collecting().associateBy { it.id } else emptyMap()
                    if (!consent.accepted) accounts.value = emptyMap()
                    for (id in jobs.keys.toList()) if (id !in collecting) {
                        jobs.remove(id)?.cancelAndJoin(); monitors.remove(id)
                    }
                    for ((id, initial) in collecting) if (id !in jobs) {
                        val job = SupervisorJob(currentCoroutineContext()[Job])
                        val accountScope = CoroutineScope(currentCoroutineContext() + job)
                        fun profile() = settings.providers.profiles.firstOrNull { it.id == id } ?: initial
                        val monitor = UsageMonitor(
                            accountScope, Clock.systemUTC(), ZoneId::systemDefault,
                            { profilePath(initial) }, { settings.trackers.filter { it.accountId == id } },
                            {
                                CodexHomeDiscovery().discover(
                                    if (id == ProviderProfile.LEGACY_CODEX) settings.extraHomes + profile().paths()
                                        .map(Path::toString) else profile().paths().map(Path::toString)
                                )
                            },
                            { if (id == ProviderProfile.LEGACY_CODEX) settings.analyticsDisplay else profile().analytics },
                            customCollect = { database, now, completedAt, cancelled ->
                                val p = profile()
                                database.bindAccount(p)
                                if (p.vendor == UsageVendor.CODEX) CodexUsageCollector(database).collect(
                                    if (id == ProviderProfile.LEGACY_CODEX) CodexHomeDiscovery().discover(
                                        settings.extraHomes + p.paths().map(Path::toString)
                                    )
                                    else Discovery(p.paths().toSet(), emptySet()), now, completionTime = completedAt, checkCancelled = cancelled
                                )
                                else ProviderUsageCollector(database).collect(p, now, cancelled, completedAt)
                            }, releaseDriverOnClose = false, allowed = { consent.accepted }
                        )
                        monitors[id] = monitor
                        jobs[id] = job
                        accountScope.launch {
                            monitor.snapshots.collect { value ->
                                synchronized(accounts) { accounts.value += id to value }
                            }
                        }
                    }
                    delay(250.milliseconds)
                }
            } finally {
                jobs.values.forEach { it.cancel() }; UsageDatabase.releaseDriver()
            }
        }
    }

    fun refresh() {
        monitors.values.forEach { it.refresh() }
    }

    fun acknowledgeAccount(accountId: String, page: String, issues: Set<String>, callback: (Boolean) -> Unit) {
        val modality = ModalityState.current()
        scope.launch(ioDispatcher) {
            val saved = try {
                check(consent.accepted) { UsageConsent.REQUIRED_MESSAGE }
                val profile = settings.providers.profiles.firstOrNull { it.id == accountId }
                    ?: error("Account no longer configured")
                UsageDatabase(profilePath(profile)).use { it.acknowledgeIssues(page, issues) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            ApplicationManager.getApplication().invokeLater({ callback(saved) }, modality)
        }
    }

    fun preview(
        definition: TrackerDefinition,
        preferences: DisplayPreferences,
        draftOptions: AnalyticsDisplayOptions?,
        callback: (String) -> Unit,
    ): Job {
        val modality = ModalityState.current()
        return scope.launch(ioDispatcher) {
            val text = try {
                check(consent.accepted) { UsageConsent.REQUIRED_MESSAGE }
                UsagePeriodResolver().validate(definition)
                val context = currentCoroutineContext()
                val profile = settings.providers.profiles.firstOrNull { it.id == definition.accountId }
                    ?: error("Select a configured account binding")
                UsageDatabase(profilePath(profile), true).use { reader ->
                    reader.readSnapshot {
                        val now = Clock.systemUTC().instant()
                        PeriodPresentation.describe(
                            reader.evaluate(
                                definition,
                                now,
                                ZoneId.systemDefault(),
                                draftOptions ?: if (profile.id == ProviderProfile.LEGACY_CODEX) settings.analyticsDisplay else profile.analytics
                            ) { context.ensureActive() }, now, preferences
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Preview unavailable: ${failure.message ?: "database is not ready"}"
            }
            ApplicationManager.getApplication().invokeLater({ callback(text) }, modality)
        }
    }
}

internal class UsageMonitor(
    scope: CoroutineScope,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val path: () -> Path,
    private val definitions: () -> List<TrackerDefinition>,
    private val discover: () -> Discovery,
    private val analyticsOptions: () -> AnalyticsDisplayOptions,
    private val customCollect: ((UsageDatabase, Instant, () -> Instant, () -> Unit) -> Unit)? = null,
    private val releaseDriverOnClose: Boolean = true,
    ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    private val allowed: () -> Boolean = { true },
) {
    private class CompletedAnalytics {
        var key: Triple<Instant?, Long, QuotaPlanProfile>? = null
        var analytics = UsageAnalytics()
        var budgets: Map<String, QuotaBudgetEstimate> = emptyMap()
    }

    constructor(
        scope: CoroutineScope, clock: Clock, zone: () -> ZoneId, path: () -> Path,
        definitions: () -> List<TrackerDefinition>, discover: () -> Discovery
    ) :
            this(scope, clock, zone, path, definitions, discover, { AnalyticsDisplayOptions() })

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableSnapshots = MutableStateFlow(UsageSnapshot())
    val snapshots = mutableSnapshots.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            try {
                while (isActive) {
                    try {
                        if (!allowed()) { delay(250.milliseconds); continue }
                        val location = path()
                        UsageDatabase(location).use { writer ->
                            coroutineScope {
                                val scanIssues = MutableStateFlow<Set<String>>(emptySet())
                                val importing = MutableStateFlow(true)
                                val completedScans = MutableStateFlow(0L)
                                launch {
                                    while (isActive) {
                                        delay(30.seconds); refresh()
                                    }
                                }
                                launch {
                                    val collector = CodexUsageCollector(writer)
                                    requests.trySend(Unit)
                                    while (isActive) {
                                        requests.receive()
                                        checkAgreement()
                                        importing.value = true
                                        val context = currentCoroutineContext()
                                        try {
                                            if (customCollect != null) customCollect.invoke(
                                                writer,
                                                clock.instant(),
                                                clock::instant
                                            ) { context.ensureActive(); checkAgreement() }
                                            else collector.collect(
                                                discover(),
                                                clock.instant(),
                                                completionTime = clock::instant
                                            ) { context.ensureActive(); checkAgreement() }
                                            completedScans.value++
                                            scanIssues.value = emptySet()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            scanIssues.value =
                                                setOf("Collection failed; persisted readings retained; retry pending")
                                        } finally {
                                            importing.value = false
                                        }
                                    }
                                }
                                UsageDatabase(location, true).use { reader ->
                                    val completed = CompletedAnalytics()
                                    while (isActive) {
                                        checkAgreement()
                                        val context = currentCoroutineContext()
                                        val now = clock.instant()
                                        val selectedZone = zone()
                                        val trackers = definitions().map { it.copy() }
                                        val options = analyticsOptions()
                                        mutableSnapshots.value = reader.readSnapshot {
                                            val collection = reader.collection()
                                            val refreshKey = Triple(collection.lastCompleted, completedScans.value, options.planProfile)
                                            if (completed.key != refreshKey) {
                                                completed.analytics =
                                                    collection.lastCompleted?.let(reader::analytics) ?: UsageAnalytics()
                                                completed.budgets = reader.budgetEstimates(
                                                    completed.analytics, collection.operationalIssues, options.capabilities()
                                                )
                                                completed.key = refreshKey
                                            }
                                            val analytics = completed.analytics
                                            val results = trackers.map { tracker ->
                                                context.ensureActive()
                                                try {
                                                    reader.evaluate(
                                                        tracker,
                                                        now,
                                                        selectedZone, options
                                                    ) { context.ensureActive() }
                                                } catch (failure: IllegalArgumentException) {
                                                    TrackerResult(
                                                        tracker,
                                                        ResolvedPeriod(emptyList(), selectedZone),
                                                        null,
                                                        setOf(failure.message ?: "Invalid tracker")
                                                    )
                                                } catch (failure: DateTimeException) {
                                                    TrackerResult(
                                                        tracker, ResolvedPeriod(emptyList(), selectedZone), null,
                                                        setOf(failure.message ?: "Invalid tracker date or time zone")
                                                    )
                                                }
                                            }
                                            UsageSnapshot(
                                                results,
                                                collection,
                                                buildSet {
                                                    addAll(collection.issues); addAll(analytics.issues); addAll(
                                                    scanIssues.value
                                                )
                                                    if (importing.value) add("Import in progress; totals may increase")
                                                    if (reader.hasFuture(now)) add("Future-dated usage excluded")
                                                },
                                                importing.value && reader.eventCount() == 0L,
                                                reader.eventCount(),
                                                now,
                                                location.toString(),
                                                reader.earliest(),
                                                analytics,
                                                completed.budgets,
                                                operationalIssues = buildSet {
                                                    addAll(collection.operationalIssues); addAll(analytics.operationalIssues); addAll(
                                                    scanIssues.value
                                                )
                                                    if (importing.value) add("Import in progress; totals may increase")
                                                },
                                                acknowledgedWarnings = reader.acknowledgedIssues("warnings"),
                                                acknowledgedIntegrity = reader.acknowledgedIssues("integrity"),
                                                integrityIssues = buildSet {
                                                    addAll(collection.integrityIssues); addAll(analytics.integrityIssues)
                                                    if (reader.hasFuture(now)) add("Future-dated usage excluded")
                                                },
                                            )
                                        }
                                        delay(250.milliseconds)
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableSnapshots.value = mutableSnapshots.value.copy(
                            loading = false,
                            issues = setOf("Usage database unavailable; existing history preserved. Check the storage location and restart or refresh."),
                            operationalIssues = setOf("Usage database unavailable; existing history preserved"),
                            databasePath = path().toString()
                        )
                        delay(5.seconds)
                    }
                }
            } finally {
                if (releaseDriverOnClose) UsageDatabase.releaseDriver()
            }
        }
    }

    fun refresh() {
        requests.trySend(Unit)
    }

    private fun checkAgreement() { check(allowed()) { UsageConsent.REQUIRED_MESSAGE } }
}
