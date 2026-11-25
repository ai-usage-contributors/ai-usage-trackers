package local.aiusagetrackers

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

object WidgetAppearance {
    /** Resolve dynamic theme colors now so cached rendering detects later theme changes. */
    fun foreground(): Color = Color(com.intellij.util.ui.UIUtil.getLabelForeground().rgb, true)

    fun usesHtml(value: StatusValue): Boolean = value.quotaColorEnabled || value.fiveHourColor != null ||
        value.palette?.let { UsagePalette.parse(it).neutral != null } == true
    fun font(preferences: DisplayPreferences, fallback: Font): Font {
        val base = UIManager.getFont("Label.font") ?: fallback
        return if (preferences.widgetFontSize == 0) base else base.deriveFont(JBUI.scale(preferences.widgetFontSize).toFloat())
    }

    /** Only quota-goal colors leave the label neutral; legacy styles retain their existing behavior. */
    fun text(value: StatusValue, theme: Color, prefix: String = ""): String {
        if (!usesHtml(value)) return prefix + value.displayText()
        val style = StatusValueStyle.forDisplay(value.style)
        val base = style.color(value, theme)
        val label = if (value.label.isEmpty() || style.hideLabel) "" else "${value.label}: "
        val neutral = value.palette?.let { UsagePalette.parse(it).neutral } ?: theme
        val ranges = buildList {
            value.neutralRanges.forEach { add(it to neutral) }
            value.fiveHourColor?.let { span ->
                add((span.start until span.start + span.length) to style.color(value, theme, span.ratio, quotaEnabled = true))
            }
        }
        return buildString {
            append("<html>").append(escape(prefix + label))
            var from = 0
            ranges.sortedBy { it.first.first }.forEach { (range, color) ->
                val end = range.last + 1
                append(colored(value.text.substring(from, range.first), base))
                append(colored(value.text.substring(range.first, end), color))
                from = end
            }
            append(colored(value.text.substring(from), base)).append("</html>")
        }
    }

    private fun colored(text: String, color: Color): String {
        if (text.isEmpty()) return ""
        val hex = "#%06X".format(color.rgb and 0xFFFFFF)
        return "<font color=\"$hex\">${escape(text)}</font>"
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
