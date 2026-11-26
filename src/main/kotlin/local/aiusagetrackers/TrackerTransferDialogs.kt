package local.aiusagetrackers

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

class NewTrackerDialog(parent: JComponent) : DialogWrapper(parent, true) {
    private val unit = JComboBox(SpanUnit.entries.toTypedArray())
    private val amount = JSpinner(SpinnerNumberModel(1, 1, 1000, 1))

    init {
        SettingsHelp.attach(unit, "Choose the time unit for the new tracker.")
        SettingsHelp.attach(amount, "Number of selected time units to count.")
        title = "Add New Tracker"; setOKButtonText("Add"); init()
    }

    override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 2, JBUI.scale(8), JBUI.scale(8))).apply {
        add(JLabel("Unit").apply { SettingsHelp.attach(this, unit.toolTipText) }); add(unit)
        add(JLabel("Amount").apply { SettingsHelp.attach(this, amount.toolTipText) }); add(amount)
    }

    fun definition(): TrackerDefinition {
        val selected = unit.selectedItem as SpanUnit
        val count = (amount.value as Number).toInt()
        val name = if (count != 1) "$count ${selected.label.lowercase()}" else when (selected) {
            SpanUnit.MINUTE -> "Minute"
            SpanUnit.HOUR -> "Hourly"; SpanUnit.DAY -> "Daily"; SpanUnit.WEEK -> "Weekly"; SpanUnit.MONTH -> "Monthly"
        }
        return TrackerDefinition(name = name, unit = selected, amount = count)
    }
}

class ExportTrackersDialog(parent: JComponent, hasSelection: Boolean) : DialogWrapper(parent, true) {
    private val scope =
        JComboBox(if (hasSelection) arrayOf("All trackers", "Selected tracker") else arrayOf("All trackers"))
    val includeDisplay = JCheckBox("Include appearance settings")
    val includeGeneral = JCheckBox("Include vendor labels and carousel settings")
    val selectedOnly get() = scope.selectedIndex == 1

    init {
        SettingsHelp.attach(scope, "Export all trackers or only the selected tracker.")
        SettingsHelp.attach(includeDisplay, "Include the current draft's appearance and inner ribbon settings.")
        SettingsHelp.attach(includeGeneral, "Include widget prefixes, displayed vendor and carousel preferences.")
        title = "Export Trackers"; setOKButtonText("Choose File…"); init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(scope, BorderLayout.NORTH); add(JPanel(GridLayout(0, 1)).apply { add(includeDisplay); add(includeGeneral) }, BorderLayout.CENTER)
        add(
            JLabel("<html>Exports tracker definitions from the current draft and any selected display settings.<br>Usage history, source paths, and credentials are excluded.</html>"),
            BorderLayout.SOUTH
        )
    }
}

class ImportTrackersDialog(
    parent: JComponent,
    private val configuration: TrackerConfiguration,
    private val existing: List<TrackerDefinition>,
    private val limit: Int = 32,
    private val profiles: List<ProviderProfile> = emptyList(),
) : DialogWrapper(parent, true) {
    private val strategy = JComboBox(arrayOf("Merge: append imported trackers", "Replace: use imported list"))
    val replace get() = strategy.selectedIndex == 1
    val applyDisplay = JCheckBox("Apply imported appearance settings", configuration.display != null).apply {
        isVisible = configuration.display != null
    }
    private val summary = JBTextArea(14, 62).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    val applyGeneral = JCheckBox("Apply imported vendor labels and carousel settings", false).apply { isVisible = configuration.general != null }
    private val mappings = configuration.bindings.associateWith { binding -> JComboBox((listOf(AccountChoice("", "Leave unconfigured")) + profiles.filter { it.vendor == binding.vendor }.map { AccountChoice(it.id, it.alias) }).toTypedArray()).apply {
        renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    } }
    fun accountMapping(): Map<String, String> = mappings.mapNotNull { (binding, choice) ->
        (choice.selectedItem as? AccountChoice)?.takeIf { it.id.isNotEmpty() }?.let { binding.reference to it.id }
    }.toMap()

    init {
        SettingsHelp.attach(strategy, "Append imported trackers or replace the current draft's tracker list.")
        SettingsHelp.attach(applyDisplay, "Apply the appearance settings included in this file.")
        SettingsHelp.attach(applyGeneral, "Apply imported widget prefixes and carousel preferences without enabling collection.")
        mappings.values.forEach { SettingsHelp.attach(it, "Choose a local account for each imported tracker, or leave it unconfigured.") }
        title = "Import Trackers — Preview"
        setOKButtonText("Import Trackers")
        init()
        strategy.addActionListener { updateSummary() }
        updateSummary()
    }

    private fun updateSummary() {
        val count = configuration.trackers.size + if (replace) 0 else existing.size
        isOKActionEnabled = count <= limit
        summary.text = buildString {
            append("${configuration.trackers.size} imported trackers; resulting list: $count / $limit.\n")
            append("The file contains tracker definitions and can include appearance, vendor labels and carousel settings. Usage history and source paths are excluded.\n")
            if (count > limit) append("Choose Replace or cancel: Merge exceeds the tracker limit.\n")
            append("Order is preserved. Apply/OK saves the draft; Cancel in Settings discards it.\n\n")
            configuration.trackers.forEach { append("• ${it.name}: ${it.amount} ${it.unit.label.lowercase()} (${it.mode.label}); quota pool: ${it.quotaPoolId.ifEmpty { "account default (legacy)" }}\n") }
            val duplicates = configuration.duplicateNames(if (replace) emptyList() else existing)
            if (duplicates.isNotEmpty()) append("\nDuplicate names (separate trackers): ${duplicates.joinToString()}\n")
            configuration.display?.let { append("\nOptional display preferences: ${it.datePattern}; ${it.clockFormat}\n") }
        }
        summary.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(strategy, BorderLayout.NORTH); add(JBScrollPane(summary), BorderLayout.CENTER); add(
        JPanel(GridLayout(0, 1)).apply {
            add(applyDisplay); add(applyGeneral)
            mappings.forEach { (binding, combo) -> add(JLabel("Map ${binding.vendor.presentationLabel}: ${binding.alias}").apply {
                SettingsHelp.attach(this, combo.toolTipText)
            }); add(combo) }
        },
        BorderLayout.SOUTH
    )
    }
}
