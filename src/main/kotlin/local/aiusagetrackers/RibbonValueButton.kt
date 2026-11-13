package local.aiusagetrackers

import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.SwingUtilities

/** A fresh button can also be used in the chooser without reparenting the ribbon's button. */
internal class RibbonValueButton(
    val value: StatusValue,
    font: Font,
    val theme: Color,
    private val ribbonGesture: Boolean,
    action: () -> Unit,
) : JButton(), IdeGlassPane.TopComponent {
    private var prefix = ""
    init {
        this.font = font
        isOpaque = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(0, 1)
        horizontalAlignment = LEADING
        getAccessibleContext().accessibleName = value.accessibleText
        toolTipText = value.accessibleText
        foreground = if (WidgetAppearance.usesHtml(value)) theme else StatusValueStyle.forDisplay(value.style).color(value, theme)
        addActionListener { action() }
        render()
    }

    override fun canBePreprocessed(e: MouseEvent): Boolean = !ribbonGesture || !SwingUtilities.isLeftMouseButton(e)
    fun measuredWidth(): Int = getFontMetrics(font).stringWidth(value.displayText()) + insets.left + insets.right
    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
        width = measuredWidth() + getFontMetrics(font).stringWidth(prefix)
    }

    fun render(prefix: String = "", maximum: Int = Int.MAX_VALUE) {
        this.prefix = prefix
        val plain = prefix + value.displayText()
        val metrics = getFontMetrics(font)
        val available = (maximum - insets.left - insets.right).coerceAtLeast(0)
        val clipped = metrics.stringWidth(plain) > available
        // Truncate the textual parts before producing HTML, preserving label/value colors.
        var shortened = value
        if (clipped) {
            var length = plain.length
            while (length > 0 && metrics.stringWidth(plain.take(length) + "…") > available) {
                length = plain.offsetByCodePoints(length, -1)
            }
            val fullLabel = if (value.displayText() == value.text) "" else "${value.label}: "
            val visible = (plain.take(length) + "…").removePrefix(prefix)
            shortened = if (fullLabel.isNotEmpty() && visible.startsWith(fullLabel))
                value.copy(text = visible.removePrefix(fullLabel))
            else value.copy(label = "", text = visible)
        }
        putClientProperty("html.disable", !WidgetAppearance.usesHtml(shortened))
        text = WidgetAppearance.text(shortened, theme, prefix)
    }
}
