package local.aiusagetrackers


import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.GridLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.time.Instant
import java.time.ZoneId
import java.util.*
import javax.swing.*
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

private const val EXPORT_TRACKERS = "Export Trackers"
private const val FIVE_HOUR_SHARE_HELP = "Show the period's approximate share of the five-hour token budget."

class TrackerSettingsPanel(private val recordedWidths: RibbonWidthStatistics = RibbonWidthStatistics()) :
    JPanel(BorderLayout(8, 8)), AutoCloseable {
    private val model: CollectionListModel<TrackerDefinition> = object : CollectionListModel<TrackerDefinition>() {
        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            val selected = list.selectedValue
            loading = true
            super.exchangeRows(oldIndex, newIndex)
            if (selected != null) list.setSelectedValue(selected, true)
            loading = false
            loadSelected()
        }
    }
    private val list: JBList<TrackerDefinition> = JBList(model)
    private val widgetPreview = TrackerWidgetPreview(::reorderPreview)
    private val trackerLimit = JSpinner(SpinnerNumberModel(5, 1, 32, 1))
    private val lastRequest = JCheckBox("Show last request", false)
    private val consentEditor = UsageConsentPanel()
    fun usageAgreement(): Boolean = consentEditor.accepted
    fun resetUsageAgreement(value: Boolean) { consentEditor.accepted = value }
    private var quotaAccountId = ProviderProfile.LEGACY_CODEX
    private val analyticsEditor = AnalyticsSettingsPanel { service<UsageService>().snapshot(quotaAccountId) }
    private val generalEditor = ProviderSettingsPanel(::providersChanged, ::addDefaultTrackers) { id -> trackers().any { it.accountId == id } }
    private val trackerAccount = JComboBox<AccountChoice>()
    private val trackerPool = JComboBox<AccountChoice>().apply { isEditable = true }
    private val quotaAccount = JComboBox<AccountChoice>()
    private var providerLoading = false
    fun trackerLimit(): Int = (trackerLimit.value as Number).toInt()
    fun providers(): ProviderConfiguration {
        commitQuotaAccountSelection()
        generalEditor.setOptions(quotaAccountId, analyticsEditor.options())
        return generalEditor.reconcileExternal(service<UsageSettings>().providers)
    }

    fun resetProviders(value: ProviderConfiguration) {
        generalEditor.reset(value); providersChanged()
    }

    private fun providersChanged() {
        if (providerLoading) return
        providerLoading = true
        val config = generalEditor.value()
        val binding = list.selectedValue?.accountId
        val oldQuota = quotaAccountId
        val choices = config.profiles.map { AccountChoice(it.id, "${it.vendor.presentationLabel}: ${it.alias}") }
        updateSettingsChoices(trackerAccount, choices, choices.firstOrNull { it.id == binding })
        updateSettingsChoices(quotaAccount, choices, choices.firstOrNull { it.id == oldQuota } ?: choices.firstOrNull())
        providerLoading = false
        if (!loading) {
            commitQuotaAccountSelection()
            updateColorControls(); enableFields(); refreshWidgetPreview()
        }
    }

    private fun addDefaultTrackers(accountId: String) {
        if (trackers().any { it.accountId == accountId }) return
        if (model.size + 2 > trackerLimit()) trackerLimit.value = (model.size + 2).coerceAtMost(32)
        if (model.size + 2 > 32) return
        TrackerDefinition.defaults()
            .forEach { model.add(it.copy(id = UUID.randomUUID().toString(), accountId = accountId)) }
        list.selectedIndex = model.size - 2
    }

    private fun trackerCapabilities(definition: TrackerDefinition? = list.selectedValue): PlanCapabilities {
        val id = definition?.accountId ?: quotaAccountId
        return trackerAnalyticsOptions(id)?.capabilities() ?: PlanCapabilities.UNKNOWN
    }

    private fun trackerAnalyticsOptions(accountId: String): AnalyticsDisplayOptions? =
        if (accountId == quotaAccountId) analyticsEditor.options()
        else generalEditor.value().profiles.firstOrNull { it.id == accountId }?.analytics
    private val name = JTextField()
    private val fixedAuxiliary = linkedMapOf(
        "request" to JCheckBox("Last request"),
        "quota:10080" to JCheckBox("Weekly quota"),
        "quota:300" to JCheckBox("Five-hour quota"),
        "reset:10080" to JCheckBox("Weekly reset"),
        "reset:300" to JCheckBox("Five-hour reset")
    )
    private val ribbonPlacement = JComboBox(RibbonPlacement.entries.toTypedArray())
    private val ribbonWidth = JSpinner(SpinnerNumberModel(280, 80, 1200, 10))
    private val ribbonAutoExpand = JCheckBox("Widen ribbon to fit oversized entries")
    private val ribbonScrollMode = JComboBox(RibbonScrollMode.entries.toTypedArray())
    private val ribbonSpeed = JSpinner(SpinnerNumberModel(30, 0, 200, 5))
    private val horizontalInterval = JSpinner(SpinnerNumberModel(3, 0, 300, 1))
    private val verticalInterval = JSpinner(SpinnerNumberModel(3, 0, 300, 1))
    private val verticalDirection = JComboBox(RibbonStepDirection.entries.toTypedArray())
    private val ribbonModeControls = mutableListOf<Pair<JComponent, JComponent>>()
    private val hideLabel = JCheckBox("Hide tracker label")
    private val coloringEnabled = JCheckBox("Enable coloring", true)
    private val trackerColors = SettingsForm()
    private val palette = PaletteEditor(::presentationChanged)
    private val weeklyPacing = JCheckBox("Adjust weekly colors for reset progress")
    private val separateFiveHourColors = JCheckBox("Color five-hour tracker shares separately", true)
    private val legacyNotice = JLabel("Legacy schedule retained")
    private val replaceLegacy = JButton("Use automatic periods")
    private val showPercentUsed = JCheckBox("Show weekly percentage used")
    private val showFiveHourPercentUsed = JCheckBox("Show five-hour percentage used")
    private val metricSettings = TokenMetricSettings(::changed)
    private val quotaColors = JCheckBox("Color by quota goal")
    private val allPercentUsed = JCheckBox("Show weekly percentage used on all token metrics")
    private val customFont = JCheckBox("Custom widget font size")
    private val fontSize = JSpinner(SpinnerNumberModel(12, 8, 24, 1))
    private val amount = JSpinner(SpinnerNumberModel(1, 1, 1000, 1))
    private val unit = JComboBox(SpanUnit.entries.toTypedArray())
    private val mode = JComboBox(arrayOf(PeriodMode.CALENDAR, PeriodMode.ELAPSED, PeriodMode.PROVIDER_WEEK))
    private val selection = JComboBox(DaySelection.entries.toTypedArray())
    private val current = JCheckBox("Include the current period", true)
    private val resetDailyAtWeeklyReset = JCheckBox("Also reset daily usage at weekly reset")
    private val visible = JCheckBox("Show in status bar", true)
    private val showPeriodStart = JCheckBox("Show period start in status bar")
    private val equivalence = JButton(AllIcons.General.Information).apply {
        preferredSize = JBUI.size(24, 24)
        border = JBUI.Borders.empty(2)
        toolTipText = "Compare this tracker duration with equivalent values in other supported units."
        accessibleContext.accessibleName = "Time-unit equivalence"
        addActionListener { showEquivalence(false) }
    }
    private val datePattern = JComboBox(arrayOf("yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd MMM yyyy")).apply {
        isEditable = true
    }
    private val clockFormat = JComboBox(ClockFormat.entries.toTypedArray())
    private val formatPreview = JLabel()
    private val zone = JTextField()
    private val endAnchor = JComboBox(EndAnchor.entries.toTypedArray())
    private val endOffset = JSpinner(SpinnerNumberModel(0, 0, 1000, 1))
    private val preview = JBTextArea(7, 48).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val extraHomes = JBTextArea(3, 48)
    private val trackerDetails = SettingsCategory("Period details", preview, false)
    private val emptyTracker = JLabel("Add or select a tracker to edit its settings.")
    private val fields = SettingsForm()
    private var loading = false
    private var closed = false
    private var generation = 0
    private var previewJob: Job? = null
    private var transferJob: Job? = null
    private val importSettings = JButton("Import Settings…").apply { addActionListener { importTrackers() } }
    private val exportSettings = JButton("Export Settings…").apply {
        isEnabled = false
        addActionListener { exportTrackers() }
    }
    private var transferBusy = false
        set(value) {
            field = value
            updateTransferControls()
        }
    private var transferGeneration = 0
    private var hintPopup: JBPopup? = null
    private var pendingHint: String? = null
    private val hintTimer = Timer(700) { showEquivalence(true) }.apply { isRepeats = false }
    private val previewTimer = Timer(350) { updatePreview() }.apply { isRepeats = false }

    init {
        initializeUi()
    }

    private fun initializeUi() {

        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = trackerListChanged()
            override fun intervalRemoved(e: ListDataEvent) = trackerListChanged()
            override fun contentsChanged(e: ListDataEvent) = trackerListChanged()
        })
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                putClientProperty("html.disable", true)
                return super.getListCellRendererComponent(
                    list,
                    (value as? TrackerDefinition)?.let { "${it.name} — ${it.amount} ${it.unit.label.lowercase()}" }
                        ?: "",
                    index,
                    isSelected,
                    cellHasFocus
                ).also { (it as JLabel).icon = AllIcons.General.Drag }
            }
        }
        list.addListSelectionListener { if (!loading && !it.valueIsAdjusting) loadSelected() }
        fun row(label: String, component: JComponent, help: String, control: JComponent = component) {
            explain(component, help)
            fields.addRow(JLabel(label).apply { labelFor = control; explain(this, help) }, component)
        }
        fields.section("Period")
        fields.addRow(legacyNotice, replaceLegacy)
        row("Name", name, "Name shown in the tracker list and usage details.")
        row("Account / token dataset", trackerAccount, "Choose the account whose token usage this tracker counts.")
        row("Quota pool", trackerPool, "Choose the quota limit that supplies this tracker's provider reset.")
        row(
            "Weekly reset alignment",
            resetDailyAtWeeklyReset,
            "For one current calendar day, start counting again at the selected account's weekly quota reset. " +
                "If no weekly reset is available, use the usual daily boundary."
        )
        row(
            "Amount",
            amount,
            "Number of minutes, hours, days, weeks or months to include."
        )
        row(
            "Unit",
            JPanel(BorderLayout(4, 0)).apply {
                add(unit, BorderLayout.CENTER); add(equivalence, BorderLayout.EAST)
            },
            "Minutes, hours, days, weeks or months. Elapsed minutes are 60 seconds; days are 24 hours. Elapsed months are unsupported.",
            unit
        )
        row(
            "Period",
            mode,
            "Choose calendar boundaries, a rolling duration, or a provider reset, with five-hour alignment available only for Amount 5 and Unit Hours on a supported account."
        )
        row(
            "Days to include",
            selection,
            "Include all days, Monday–Friday, or days with recorded usage."
        )
        row(
            "Current period",
            current,
            "Include the current period, even though it has not finished."
        )
        row(
            "Visibility",
            visible,
            "Show this tracker in the status bar."
        )
        row(
            "Minute/hour display",
            showPeriodStart,
            "Show period boundaries for minute and hour trackers."
        )
        row(
            "Time zone",
            zone,
            "Leave blank for the system time zone, or enter a name such as Europe/Budapest."
        )
        row(
            "Elapsed period ends at",
            endAnchor,
            "End the rolling period now or at a calendar boundary."
        )
        row(
            "End this many periods earlier",
            endOffset,
            "Move the end boundary back by this many selected units."
        )
        val focusScroll = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                val bounds = SwingUtilities.convertRectangle(e.component.parent, e.component.bounds, fields)
                fields.scrollRectToVisible(bounds)
            }
        }
        fields.section("Status bar")
        row(
            "Placement",
            ribbonPlacement,
            "Automatic pins single-week, single-day and single-hour trackers. Fixed and Ribbon override this for the selected tracker."
        )
        row("Tracker label", hideLabel, "Hide this tracker label while retaining its accessible name and identity.")
        row(
            "Weekly share",
            showPercentUsed,
            "Show the period's approximate share of the selected weekly token budget. Missing estimates show a dash."
        )
        row(
            "Five-hour share",
            showFiveHourPercentUsed,
            FIVE_HOUR_SHARE_HELP
        )
        metricSettings.addRows(fields)


        fun followFocus(component: Component) {
            component.addFocusListener(focusScroll)
            if (component is Container) component.components.forEach(::followFocus)
        }
        followFocus(fields)
        explain(
            preview,
            "Resolved intervals and stored token totals for the selected draft. Updates in the background; long previews show the first 20 intervals. Starts are inclusive and ends exclusive."
        )
        val right = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                add(emptyTracker, BorderLayout.NORTH); add(
                fields,
                BorderLayout.CENTER
            )
            }, BorderLayout.NORTH)
            add(trackerDetails, BorderLayout.CENTER)
        }
        val decorator = ToolbarDecorator.createDecorator(list)
            .setToolbarPosition(ActionToolbarPosition.TOP)
            .setAddActionName("Add New")
            .setRemoveActionName("Remove")
            .setMoveUpActionName("Move Up")
            .setMoveDownActionName("Move Down")
            .setAddAction { addTracker() }
            .setRemoveAction { removeTracker() }
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .setAddActionUpdater { model.size < trackerLimit() && !transferBusy }
            .setForcedDnD()

        fun extra(label: String, icon: Icon, enabled: () -> Boolean, help: String = label, action: (AnActionEvent) -> Unit) {
            decorator.addExtraAction(object : DumbAwareAction(label, help, icon) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !closed && !transferBusy && enabled()
                }

                override fun actionPerformed(e: AnActionEvent) = action(e)
            })
        }
        extra(
            "Duplicate",
            AllIcons.Actions.Copy,
            { list.selectedIndex >= 0 && model.size < trackerLimit() },
        ) { duplicateTracker() }
        extra("Sort Trackers", AllIcons.ObjectBrowser.Sorted, { model.size > 1 },
            "Sort all trackers by configured duration, shortest first or longest first.") { event ->
            val shortestFirst = "Shortest First"
            JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(shortestFirst, "Longest First"))
                .setTitle("Sort Trackers by Duration")
                .setItemChosenCallback { sortTrackers(it == shortestFirst) }
                .createPopup().showInBestPositionFor(event.dataContext)
        }
        val left = JPanel(BorderLayout(4, 4)).apply {
            add(decorator.createPanel(), BorderLayout.CENTER)
        }
        val trackersPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            add(
                JBSplitter(false, 0.22f).apply {
                    firstComponent = left
                    secondComponent = right
                    dividerWidth = JBUI.scale(4)
                    isShowDividerIcon = false
                    isShowDividerControls = false
                    divider.isOpaque = true
                    divider.background = JBColor(0xA6A6A6, 0x606060)
                },
                BorderLayout.CENTER
            )
            add(SettingsCategory("Last request", SettingsForm().apply {
                addRow(JLabel("Visibility"), lastRequest)
                addBlock(analyticsEditor.requestSettings)
            }), BorderLayout.SOUTH)
        }
        explain(
            extraHomes,
            "Additional Codex home directories, one per line, alongside automatic discovery. Scanning reads sessions and archived_sessions. Removing a home keeps already imported SQLite history."
        )
        val homesPanel = SettingsForm().apply {
            addRow(JLabel("Additional Codex folders"), JBScrollPane(extraHomes).apply {
                SettingsHelp.attach(this, extraHomes.toolTipText)
            })
        }
        // GridBagLayout collapses hidden mode-specific rows instead of reserving blank cells.
        val displayPanel = SettingsForm().apply {
            border = JBUI.Borders.empty()
            section("Status bar layout")
            explain(
                trackerLimit,
                "Maximum number of trackers, from 1 to 32. Default: 5. Remove excess trackers before lowering the limit; no trackers are deleted automatically."
            )

            fun displayRow(label: String, control: JComponent, help: String) {
                explain(control, help)
                addRow(JLabel(label).apply { labelFor = control; toolTipText = control.toolTipText }, control)
            }
            displayRow(
                "Keep outside the ribbon",
                JPanel(GridLayout(0, 2)).apply { fixedAuxiliary.values.forEach { add(it) } },
                "Selected values stay outside the ribbon when shown."
            )
            section("Ribbon")
            val widths = recordedWidths
            if (widths.count > 0) {
                displayRow(
                    "Average width",
                    JLabel(String.format(Locale.ROOT, "%.1f pixels", widths.average)),
                    "Running average of full rendered dynamic ribbon entries at their observed IDE scale."
                )
                displayRow("Max width", JBTextArea("${widths.maximum} pixels — ${widths.maximumValue}").apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; columns = 36
                }, "Largest recorded full rendered entry and its complete value, at the observed IDE scale.")
            }
            displayRow(
                "Ribbon width (logical pixels)",
                ribbonWidth,
                "Width of the scrolling metrics area, 80–1200 pixels, respecting IDE scaling."
            )
            displayRow(
                "Oversized ribbon entries",
                ribbonAutoExpand,
                "Widen for long values; the resize notice lets you keep or revert the change."
            )
            displayRow(
                "Ribbon scrolling",
                ribbonScrollMode,
                "Restart, bounce, scroll continuously, or jump one entry at a time horizontally or vertically."
            )
            fun modeRow(label: String, control: JComponent, help: String) {
                val title = JLabel(label).apply { labelFor = control; toolTipText = help }
                explain(control, help); addRow(title, control)
                ribbonModeControls.add(title to control)
            }
            modeRow(
                "Ribbon speed (pixels/second)",
                ribbonSpeed,
                "Automatic scrolling speed, 0–200. Zero disables motion in pixel scrolling modes."
            )
            modeRow(
                "Horizontal step interval (seconds)",
                horizontalInterval,
                "0–300 seconds. Zero disables automatic steps; manual entry navigation remains available."
            )
            modeRow(
                "Vertical step interval (seconds)",
                verticalInterval,
                "0–300 seconds. Zero disables automatic steps; hover to select any remaining entry."
            )
            modeRow(
                "Vertical step direction",
                verticalDirection,
                "Up follows the configured entry order; Down reverses it. The hover rows follow the same direction."
            )
            updateRibbonControls()
            displayRow(
                "All token metrics",
                allPercentUsed,
                "Enable percentage used for every tracker and Last request. Individual options apply when this is off."
            )
            section("Font")
            displayRow(
                "Widget font",
                customFont,
                "Off uses the IDE default. Custom size applies to the status widget and its previews."
            )
            displayRow("Font size (8–24 points)", fontSize, "Logical points, respecting IDE scaling.")
            section("Date and time")
            displayRow(
                "Date format (preset or custom)",
                datePattern,
                "Choose a date format for usage details and displayed period boundaries."
            )
            displayRow(
                "Time format",
                clockFormat,
                "Choose twelve-hour or twenty-four-hour time for usage details and period boundaries."
            )
            displayRow(
                "Display preview",
                formatPreview,
                "A live sample of the selected date/time formats in the current system time zone. Invalid patterns must be corrected before Apply."
            )
        }
        val appearance = JPanel(BorderLayout()).apply {
            add(displayPanel, BorderLayout.NORTH)
            add(SettingsCategory("Coloring", SettingsForm().apply {
                addRow(JLabel("Coloring"), coloringEnabled)
                addRow(JLabel("Weekly quota colors"), analyticsEditor.colorWeeklyQuota)
                addRow(JLabel("Weekly pacing"), weeklyPacing)
                SettingsHelp.attach(separateFiveHourColors, "Use a separate duration-based color for the five-hour percentage when the tracker's Color by quota goal is enabled.")
                addRow(JLabel("Five-hour tracker colors"), separateFiveHourColors)
                addBlock(trackerColors); addBlock(palette)
            }), BorderLayout.CENTER)
        }
        val advanced = SettingsForm().apply {
            addCategory(generalEditor.advancedPanel as SettingsCategory)
            section("Tracker limit"); addRow(JLabel("Tracker limit"), trackerLimit)
            section("Settings transfer")
            SettingsHelp.attach(importSettings, "Import tracker definitions from JSON, with optional appearance, vendor labels, and carousel settings.")
            SettingsHelp.detail(importSettings, "Preview and merge or replace the tracker list, map accounts, and choose which optional settings to apply. Apply or OK saves the draft.")
            addRow(JLabel("Import from JSON"), importSettings)
            SettingsHelp.attach(exportSettings, "Export selected or all tracker definitions to JSON, with optional appearance, vendor labels, and carousel settings.")
            SettingsHelp.detail(exportSettings, "Export the current settings draft without usage history, source paths, or credentials.")
            addRow(JLabel("Export to JSON"), exportSettings)
        }
        SettingsHelp.attach(quotaAccount, "Choose the account whose quota settings you are editing.")
        val quotaPanel = JPanel(BorderLayout()).apply {
            add(SettingsCategory("Quota account", quotaAccount), BorderLayout.NORTH)
            add(analyticsEditor, BorderLayout.CENTER)
        }
        val tabs = settingsTabs(
            "General" to JPanel(BorderLayout()).apply {
                add(consentEditor, BorderLayout.NORTH)
                add(generalEditor, BorderLayout.CENTER)
            },
            "Trackers" to trackersPanel,
            "Quota" to quotaPanel,
            "Appearance" to appearance,
            "Sources" to SettingsCategory("Additional Codex folders", homesPanel),
            "Advanced" to advanced
        )
        generalEditor.openAdvanced =
            { tabs.selectedIndex = tabs.indexOfTab("Advanced"); revealSetting(generalEditor.advanced) }
        val previewPanel = SettingsForm().apply {
            addBlock(widgetPreview)
            addRow(JLabel("Quota preview data"), analyticsEditor.previewState)
            addRow(JLabel("Quota preview"), analyticsEditor.preview)
        }
        add(SettingsPreviewLayout(tabs, SettingsCategory("Widget preview", previewPanel)), BorderLayout.CENTER)
        installListeners()
        PluginTooltips.constrain(this)
        SettingsHelp.attach(lastRequest, "Show the latest captured request's token count in the widget.")
        SettingsHelp.attach(coloringEnabled, "Use the configured palette for usage values.")
        SettingsHelp.attach(weeklyPacing, "Adjust weekly goal colors for elapsed time in the reset week.")
        SettingsHelp.attach(
            analyticsEditor.colorWeeklyQuota,
            "Color the reported weekly quota using the shared palette."
        )
        SettingsHelp.attach(analyticsEditor.previewState, "Choose collected data or a sample state for the preview.")
        SettingsHelp.attach(replaceLegacy, "Convert this saved legacy schedule to automatic periods.")
    }

    private fun installListeners() {
        coloringEnabled.addActionListener { updateColorControls(); presentationChanged() }
        trackerAccount.addActionListener { if (!providerLoading) changed() }
        trackerPool.addActionListener { if (!providerLoading) changed() }
        trackerPool.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) {
                val definition = list.selectedValue ?: return
                val windowMinutes = definition.mode.resetWindowMinutes ?: 10080
                val pools = service<UsageService>().snapshot(definition.accountId).analytics.quotaObservations
                    .filter { it.usage.windowMinutes == windowMinutes }.map { it.usage.bucket }.distinct()
                providerLoading = true
                val existing = (0 until trackerPool.itemCount).map { trackerPool.getItemAt(it).id }
                pools.filter { it !in existing }.forEach { trackerPool.addItem(AccountChoice(it, it)) }
                providerLoading = false
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) = Unit
        })
        quotaAccount.addActionListener {
            if (!providerLoading) {
                // Let the popup commit and close before dependent controls trigger layout.
                SwingUtilities.invokeLater {
                    commitQuotaAccountSelection()
                }
            }
        }
        analyticsEditor.planProfile.addActionListener {
            if (!loading && !providerLoading && !analyticsEditor.isUpdating) {
                updateColorControls(); enableFields(); presentationChanged()
            }
        }
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        }
        listOf(name, zone).forEach {
            it.document.addDocumentListener(
                listener
            )
        }
        listOf(unit, mode, selection, endAnchor).forEach { it.addActionListener { changed() } }
        listOf(current, visible, showPeriodStart, resetDailyAtWeeklyReset).forEach { it.addActionListener { changed() } }
        listOf(amount, endOffset).forEach { it.addChangeListener { changed() } }
        val formatListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = presentationChanged()
            override fun removeUpdate(e: DocumentEvent) = presentationChanged()
            override fun changedUpdate(e: DocumentEvent) = presentationChanged()
        }
        (datePattern.editor.editorComponent as JTextField).document.addDocumentListener(formatListener)
        clockFormat.addActionListener { presentationChanged() }
        allPercentUsed.addActionListener { presentationChanged() }
        customFont.addActionListener {
            setSettingVisible(
                fontSize,
                customFont.isSelected,
                customFont
            ); presentationChanged()
        }
        fontSize.addChangeListener { presentationChanged() }
        showPercentUsed.addActionListener { changed() }
        showFiveHourPercentUsed.addActionListener { changed() }
        quotaColors.addActionListener { changed() }
        ribbonPlacement.addActionListener { changed() }
        ribbonScrollMode.addActionListener { updateRibbonControls(); presentationChanged() }
        horizontalInterval.addChangeListener { presentationChanged() }
        verticalInterval.addChangeListener { presentationChanged() }
        verticalDirection.addActionListener { presentationChanged() }
        ribbonAutoExpand.addActionListener { presentationChanged() }
        hideLabel.addActionListener { changed() }
        weeklyPacing.addActionListener { presentationChanged() }
        separateFiveHourColors.addActionListener { presentationChanged() }
        replaceLegacy.addActionListener {
            val index = list.selectedIndex
            if (index >= 0) {
                model.setElementAt(list.selectedValue.automaticSchedule(), index)
                loadSelected()
            }
        }
    }

    private fun explain(component: JComponent, help: String) {
        if (component.toolTipText == null) component.toolTipText = PluginTooltips.concise(help)
        component.accessibleContext?.accessibleDescription = help
        component.components.filterIsInstance<JComponent>().forEach { explain(it, help) }
    }

    private fun commitQuotaAccountSelection() {
        val id = (quotaAccount.selectedItem as? AccountChoice)?.id
        if (closed || providerLoading || id == null || id == quotaAccountId) return
        generalEditor.setOptions(quotaAccountId, analyticsEditor.options())
        quotaAccountId = id
        val p = generalEditor.value().profiles.first { it.id == id }
        providerLoading = true
        try {
            analyticsEditor.account(p.vendor, p.analytics)
        } finally {
            providerLoading = false
        }
        updateColorControls(); enableFields(); presentationChanged()
    }

    private fun refreshWidgetPreview() {
        if (!loading && !closed) {
            rebuildTrackerColors()
            val binding = list.selectedValue?.accountId ?: quotaAccountId
            val config = generalEditor.value()
            val label = config.profiles.firstOrNull { it.id == binding }?.vendor?.let(config::label).orEmpty()
            widgetPreview.update(trackers().filter { it.accountId == binding }, display(), trackerCapabilities(), label)
            analyticsEditor.updateDisplay(display())
        }
    }

    private fun updateColorControls() {
        val capabilities = trackerCapabilities()
        weeklyPacing.isEnabled = QuotaEligibility.budget(capabilities)
        allPercentUsed.isEnabled = QuotaEligibility.budget(capabilities)
        showPercentUsed.isEnabled = QuotaEligibility.budget(capabilities)
        quotaColors.isEnabled = QuotaEligibility.budget(capabilities)
        palette.quotaApplicable(capabilities.weekly || QuotaEligibility.budget(capabilities))
        updateAuxiliaryControls(capabilities)
        listOf(palette, trackerColors, weeklyPacing, separateFiveHourColors, analyticsEditor.colorWeeklyQuota).forEach {
            setSettingVisible(it, coloringEnabled.isSelected, coloringEnabled)
        }
        rebuildTrackerColors()
    }

    private fun updateAuxiliaryControls(capabilities: PlanCapabilities) {
        fixedAuxiliary.forEach { (id, control) ->
            val duration = id.substringAfter(':', "").toIntOrNull()
            val decision = duration?.let { QuotaEligibility.metric(capabilities, it, id.startsWith("reset:")) }
            control.isEnabled = decision?.enabled ?: true
            decision?.let {
                SettingsHelp.attach(
                    control,
                    if (it.enabled) "Keep this value outside the ribbon when it is shown." else it.reason
                )
            }
        }
    }

    private fun rebuildTrackerColors() {
        trackerColors.removeAll()
        trackers().filter {
            coloringEnabled.isSelected && QuotaEligibility.budget(trackerCapabilities(it)) && QuotaMetricPresentation.goal(
                it
            ) != null
        }.forEach { tracker ->
            val toggle = JCheckBox("Color by quota goal", tracker.colorByQuotaGoal)
            SettingsHelp.attach(toggle, "Color this tracker's usage relative to its estimated quota goal.")
            toggle.isEnabled = coloringEnabled.isSelected && QuotaMetricPresentation.goal(tracker) != null
            toggle.addActionListener {
                val index = trackers().indexOfFirst { it.id == tracker.id }
                if (index >= 0) {
                    model.setElementAt(model.getElementAt(index).copy(colorByQuotaGoal = toggle.isSelected), index)
                    if (list.selectedIndex == index) quotaColors.isSelected = toggle.isSelected
                    refreshWidgetPreview()
                }
            }
            trackerColors.addRow(JLabel().apply {
                putClientProperty("html.disable", true)
                text = tracker.name
            }, toggle)
        }
        trackerColors.isVisible = coloringEnabled.isSelected && trackerColors.componentCount > 0
        trackerColors.revalidate(); trackerColors.repaint()
    }

    private fun reorderPreview(sourceId: String, insertion: Int) {
        if (closed || loading || transferBusy) return
        val before = trackers()
        applyTrackerOrder(before, TrackerOrdering.moveVisible(before, sourceId, insertion))
    }

    private fun sortTrackers(ascending: Boolean) {
        if (closed || loading || transferBusy) return
        val before = trackers()
        applyTrackerOrder(before, TrackerOrdering.sortByDuration(before, ascending))
        if (list.selectedIndex >= 0) list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun applyTrackerOrder(before: List<TrackerDefinition>, after: List<TrackerDefinition>) {
        if (before == after) return
        val selected = list.selectedValue?.id
        loading = true
        model.removeAll(); after.forEach(model::add)
        list.selectedIndex = after.indexOfFirst { it.id == selected }
        loading = false
        // Keep the selected tracker's editor (including invalid draft text) intact.
        refreshWidgetPreview()
    }

    private fun move(delta: Int) {
        val index = list.selectedIndex
        val target = index + delta
        if (index >= 0 && target in 0 until model.size) {
            loading = true
            model.exchangeRows(index, target); list.selectedIndex = target
            loading = false; loadSelected()
        }
    }

    private fun addTracker() {
        if (model.size >= trackerLimit() || closed) return
        val dialog = NewTrackerDialog(this)
        if (dialog.showAndGet() && !closed) {
            val added = dialog.definition()
            val index = TrackerOrdering.insertionIndex(trackers(), added)
            model.add(index, added)
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }

    private fun duplicateTracker() {
        val selected = list.selectedValue ?: return
        if (model.size >= trackerLimit()) return
        val index = list.selectedIndex + 1
        model.add(index, selected.copy(id = UUID.randomUUID().toString(), name = "${selected.name} copy"))
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    private fun removeTracker() {
        val index = list.selectedIndex
        if (index < 0) return
        model.remove(index)
        list.selectedIndex = minOf(index, model.size - 1)
        loadSelected()
    }

    private fun showEquivalence(automatic: Boolean) {
        if (closed || !equivalence.isShowing) return
        val text = if (automatic) pendingHint ?: return else list.selectedValue?.let(UnitEquivalence::exactHint)
            ?: UnitEquivalence.EXPLANATION
        hintPopup?.cancel()
        val area = JBTextArea(text, 4, 54).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true
            border = JBUI.Borders.empty(8)
        }
        hintPopup = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(area), area)
            .setTitle("Time-Unit Equivalence").setRequestFocus(!automatic).setFocusable(true)
            .setResizable(true).setCancelOnClickOutside(true).createPopup().also { it.showUnderneathOf(equivalence) }
    }

    private fun trackerListChanged() {
        updateTransferControls()
        refreshWidgetPreview()
    }

    private fun updateTransferControls() {
        importSettings.isEnabled = !closed && !transferBusy
        exportSettings.isEnabled = importSettings.isEnabled && !model.isEmpty
    }

    private fun importTrackers() {
        if (closed || transferBusy) return
        val file =
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("json"), this, null, null)
                ?: return
        val path = file.toNioPath()
        transferBusy = true
        val requested = ++transferGeneration
        transferJob =
            service<ConfigurationTransferService>().execute({ TrackerConfigurationJson.read(path) }) { result ->
                if (!closed && requested == transferGeneration) {
                    transferBusy = false
                    result.fold(
                        { configuration ->
                            applyImport(configuration)
                        },
                        {
                            Messages.showErrorDialog(
                                this,
                                "Import failed: ${it.message ?: "invalid file"}",
                                "Import Trackers"
                            )
                        })
                }
            }
    }

    private fun applyImport(configuration: TrackerConfiguration) {
        val dialog =
            ImportTrackersDialog(this, configuration, trackers(), trackerLimit(), generalEditor.value().profiles)
        if (dialog.showAndGet() && !closed) {
            val imported = configuration.remap(dialog.accountMapping()).applyTo(trackers(), dialog.replace)
            if (imported.size > trackerLimit()) {
                Messages.showErrorDialog(
                    this,
                    "Import exceeds the tracker limit. Increase the limit or import fewer trackers.",
                    "Import Trackers"
                )
                return
            }
            val formats = if (dialog.applyDisplay.isSelected) configuration.display
                ?: display() else display()
            reset(homes(), imported, formats)
            if (dialog.applyGeneral.isSelected) configuration.general?.let { portable ->
                val config = generalEditor.value()
                config.vendors.forEach { vendor -> portable.labels[vendor.vendor]?.let { vendor.label = it } }
                config.display = portable.state.isolatedCopy()
                resetProviders(config)
            }
        }
    }

    private fun exportTrackers() {
        if (closed || transferBusy || model.isEmpty) return
        val options = ExportTrackersDialog(this, list.selectedIndex >= 0)
        if (!options.showAndGet() || closed) return
        val definitions = if (options.selectedOnly) listOfNotNull(list.selectedValue?.copy()) else trackers()
        val config = generalEditor.value()
        val bindings = definitions.map { it.accountId }.distinct().mapNotNull { id ->
            config.profiles.firstOrNull { it.id == id }?.let { PortableAccountBinding(id, it.vendor, it.alias) }
        }
        val configuration = TrackerConfiguration(
            definitions,
            if (options.includeDisplay.isSelected) display() else null,
            bindings,
            if (options.includeGeneral.isSelected) PortableVendorDisplay(
                config.vendors.associate { it.vendor to it.label },
                config.display
            ) else null
        )
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor(EXPORT_TRACKERS, "Save portable tracker definitions", "json"), this
        )
        val file = saver.save("ai-usage-trackers.json") ?: return
        val path = file.file.toPath()
        transferBusy = true
        val requested = ++transferGeneration
        transferJob = service<ConfigurationTransferService>().execute({
            TrackerConfigurationJson.write(
                path,
                configuration
            )
        }) { result ->
            if (!closed && requested == transferGeneration) {
                transferBusy = false
                result.fold(
                    { Messages.showInfoMessage(this, "Configuration exported.", EXPORT_TRACKERS) },
                    {
                        Messages.showErrorDialog(
                            this,
                            "Export failed: ${it.message ?: "unable to write file"}",
                            EXPORT_TRACKERS
                        )
                    })
            }
        }
    }

    fun validateDraft() {
        commitQuotaAccountSelection()
        generalEditor.validateDraft()
        validateSetting(trackerLimit) { require(model.size <= trackerLimit()) { "Remove trackers or increase the tracker limit before applying" } }
        trackers().forEachIndexed { index, tracker ->
            if (runCatching { tracker.metricLayout().validate() }.isFailure) {
                list.selectedIndex = index
                metricSettings.validateDraft()
            }
            try {
                UsagePeriodResolver().validate(tracker)
            } catch (failure: Exception) {
                list.selectedIndex = index
                val target = if (tracker.name.isBlank()) name else if (runCatching {
                        if (tracker.zoneId.isNotBlank()) ZoneId.of(tracker.zoneId)
                    }.isFailure) zone else mode
                revealSetting(target)
                throw failure
            }
        }
        require(trackers().map { it.id }.distinct().size == model.size) { "Tracker identities must be unique" }
        palette.validateDraft()
        validateSetting(datePattern.editor.editorComponent as JComponent) { display().validate() }
        analyticsEditor.validateDraft()
    }

    fun homes(): List<String> =
        extraHomes.text.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

    fun trackers(): List<TrackerDefinition> = (0 until model.size).map { model.getElementAt(it).copy() }
    fun showLastRequest(): Boolean = lastRequest.isSelected
    fun analyticsDisplay(): AnalyticsDisplayOptions {
        commitQuotaAccountSelection()
        generalEditor.setOptions(quotaAccountId, analyticsEditor.options())
        return generalEditor.value().profiles.firstOrNull { it.id == ProviderProfile.LEGACY_CODEX }?.analytics
            ?: AnalyticsDisplayOptions()
    }

    fun display(): DisplayPreferences =
        DisplayPreferences(
            datePattern.editor.item.toString().trim(),
            clockFormat.selectedItem as ClockFormat,
            if (customFont.isSelected) (fontSize.value as Number).toInt() else 0,
            allPercentUsed.isSelected,
            weeklyPacing.isSelected,
            palette.spec(),
            palette.aboveMultiplier(),
            palette.warningMultiplier(),
            (ribbonWidth.value as Number).toInt(),
            (ribbonSpeed.value as Number).toInt(),
            fixedAuxiliary.filterValues { it.isSelected }.keys.joinToString(","),
            ribbonScrollMode.selectedItem as RibbonScrollMode,
            (horizontalInterval.value as Number).toInt(),
            (verticalInterval.value as Number).toInt(),
            verticalDirection.selectedItem as RibbonStepDirection,
            ribbonAutoExpand.isSelected,
            coloringEnabled.isSelected,
            separateFiveHourColors.isSelected,
        )

    private fun updateRibbonControls() {
        val mode = ribbonScrollMode.selectedItem as RibbonScrollMode
        ribbonModeControls.forEach { (label, control) ->
            val shown = when (control) {
                ribbonSpeed -> !mode.stepped
                horizontalInterval -> mode == RibbonScrollMode.HORIZONTAL_STEP
                else -> mode == RibbonScrollMode.VERTICAL_STEP
            }
            label.isVisible = shown; control.isVisible = shown
        }
        revalidate(); repaint()
    }

    fun reset(
        homes: List<String>,
        trackers: List<TrackerDefinition>,
        display: DisplayPreferences,
        limit: Int = trackerLimit(),
        showLastRequest: Boolean = showLastRequest(),
        analyticsDisplay: AnalyticsDisplayOptions = analyticsDisplay()
    ) {
        transferGeneration++; transferJob?.cancel(); transferBusy = false
        loading = true
        quotaAccountId = ProviderProfile.LEGACY_CODEX
        analyticsEditor.vendor(UsageVendor.CODEX)
        trackerLimit.value = limit.coerceIn(1, 32)
        lastRequest.isSelected = showLastRequest
        analyticsEditor.reset(analyticsDisplay)
        generalEditor.setOptions(ProviderProfile.LEGACY_CODEX, analyticsDisplay)
        fixedAuxiliary.forEach { (id, box) -> box.isSelected = id in display.fixedAuxiliary.split(",") }
        ribbonWidth.value = display.ribbonWidth
        ribbonAutoExpand.isSelected = display.ribbonAutoExpand
        coloringEnabled.isSelected = display.coloringEnabled
        updateColorControls()
        ribbonSpeed.value = display.ribbonSpeed
        ribbonScrollMode.selectedItem = display.ribbonScrollMode
        horizontalInterval.value = display.ribbonHorizontalStepIntervalSeconds
        verticalInterval.value = display.ribbonVerticalStepIntervalSeconds
        verticalDirection.selectedItem = display.ribbonVerticalStepDirection
        updateRibbonControls()
        datePattern.selectedItem = display.datePattern
        clockFormat.selectedItem = display.clockFormat
        customFont.isSelected = display.widgetFontSize != 0
        fontSize.value = display.widgetFontSize.takeIf { it in 8..24 } ?: 12
        setSettingVisible(fontSize, customFont.isSelected, customFont)
        allPercentUsed.isSelected = display.allTokenPercentUsed
        weeklyPacing.isSelected = display.weeklyPacing
        separateFiveHourColors.isSelected = display.separateFiveHourColors
        palette.reset(display.palette)
        palette.resetMultipliers(display.aboveQuotaMultiplier, display.warningQuotaMultiplier)
        extraHomes.text = homes.joinToString("\n")
        model.removeAll(); trackers.forEach { model.add(it.copy()) }
        list.selectedIndex = if (model.isEmpty) -1 else 0
        loading = false
        loadSelected()
        presentationChanged()
    }

    private fun loadSelected() {
        hintTimer.stop(); pendingHint = null; hintPopup?.cancel(); hintPopup = null
        loading = true
        val value = list.selectedValue
        fields.enableFields(value != null)
        fields.isVisible = value != null
        trackerDetails.isVisible = value != null
        emptyTracker.isVisible = value == null
        legacyNotice.isVisible = value?.usesLegacySchedule() == true
        replaceLegacy.isVisible = legacyNotice.isVisible
        unit.isEnabled = value != null
        equivalence.isEnabled = value != null
        if (value != null) {
            ribbonPlacement.selectedItem = value.ribbonPlacement
            hideLabel.isSelected = value.hideLabel || StatusValueStyle.forDisplay(value.style).hideLabel
            showPercentUsed.isSelected = value.showPercentUsed
            showFiveHourPercentUsed.isSelected = value.showFiveHourPercentUsed
            metricSettings.load(value.metricLayout())
            quotaColors.isSelected = value.colorByQuotaGoal
            listOf(
                name to value.name, zone to value.zoneId
            ).forEach { (field, text) -> field.text = text }
            loadTrackerBinding(value)
            amount.value = value.amount
            endOffset.value = value.endOffset
            unit.selectedItem = value.unit
            refreshPeriodChoices(value, trackerCapabilities(value))
            selection.selectedItem = value.selection
            endAnchor.selectedItem = value.endAnchor
            current.isSelected = value.includeCurrent
            resetDailyAtWeeklyReset.isSelected = value.resetDailyAtWeeklyReset
            visible.isSelected = value.visible
            showPeriodStart.isSelected = value.showPeriodStart
            enableFields()
        }
        loading = false


        schedulePreview()
    }

    private fun loadTrackerBinding(value: TrackerDefinition) {
        providerLoading = true
        trackerAccount.selectedItem = (0 until trackerAccount.itemCount).map(trackerAccount::getItemAt)
            .firstOrNull { it.id == value.accountId }
        trackerPool.removeAllItems()
        trackerPool.addItem(AccountChoice("", "Account default (legacy behavior)"))
        val profile = generalEditor.value().profiles.firstOrNull { it.id == value.accountId }
        val windowMinutes = (value.mode.resetWindowMinutes ?: 10080).toLong()
        val pools = if (profile?.vendor == UsageVendor.CLAUDE)
            trackerCapabilities(value).pools.filter { it.duration?.toMinutes() == windowMinutes }.map { it.id }
        else emptyList()
        (pools + value.quotaPoolId).filter { it.isNotBlank() }.distinct()
            .forEach { trackerPool.addItem(AccountChoice(it, it)) }
        trackerPool.selectedItem =
            (0 until trackerPool.itemCount).map(trackerPool::getItemAt).firstOrNull { it.id == value.quotaPoolId }
        providerLoading = false
    }

    private fun readSelected(): TrackerDefinition {
        val layout = metricSettings.value()
        return list.selectedValue.copy(
            accountId = (trackerAccount.selectedItem as? AccountChoice)?.id ?: list.selectedValue.accountId,
            quotaPoolId = (trackerPool.editor.item as? AccountChoice)?.id ?: trackerPool.editor.item?.toString()?.trim()
                .orEmpty(),
            name = name.text.trim(),
            amount = (amount.value as Number).toInt(),
            unit = unit.selectedItem as SpanUnit,
            mode = mode.selectedItem as PeriodMode,
            selection = selection.selectedItem as DaySelection,
            includeCurrent = current.isSelected,
            resetDailyAtWeeklyReset = resetDailyAtWeeklyReset.isSelected,
            visible = visible.isSelected,
            showPeriodStart = showPeriodStart.isSelected,
            zoneId = zone.text.trim(),
            endAnchor = endAnchor.selectedItem as EndAnchor,
            endOffset = (endOffset.value as Number).toInt(),
            ribbonPlacement = ribbonPlacement.selectedItem as RibbonPlacement,
            hideLabel = hideLabel.isSelected,
            style = "",
            showPercentUsed = showPercentUsed.isSelected,
            showFiveHourPercentUsed = showFiveHourPercentUsed.isSelected,
            metricOrder = layout.order,
            tokenPrefix = layout.tokenPrefix,
            weeklyPrefix = layout.weeklyPrefix,
            fiveHourPrefix = layout.fiveHourPrefix,
            colorByQuotaGoal = quotaColors.isSelected,
        )
    }

    private fun changed() {
        if (loading || list.selectedIndex < 0 || closed) return
        val previous = list.selectedValue
        if (mode.selectedItem == PeriodMode.PROVIDER_WEEK && previous.mode != PeriodMode.PROVIDER_WEEK && !trackerCapabilities().weekly) {
            loading = true; mode.selectedItem = previous.mode; loading = false
            mode.toolTipText = "A verified fixed weekly quota binding is required"
            return
        }
        loading = true
        val daily = mode.selectedItem == PeriodMode.CALENDAR && unit.selectedItem == SpanUnit.DAY
        if (mode.selectedItem == PeriodMode.PROVIDER_WEEK) {
            unit.selectedItem = SpanUnit.WEEK; amount.value = 1; current.isSelected = true
        }
        if (mode.selectedItem == PeriodMode.PROVIDER_FIVE_HOUR) current.isSelected = true
        if (!daily) {
            selection.selectedItem = DaySelection.ALL
        }
        if (endAnchor.selectedItem == EndAnchor.NOW) endOffset.value = 0
        val draft = readSelected()
        if (draft.mode != previous.mode && (draft.mode == PeriodMode.PROVIDER_FIVE_HOUR || previous.mode == PeriodMode.PROVIDER_FIVE_HOUR)) {
            loadTrackerBinding(draft)
        }
        enableFields()
        if (mode.selectedItem == PeriodMode.PROVIDER_WEEK && previous.mode != PeriodMode.PROVIDER_WEEK &&
            (trackerPool.editor.item as? AccountChoice)?.id.isNullOrEmpty()
        ) {
            val choices = (0 until trackerPool.itemCount).map(trackerPool::getItemAt).filter { it.id.isNotBlank() }
            trackerPool.selectedItem =
                choices.singleOrNull() ?: AccountChoice("select-weekly-pool", "Select a weekly pool")
        }
        val next = readSelected()
        model.setElementAt(next, list.selectedIndex)
        updatePeriodHint(previous, next)
        loading = false
        schedulePreview()
    }

    private fun updatePeriodHint(previous: TrackerDefinition, next: TrackerDefinition) {
        if (previous.amount == next.amount && previous.unit == next.unit && previous.mode == next.mode) return
        hintTimer.stop()
        hintPopup?.cancel()
        hintPopup = null
        pendingHint = UnitEquivalence.exactHint(next)
        if (pendingHint != null) hintTimer.restart()
    }

    private fun refreshPeriodChoices(draft: TrackerDefinition, capabilities: PlanCapabilities) {
        val wasLoading = loading
        loading = true
        try {
            updateSettingsChoices(mode, trackerPeriodChoices(draft, capabilities), draft.mode)
        } finally {
            loading = wasLoading
        }
    }

    private fun enableFields() {
        if (list.selectedValue == null) return
        val draft = readSelected()
        val capabilities = trackerCapabilities(draft)
        refreshPeriodChoices(draft, capabilities)
        showPercentUsed.isEnabled = QuotaEligibility.budget(capabilities)
        val fiveHourUnavailableReason = when {
            !draft.supportsFiveHourShare() -> "Choose a supported period of 1–5 hours or 1–299 minutes to show this share."
            !QuotaEligibility.budget(capabilities, 300) -> FIVE_HOUR_PLAN_REQUIRED
            else -> null
        }
        metricSettings.updateFiveHourAvailability(showFiveHourPercentUsed, FIVE_HOUR_SHARE_HELP, fiveHourUnavailableReason)
        quotaColors.isEnabled = showPercentUsed.isEnabled
        setSettingVisible(resetDailyAtWeeklyReset, draft.supportsDailyWeeklyReset(), mode)
        val legacy = draft.usesLegacySchedule()
        val shown = trackerSettingVisibility(
            mode.selectedItem as PeriodMode, unit.selectedItem as SpanUnit,
            endAnchor.selectedItem as EndAnchor, legacy
        )
        listOf(
            "amount" to amount, "unit" to unit, "days" to selection, "current" to current,
            "boundaries" to showPeriodStart, "end" to endAnchor, "offset" to endOffset,
            "period" to mode, "zone" to zone
        ).forEach { (key, control) ->
            setSettingVisible(control, key in shown, mode)
        }
        legacyNotice.text = "Saved schedule: ${list.selectedValue?.mode}"
        legacyNotice.isVisible = legacy; replaceLegacy.isVisible = legacy
    }

    private fun schedulePreview() {
        refreshWidgetPreview()
        generation++
        previewJob?.cancel(); previewTimer.restart()
    }

    private fun presentationChanged() {
        if (loading || closed) return
        formatPreview.text = try {
            display().validate()
            DisplayFormats(display()).dateTime(Instant.now(), ZoneId.systemDefault())
        } catch (_: Exception) {
            "Invalid date format"
        }
        schedulePreview()
    }

    private fun updatePreview() {
        val definition = list.selectedValue?.copy()
        if (definition == null) {
            preview.text = "Add a tracker to configure its period."; return
        }
        val requested = generation
        preview.text = "Calculating preview…"
        previewJob = service<UsageService>().preview(definition, display(), trackerAnalyticsOptions(definition.accountId)) { text ->
            if (!closed && generation == requested) {
                preview.text = text; preview.caretPosition = 0
            }
        }
    }

    override fun close() {
        widgetPreview.close()
        analyticsEditor.close()
        closed = true; generation++; previewTimer.stop(); previewJob?.cancel()
        updateTransferControls()
        transferGeneration++; transferJob?.cancel(); hintTimer.stop(); hintPopup?.cancel(); hintPopup = null
    }
}

