package local.aiusagetrackers

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

object WidgetAppearance {
    /** Resolve dynamic theme colors now so cached rendering detects later theme changes. */
    fun foreground(): Color = Color(com.intellij.util.ui.UIUtil.getLabelForeground().rgb, true)

    fun usesHtml(value: StatusValue): Boolean = value.quotaColorEnabled || value.palette?.let { UsagePalette.parse(it).neutral != null } == true
    fun font(preferences: DisplayPreferences, fallback: Font): Font {
        val base = UIManager.getFont("Label.font") ?: fallback
        return if (preferences.widgetFontSize == 0) base else base.deriveFont(JBUI.scale(preferences.widgetFontSize).toFloat())
    }

    /** Only quota-goal colors leave the label neutral; legacy styles retain their existing behavior. */
    fun text(value: StatusValue, theme: Color, prefix: String = ""): String {
        if (!usesHtml(value)) return prefix + value.displayText()
        val color = StatusValueStyle.forDisplay(value.style).color(value, theme)
        val label = if (value.label.isEmpty() || StatusValueStyle.forDisplay(value.style).hideLabel) "" else "${value.label}: "
        val amount = value.displayText().removePrefix(label)
        val hex = "#%06X".format(color.rgb and 0xFFFFFF)
        return "<html>${escape(prefix + label)}<font color=\"$hex\">${escape(amount)}</font></html>"
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
