package local.aiusagetrackers

import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal const val FIVE_HOUR_PLAN_REQUIRED =
    "Select a Codex quota scheme that includes a five-hour limit to show this share."

internal class TokenMetricSettings(changed: () -> Unit) {
    private data class OrderChoice(val order: TokenMetricOrder, val label: String) {
        override fun toString(): String = label
    }

    private val order = JComboBox<OrderChoice>()
    private val prefixes = TokenMetric.entries.associateWith { JTextField() }
    private var fiveHourAvailable = true
    private var updatingOrder = false

    init {
        updateOrder(TokenMetricOrder.TOKENS_WEEKLY_FIVE_HOUR)
        order.addActionListener { if (!updatingOrder) changed() }
        prefixes.values.forEach { field ->
            field.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = changed()
                override fun removeUpdate(e: DocumentEvent) = changed()
                override fun changedUpdate(e: DocumentEvent) = changed()
            })
        }
    }

    fun addRows(form: SettingsForm) {
        SettingsHelp.attach(order, "Choose the order of the token count and any enabled quota shares.")
        form.addRow(JLabel("Metric order"), order)
        prefixes.forEach { (metric, field) ->
            val label = "${metric.label} prefix"
            SettingsHelp.attach(field, "Optional text before the ${metric.label.lowercase()} value. Spaces are preserved. Use up to 40 characters.")
            form.addRow(JLabel(label), field)
        }
    }

    fun updateFiveHourAvailability(share: JCheckBox, purpose: String, unavailableReason: String?) {
        val enabled = unavailableReason == null
        val help = unavailableReason ?: purpose
        share.isEnabled = enabled
        SettingsHelp.attach(share, help)
        val row = share.getClientProperty("settingsRow") as? Pair<*, *>
        (row?.first as? JLabel)?.let { SettingsHelp.attach(it, help) }
        if (fiveHourAvailable == enabled) return
        val selected = selectedOrder()
        fiveHourAvailable = enabled
        updateOrder(selected)
        setSettingVisible(prefixes.getValue(TokenMetric.FIVE_HOUR), enabled, order)
    }

    private fun selectedOrder(): TokenMetricOrder = (order.selectedItem as OrderChoice).order

    private fun updateOrder(selected: TokenMetricOrder) {
        // Keep the hidden metric in its saved position when the visible pair is reordered.
        val fiveHourPosition = selected.metrics.indexOf(TokenMetric.FIVE_HOUR)
        val choices = TokenMetricOrder.entries.filter {
            fiveHourAvailable || it.metrics.indexOf(TokenMetric.FIVE_HOUR) == fiveHourPosition
        }.map { candidate ->
            val label = candidate.metrics.filter { fiveHourAvailable || it != TokenMetric.FIVE_HOUR }
                .joinToString(" → ") { it.label }
            OrderChoice(candidate, label)
        }
        updatingOrder = true
        try {
            updateSettingsChoices(order, choices, choices.first { it.order == selected })
        } finally {
            updatingOrder = false
        }
    }

    fun load(layout: TokenMetricLayout) {
        updateOrder(layout.order)
        prefixes.getValue(TokenMetric.TOKENS).text = layout.tokenPrefix
        prefixes.getValue(TokenMetric.WEEKLY).text = layout.weeklyPrefix
        prefixes.getValue(TokenMetric.FIVE_HOUR).text = layout.fiveHourPrefix
    }

    fun value(): TokenMetricLayout = TokenMetricLayout(
        selectedOrder(),
        prefixes.getValue(TokenMetric.TOKENS).text,
        prefixes.getValue(TokenMetric.WEEKLY).text,
        prefixes.getValue(TokenMetric.FIVE_HOUR).text,
    )

    fun validateDraft() {
        prefixes.values.forEach { field -> validateSetting(field) { TokenMetricLayout.validatePrefix(field.text) } }
    }
}