internal fun trackerPeriodChoices(definition: TrackerDefinition, capabilities: PlanCapabilities): List<PeriodMode> = buildSet {
    add(PeriodMode.CALENDAR)
    add(PeriodMode.ELAPSED)
    add(PeriodMode.PROVIDER_WEEK)
    if (definition.hasFiveHourDuration() && QuotaEligibility.resetAligned(capabilities, 300)) add(PeriodMode.PROVIDER_FIVE_HOUR)
    add(definition.mode)
}.toList()

internal fun trackerSettingVisibility(mode: PeriodMode, unit: SpanUnit, end: EndAnchor, legacy: Boolean): Set<String> =
    buildSet {
        if (!legacy) addAll(periodSettingVisibility(mode, unit, end))
        if (unit == SpanUnit.MINUTE || unit == SpanUnit.HOUR) add("boundaries")
    }

private fun periodSettingVisibility(mode: PeriodMode, unit: SpanUnit, end: EndAnchor): Set<String> = buildSet {
    add("period"); add("zone")
    if (mode.resetWindowMinutes == null && mode != PeriodMode.FIXED) {
        add("amount"); add("unit")
    }
    if (mode == PeriodMode.CALENDAR && unit == SpanUnit.DAY) add("days")
    if (mode == PeriodMode.CALENDAR || mode == PeriodMode.REPEATING) add("current")
    if (mode == PeriodMode.ELAPSED) {
        add("end")
        if (end != EndAnchor.NOW) add("offset")
    }
}
