package local.aiusagetrackers


import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.time.Instant
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val LAST_REQUEST = "Last request"
private const val WEEKLY_RESET = "Weekly reset"
private const val FIVE_HOUR_RESET = "5h reset"
private const val FIVE_HOUR_QUOTA = "5h quota"
private const val PERCENTAGE_LABEL = "Percentage label"
private const val WEEKLY_QUOTA = "Weekly quota"
private const val HIDDEN_LABEL_STYLE = "|true|"
private const val RESET_LABEL = "Reset label"

/** Reads cached snapshots only; sample states never start collection. */
class AnalyticsSettingsPanel(private val snapshot: () -> UsageSnapshot) : SettingsForm(),
    AutoCloseable {
    private data class ScopeChoice(val id: String, val label: String) {
        override fun toString(): String = label
    }

    private var updating = false
    val isUpdating: Boolean get() = updating
    private var closed = false
    val planProfile = JComboBox(QuotaPlanProfile.entries.toTypedArray()).apply { renderer = providerChoiceRenderer() }
    private var lastValidPlan = planProfile.getItemAt(0)
    private val weeklyQuotaLabel = JTextField(WEEKLY_QUOTA)
    private val weeklyResetLabel = JTextField(WEEKLY_RESET)
    private val fiveHourQuotaLabel = JTextField(FIVE_HOUR_QUOTA)
    private val fiveHourResetLabel = JTextField(FIVE_HOUR_RESET)
    private val requestLabel = JTextField(LAST_REQUEST)
    private val quota = JCheckBox("Show weekly quota")
    val colorWeeklyQuota = JCheckBox("Color weekly quota")
    private val reset = JCheckBox("Show weekly reset")
    private val fiveQuota = JCheckBox("Show 5h quota")
    private val fiveReset = JCheckBox("Show 5h reset")
    private val reportedPools = JCheckBox("Show reported pools with unknown boundary schemes")
    private val estimate = JCheckBox("Estimate weekly token budget")
    private val requestPercent = JCheckBox("Show percentage used for Last request")
    private var display = DisplayPreferences()
    private val warnings = List(4) { JLabel() }
    private val percentMode = JComboBox(QuotaPercentMode.entries.toTypedArray())
    private val source = JComboBox<ScopeChoice>()
    private val fiveSource = JComboBox<ScopeChoice>()
    val previewState =
        JComboBox(arrayOf("Collected snapshot", "Sample totals", "Unavailable", "Loading", "Stale", "Expired"))
    val preview = JPanel(FlowLayout(FlowLayout.LEADING, 4, 2))
    val requestSettings = SettingsForm()
    private val styles = listOf(
        LAST_REQUEST to "tokens", WEEKLY_QUOTA to "percent used", WEEKLY_RESET to "seconds",
        FIVE_HOUR_QUOTA to "percent used", FIVE_HOUR_RESET to "seconds"
    ).associate { (name, _) ->
        name to JCheckBox("Hide label").apply { addActionListener { updatePreview() } }
    }
    private val refreshTimer = Timer(1000) { refreshAvailability() }

    init {
        border = JBUI.Borders.emptyBottom(8)
        fun row(label: String, control: JComponent, help: String) {
            control.toolTipText = PluginTooltips.concise(help)
            val target = if (label.startsWith(LAST_REQUEST)) requestSettings else this
            target.addRow(JLabel(label).apply { labelFor = control; toolTipText = control.toolTipText }, control)
        }
        row("Last request label", requestLabel, "Status label, 1–80 characters. Coverage stays in Usage Info.")
        row(
            "Last request percentage",
            requestPercent,
            "Append its approximate share of the selected weekly token budget, without spaces. Independent of the quota used/remaining setting."
        )
        fun withWarning(control: JCheckBox, warning: JLabel): JPanel = JPanel(BorderLayout(4, 0)).apply {
            add(control, BorderLayout.CENTER); add(warning, BorderLayout.EAST)
        }
        section("Plan capabilities")
        row("Quota scheme", planProfile, "Choose the quota limits confirmed for this account.")
        row("Other quota pools", reportedPools, "Show quota pools whose reset schedule is not verified.")
        planProfile.addActionListener {
            (planProfile.selectedItem as? QuotaPlanProfile)?.let { lastValidPlan = it }
            if (!updating) refreshAvailability()
        }
        reportedPools.addActionListener { if (!updating) refreshAvailability() }
        section(WEEKLY_QUOTA)
        row("Percentage", withWarning(quota, warnings[0]), "Show the latest recorded weekly quota percentage.")
        row(PERCENTAGE_LABEL, weeklyQuotaLabel, "Name shown beside the weekly percentage.")
        addRow(JLabel(PERCENTAGE_LABEL), styles.getValue(WEEKLY_QUOTA))
        row("Reset time", withWarning(reset, warnings[1]), "Show time until reset, updated after each collection.")
        row(RESET_LABEL, weeklyResetLabel, "Name shown beside the weekly reset time.")
        addRow(JLabel(RESET_LABEL), styles.getValue(WEEKLY_RESET))
        row("Quota data", source, "Use the newest reading or choose a specific local source.")
        section("Five-hour quota")
        row(
            "Percentage",
            withWarning(fiveQuota, warnings[2]),
            "Show the recorded five-hour quota percentage when available."
        )
        row(PERCENTAGE_LABEL, fiveHourQuotaLabel, "Name shown beside the five-hour percentage.")
        addRow(JLabel(PERCENTAGE_LABEL), styles.getValue(FIVE_HOUR_QUOTA))
        row("Reset time", withWarning(fiveReset, warnings[3]), "Show time until reset, updated after each collection.")
        row(RESET_LABEL, fiveHourResetLabel, "Name shown beside the five-hour reset time.")
        addRow(JLabel(RESET_LABEL), styles.getValue(FIVE_HOUR_RESET))
        row("Quota data", fiveSource, "Use the newest reading or choose a specific local source.")
        listOf(source, fiveSource).forEach { combo ->
            combo.renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
            combo.addActionListener { if (!updating) refreshAvailability() }
        }
        section("Percentage display")
        row("Show", percentMode, "Choose used or remaining for reported quota percentages.")
        section("Token budget estimate")
        row("Weekly budget", estimate, "Estimate from local usage after at least 1% is used.")
        addRow(JLabel(""), JLabel("An estimate, not a provider allowance. Incomplete logs can affect it."))
        requestSettings.addRow(JLabel("Last request label"), styles.getValue(LAST_REQUEST))
        listOf(
            requestLabel,
            weeklyQuotaLabel,
            weeklyResetLabel,
            fiveHourQuotaLabel,
            fiveHourResetLabel
        ).forEach { field ->
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updatePreview()
                override fun removeUpdate(e: DocumentEvent) = updatePreview()
                override fun changedUpdate(e: DocumentEvent) = updatePreview()
            })
        }
        listOf(percentMode, previewState).forEach { it.addActionListener { updatePreview() } }
        listOf(
            quota,
            colorWeeklyQuota,
            reset,
            fiveQuota,
            fiveReset,
            estimate,
            requestPercent,
            reportedPools
        ).forEach { it.addActionListener { updatePreview() } }
        addHierarchyListener {
            if (it.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (isShowing && !closed) {
                    refreshAvailability(); refreshTimer.start()
                } else refreshTimer.stop()
            }
        }
    }

    fun validateDraft() {
        listOf(
            weeklyQuotaLabel,
            weeklyResetLabel,
            fiveHourQuotaLabel,
            fiveHourResetLabel,
            requestLabel
        ).forEach { field ->
            validateSetting(field) {
                val text = field.text.trim()
                require(text.isNotBlank() && text.length <= 80 && text.none(Char::isISOControl)) {
                    "Status labels must contain 1–80 characters without control characters"
                }
            }
        }
        validateSetting(source) { options().validate() }
    }

    fun options(): AnalyticsDisplayOptions = AnalyticsDisplayOptions(
        lastRequestLabel = requestLabel.text.trim(),
        weeklyQuotaLabel = weeklyQuotaLabel.text.trim(),
        weeklyResetLabel = weeklyResetLabel.text.trim(),
        fiveHourQuotaLabel = fiveHourQuotaLabel.text.trim(),
        fiveHourResetLabel = fiveHourResetLabel.text.trim(),
        showQuota = quota.isSelected,
        colorWeeklyQuota = colorWeeklyQuota.isSelected,
        showReset = reset.isSelected,
        quotaPercentMode = percentMode.selectedItem as QuotaPercentMode,
        quotaScope = (source.selectedItem as? ScopeChoice)?.id.orEmpty(),
        showFiveHourQuota = fiveQuota.isSelected,
        showFiveHourReset = fiveReset.isSelected,
        fiveHourScope = (fiveSource.selectedItem as? ScopeChoice)?.id.orEmpty(),
        estimateBudget = estimate.isSelected,
        lastRequestPercentUsed = requestPercent.isSelected,
        lastRequestStyle = if (styles.getValue(LAST_REQUEST).isSelected) HIDDEN_LABEL_STYLE else "",
        weeklyQuotaStyle = if (styles.getValue(WEEKLY_QUOTA).isSelected) HIDDEN_LABEL_STYLE else "",
        weeklyResetStyle = if (styles.getValue(WEEKLY_RESET).isSelected) HIDDEN_LABEL_STYLE else "",
        fiveHourQuotaStyle = if (styles.getValue(FIVE_HOUR_QUOTA).isSelected) HIDDEN_LABEL_STYLE else "",
        fiveHourResetStyle = if (styles.getValue(FIVE_HOUR_RESET).isSelected) HIDDEN_LABEL_STYLE else "",
        planProfile = selectedPlan(),
        showReportedPools = reportedPools.isSelected,
    )

    private fun selectedPlan(): QuotaPlanProfile =
        (planProfile.selectedItem as? QuotaPlanProfile)?.also { lastValidPlan = it } ?: lastValidPlan

    private inline fun editOptions(edit: () -> Unit) {
        val previouslyUpdating = updating
        updating = true
        try {
            edit()
        } finally {
            updating = previouslyUpdating
        }
        if (!updating) refreshAvailability()
    }

    fun reset(options: AnalyticsDisplayOptions) = editOptions {
        planProfile.selectedItem = options.planProfile
        reportedPools.isSelected = options.showReportedPools
        weeklyQuotaLabel.text = options.weeklyQuotaLabel
        weeklyResetLabel.text = options.weeklyResetLabel
        fiveHourQuotaLabel.text = options.fiveHourQuotaLabel
        fiveHourResetLabel.text = options.fiveHourResetLabel
        colorWeeklyQuota.isSelected = options.colorWeeklyQuota
        requestLabel.text = options.lastRequestLabel; quota.isSelected = options.showQuota; reset.isSelected =
        options.showReset
        fiveQuota.isSelected = options.showFiveHourQuota; fiveReset.isSelected = options.showFiveHourReset
        estimate.isSelected = options.estimateBudget; percentMode.selectedItem = options.quotaPercentMode
        requestPercent.isSelected = options.lastRequestPercentUsed
        for ((combo, pin) in listOf(source to options.quotaScope, fiveSource to options.fiveHourScope)) {
            val choice = ScopeChoice(pin, pin.ifEmpty { "Automatic — newest observation" })
            updateSettingsChoices(combo, listOf(choice), choice)
        }
        listOf(
            LAST_REQUEST to options.lastRequestStyle, WEEKLY_QUOTA to options.weeklyQuotaStyle,
            WEEKLY_RESET to options.weeklyResetStyle, FIVE_HOUR_QUOTA to options.fiveHourQuotaStyle,
            FIVE_HOUR_RESET to options.fiveHourResetStyle
        ).forEach { (name, spec) -> styles.getValue(name).isSelected = StatusValueStyle.forDisplay(spec).hideLabel }
    }

    private fun refreshAvailability() {
        if (updating || closed) return
        val data = snapshot()
        refreshScopes(data)
        val options = options()
        val capabilities = options.capabilities()
        val weekly = capabilities.weekly
        val five = capabilities.fiveHour
        listOf(
            quota, reset, weeklyQuotaLabel, weeklyResetLabel, source,
            styles.getValue(WEEKLY_QUOTA), styles.getValue(WEEKLY_RESET)
        ).forEach { it.isEnabled = weekly }
        listOf(
            fiveQuota, fiveReset, fiveHourQuotaLabel, fiveHourResetLabel, fiveSource,
            styles.getValue(FIVE_HOUR_QUOTA), styles.getValue(FIVE_HOUR_RESET)
        ).forEach { it.isEnabled = five }
        colorWeeklyQuota.isEnabled = weekly
        refreshMetricControls(capabilities)
        estimate.isEnabled = QuotaEligibility.budget(capabilities)
        requestPercent.isEnabled = QuotaEligibility.budget(capabilities)
        SettingsHelp.attach(
            estimate, if (estimate.isEnabled) "Estimate a weekly token budget from local usage and quota readings."
            else "Available only for Codex plans covered by the token-budget estimation policy."
        )
        SettingsHelp.attach(
            requestPercent,
            if (requestPercent.isEnabled) "Show this request's approximate share of the weekly token budget."
            else "Available only for Codex plans covered by the token-budget estimation policy."
        )
        percentMode.isEnabled = weekly || five || options.showReportedPools
        refreshWarnings(data, options, capabilities)
        updatePreview()
    }

    private fun refreshWarnings(data: UsageSnapshot, options: AnalyticsDisplayOptions, capabilities: PlanCapabilities) {
        listOf(10080, 300).forEachIndexed { index, duration ->
            val applicable = QuotaEligibility.window(capabilities, duration)
            val reading = QuotaPresentation.select(data, options, duration)
            listOf(false, true).forEachIndexed { offset, isReset ->
                val message = if (applicable) reading.warning(isReset) else null
                warnings[index * 2 + offset].apply {
                    icon = if (message == null) null else AllIcons.General.Warning
                    toolTipText =
                        if (message == null) null else "Review quota availability and freshness on the usage details warnings page."
                    accessibleContext.accessibleName = message ?: "Quota data available"
                }
            }
        }
    }

    private fun refreshScopes(data: UsageSnapshot) {
        for ((combo, duration) in listOf(source to 10080, fiveSource to 300)) {
            val selected = (combo.selectedItem as? ScopeChoice)?.id.orEmpty()
            val scopes = data.analytics.quotaObservations.filter { it.usage.windowMinutes == duration }
                .map(QuotaPresentation::scope).distinct()
            val choices = listOf(ScopeChoice("", "Automatic — newest observation")) +
                    (scopes + listOfNotNull(selected.takeIf { it.isNotEmpty() })).distinct().sorted().map {
                        ScopeChoice(it, it + if (it !in scopes) " (unavailable)" else "")
                    }
            if ((0 until combo.itemCount).map(combo::getItemAt) != choices) {
                updating = true
                try {
                    updateSettingsChoices(combo, choices, choices.first { it.id == selected })
                } finally {
                    updating = false
                }
            }
        }
    }

    private fun refreshMetricControls(capabilities: PlanCapabilities) {
        for ((duration, percentageControls, resetControls) in listOf(
            Triple(
                10080,
                listOf(quota, weeklyQuotaLabel, styles.getValue(WEEKLY_QUOTA), colorWeeklyQuota),
                listOf(reset, weeklyResetLabel, styles.getValue(WEEKLY_RESET))
            ),
            Triple(
                300,
                listOf(fiveQuota, fiveHourQuotaLabel, styles.getValue(FIVE_HOUR_QUOTA)),
                listOf(fiveReset, fiveHourResetLabel, styles.getValue(FIVE_HOUR_RESET))
            )
        )) {
            for ((controls, resetMetric) in listOf(percentageControls to false, resetControls to true)) {
                val decision = QuotaEligibility.metric(capabilities, duration, resetMetric)
                controls.forEach {
                    it.isEnabled = decision.enabled
                    if (!decision.enabled) SettingsHelp.attach(it, decision.reason)
                    else SettingsHelp.attach(
                        it, when (it) {
                            quota, fiveQuota -> "Show the latest recorded quota percentage."
                            reset, fiveReset -> "Show the time remaining until the recorded reset."
                            colorWeeklyQuota -> "Color the weekly quota using the shared palette."
                            weeklyQuotaLabel, weeklyResetLabel, fiveHourQuotaLabel, fiveHourResetLabel -> "Name shown beside this quota value."
                            else -> "Hide the label while keeping the quota value visible."
                        }
                    )
                }
            }
        }
    }

    fun updateDisplay(preferences: DisplayPreferences) {
        display = preferences.normalized()
        updatePreview()
    }

    fun vendor(vendor: UsageVendor) = editOptions {
        val choices = QuotaPlanProfile.forVendor(vendor)
        updateSettingsChoices(planProfile, choices, selectedPlan().takeIf { it in choices } ?: QuotaPlanProfile.UNKNOWN)
    }

    /** Install an account's final scheme before exposing its replacement model to listeners. */
    fun account(vendor: UsageVendor, options: AnalyticsDisplayOptions) = editOptions {
        val choices = QuotaPlanProfile.forVendor(vendor)
        require(options.planProfile in choices) { "The quota scheme belongs to another vendor" }
        updateSettingsChoices(planProfile, choices, options.planProfile)
        reset(options)
    }

    private fun updatePreview() {
        if (updating || closed) return
        val options = options()
        val data = if (previewState.selectedIndex == 0) snapshot() else sampleSnapshot(previewState.selectedIndex)
        preview.removeAll()
        val effective =
            if (previewState.selectedIndex == 0) options else options.copy(quotaScope = "", fiveHourScope = "")
        WidgetText.values(data, display, showLastRequest = true, options = effective)
            .filter { it.id != "state" || data.loading }
            .forEach { value ->
                preview.add(JLabel().apply {
                    font = WidgetAppearance.font(display, font)
                    putClientProperty("html.disable", !WidgetAppearance.usesHtml(value))
                    text = WidgetAppearance.text(value, foreground)
                    accessibleContext.accessibleName = value.accessibleText
                    toolTipText = "Preview quota values using the current draft settings and selected sample state."
                })
            }
        preview.revalidate(); preview.repaint()
    }

    override fun close() {
        closed = true; refreshTimer.stop()
    }

    companion object {
        internal fun sampleSnapshot(state: Int): UsageSnapshot {
            val now = Instant.parse("2026-11-04T12:00:00Z")
            val observed = if (state == 4) now.minusSeconds(1200) else now
            val weekly = QuotaObservation(
                "sample",
                observed,
                QuotaUsage("sample", 20.0, now.plusSeconds(86400).epochSecond, "secondary")
            )
            val five = QuotaObservation(
                "sample",
                observed,
                QuotaUsage("sample", 40.0, now.plusSeconds(7200).epochSecond, "primary", 300)
            )
            return UsageSnapshot(
                loading = state == 3, storedEvents = 1, evaluatedAt = if (state == 5) now.plusSeconds(86400) else now,
                collection = CollectionSnapshot(lastCompleted = now),
                analytics = UsageAnalytics(
                    lastRequest = RequestReading(
                        observed,
                        RequestUsage(RequestCounters(1230, 1000, null, 230, null), "Sample data")
                    ),
                    quotaObservations = if (state == 2) emptyList() else listOf(weekly, five)
                ),
                budgetEstimates = mapOf(
                    QuotaPresentation.scope(weekly) to QuotaBudgetEstimate.calculate(
                        weekly, 70_460_000,
                        ResetWeekResolver.resolve(weekly, observed)
                    )
                )
            )
        }
    }
}
