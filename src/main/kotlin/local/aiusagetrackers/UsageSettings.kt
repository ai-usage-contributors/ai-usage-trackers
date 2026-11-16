package local.aiusagetrackers

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import javax.swing.JComponent

@Service(Service.Level.APP)
@State(name = "CodexDailyTokens", storages = [Storage("codexDailyTokens.xml")])
class UsageSettings : PersistentStateComponent<UsageSettings.SettingsState> {
    class SettingsState {
        var ribbonWidths: RibbonWidthStatistics = RibbonWidthStatistics()
        var widgetAnchor: String = ""
        var previewPinned: Boolean = false
        var trackerLimit: Int = 5
        var showLastRequest: Boolean = false
        var analyticsDisplay: AnalyticsDisplayOptions = AnalyticsDisplayOptions()
        var extraHomes: MutableList<String> = mutableListOf()
        var trackers: MutableList<TrackerDefinition> = TrackerDefinition.defaults().toMutableList()
        var display: DisplayPreferences = DisplayPreferences()
        var providers: ProviderConfiguration = ProviderConfiguration()
    }

    @Volatile
    private var stored = SettingsState().also { it.analyticsDisplay.planProfile = QuotaPlanProfile.CODEX_WEEKLY }
    val ribbonWidths: RibbonWidthStatistics get() = stored.ribbonWidths.copy()
    fun setPreviewPinned(value: Boolean) { stored = copy(stored).also { it.previewPinned = value } }
    fun recordRibbonWidth(width: Int, value: String) { stored.ribbonWidths.record(width, value) }
    val trackerLimit: Int get() = stored.trackerLimit
    val showLastRequest: Boolean get() = stored.showLastRequest
    val analyticsDisplay: AnalyticsDisplayOptions get() = stored.analyticsDisplay.copy()
    val extraHomes: List<String> get() = stored.extraHomes.toList()
    val trackers: List<TrackerDefinition> get() = stored.trackers.map { it.copy() }
    val display: DisplayPreferences get() = stored.display.copy()
    val providers: ProviderConfiguration get() = stored.providers.isolatedCopy()
    fun selectVendor(vendor: UsageVendor) {
        val config = providers
        require(vendor in config.eligible())
        config.display.selected = vendor
        stored = copy(stored).also { it.providers = config }
    }
    fun toggleCarouselVendor(vendor: UsageVendor) {
        val config = providers
        config.display = config.display.toggle(vendor, config.eligible())
        stored = copy(stored).also { it.providers = config }
    }
    override fun getState(): SettingsState = copy(stored)
    override fun loadState(state: SettingsState) {
        stored = copy(state)
    }

    fun update(homes: List<String>, trackers: List<TrackerDefinition>, display: DisplayPreferences = this.display, trackerLimit: Int = this.trackerLimit, showLastRequest: Boolean = this.showLastRequest, analyticsDisplay: AnalyticsDisplayOptions = this.analyticsDisplay, providers: ProviderConfiguration = this.providers) {
        stored = SettingsState().also {
            it.ribbonWidths = stored.ribbonWidths.copy()
            it.widgetAnchor = stored.widgetAnchor
            it.previewPinned = stored.previewPinned
            it.extraHomes = homes.toMutableList(); it.trackers = trackers.map(TrackerDefinition::sharedPresentation).toMutableList()
            it.display = display.normalized()
            it.trackerLimit = trackerLimit.coerceIn(1, 32)
            it.showLastRequest = showLastRequest
            it.analyticsDisplay = analyticsDisplay.normalized()
            it.providers = providers.reconciled()
            it.providers.profiles.firstOrNull { p -> p.id == ProviderProfile.LEGACY_CODEX }?.analytics = it.analyticsDisplay.copy()
        }
    }

    private fun copy(state: SettingsState) = SettingsState().also { result ->
        result.ribbonWidths = state.ribbonWidths.copy()
        result.widgetAnchor = state.widgetAnchor
        result.previewPinned = state.previewPinned
        result.showLastRequest = state.showLastRequest
        result.analyticsDisplay = state.analyticsDisplay.normalized()
        result.trackerLimit = maxOf(state.trackerLimit.coerceIn(1, 32), state.trackers.size.coerceAtMost(32))
        result.extraHomes = state.extraHomes.toMutableList()
        result.trackers = state.trackers.map(TrackerDefinition::sharedPresentation).toMutableList()
        result.display = state.display.normalized()
        result.providers = state.providers.isolatedCopy()
        result.providers.profiles.firstOrNull { it.id == ProviderProfile.LEGACY_CODEX }?.analytics = state.analyticsDisplay.copy()
    }
}

class UsageConfigurable : Configurable, Configurable.NoScroll {
    private var editor: TrackerSettingsPanel? = null
    override fun getDisplayName(): String = "AI Usage Trackers"
    override fun createComponent(): JComponent = TrackerSettingsPanel(service<UsageSettings>().ribbonWidths).also { editor = it; reset() }
    override fun isModified(): Boolean {
        val panel = editor ?: return false
        val settings = service<UsageSettings>()
        return panel.usageAgreement() != service<UsageConsent>().accepted || panel.providers() != settings.providers || panel.analyticsDisplay() != settings.analyticsDisplay || panel.showLastRequest() != settings.showLastRequest || panel.trackerLimit() != settings.trackerLimit || panel.homes() != settings.extraHomes || panel.trackers() != settings.trackers || panel.display() != settings.display
    }

    override fun apply() {
        val panel = editor ?: return
        val trackers = panel.trackers()
        try {
            panel.validateDraft()
        } catch (failure: Exception) {
            throw ConfigurationException(failure.message ?: "Invalid tracker")
        }
        service<UsageSettings>().update(panel.homes(), trackers, panel.display(), panel.trackerLimit(), panel.showLastRequest(), panel.analyticsDisplay(), panel.providers())
        service<UsageConsent>().setAccepted(panel.usageAgreement())
        service<UsageService>().refresh()
    }

    override fun reset() {
        val settings = service<UsageSettings>()
        editor?.reset(settings.extraHomes, settings.trackers, settings.display, settings.trackerLimit, settings.showLastRequest, settings.analyticsDisplay)
        editor?.resetProviders(settings.providers)
        editor?.resetUsageAgreement(service<UsageConsent>().accepted)
    }

    override fun disposeUIResources() {
        editor?.close(); editor = null
    }
}
