package local.aiusagetrackers

import java.awt.Color
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JLabel

class PaletteEditor(private val changed: () -> Unit) : SettingsForm() {
    private var draft = DisplayPreferences().palette
    private val above = javax.swing.JTextField("2", 8)
    private val warning = javax.swing.JTextField("3", 8)
    private val sample = JLabel()
    private val buttons = listOf("Neutral", "Below goal", "At goal", "Above goal", "Warning").mapIndexed { index, name ->
        JButton().apply {
            toolTipText = "Choose the shared $name color for all displayed usage values."
            addActionListener {
                val parts = draft.split(',').toMutableList()
                val selected = JColorChooser.showDialog(this, "$name usage color", parts[index].takeIf { it.isNotEmpty() }?.let(Color::decode) ?: foreground)
                if (selected != null) {
                    parts[index] = "#%06X".format(selected.rgb and 0xFFFFFF)
                    reset(parts.joinToString(",")); changed()
                }
            }
        }.also { addRow(JLabel(name), it) }
    }

    init {

        addRow(JLabel("Defaults"), JButton("Reset palette").apply {
            toolTipText = "Restore default colors and quota multiples, following the theme for neutral values."
            addActionListener { reset(DisplayPreferences().palette); resetMultipliers("2", "3"); changed() }
        })
        addRow(JLabel("Above goal (×)"), above)
        addRow(JLabel("Warning (×)"), warning)
        addRow(JLabel("Gradient samples"), sample)
        val listener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = updateSample()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = updateSample()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = updateSample()
        }
        above.document.addDocumentListener(listener)
        warning.document.addDocumentListener(listener)
        reset(draft)
    }

    fun validateDraft() {
        validateSetting(above) {
            require(aboveMultiplier().toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ONE } == true) { "Above goal must be greater than 1" }
        }
        validateSetting(warning) { UsagePalette.validateBreakpoints(aboveMultiplier(), warningMultiplier()) }
    }
    fun spec(): String = draft
    fun quotaApplicable(value: Boolean) {
        buttons.drop(1).forEach { setSettingVisible(it, value, buttons.first()) }
        listOf(above, warning, sample).forEach { setSettingVisible(it, value, buttons.first()) }
    }
    fun aboveMultiplier(): String = above.text.trim()
    fun warningMultiplier(): String = warning.text.trim()
    fun resetMultipliers(a: String, w: String) { above.text = a; warning.text = w }
    private fun updateSample(notify: Boolean = true) {
        sample.text = try {
            val a = aboveMultiplier(); val w = warningMultiplier()
            UsagePalette.validateBreakpoints(a, w)
            val anchors = listOf(java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE, a.toBigDecimal(), w.toBigDecimal())
            val ratios = anchors.zipWithNext().flatMap { (x, y) -> listOf(x, (x + y).divide(java.math.BigDecimal(2))) } + anchors.last()
            "<html>" + ratios.joinToString(" ") {
                val c = UsagePalette.parse(draft).color(it, a, w)
                "<font color='#%06X'>%sx</font>".format(c.rgb and 0xFFFFFF, it.stripTrailingZeros().toPlainString())
            } + "</html>"
        } catch (_: IllegalArgumentException) { "Enter multiples satisfying 1 < above < warning" }
        if (notify) changed()
    }
    fun reset(spec: String) {
        UsagePalette.parse(spec)
        draft = spec
        updateSample(false)
        draft.split(',').forEachIndexed { index, color ->
            buttons[index].text = color.ifEmpty { "IDE theme" }
            buttons[index].foreground = color.takeIf { it.isNotEmpty() }?.let(Color::decode) ?: javax.swing.UIManager.getColor("Button.foreground")
        }
    }
}
