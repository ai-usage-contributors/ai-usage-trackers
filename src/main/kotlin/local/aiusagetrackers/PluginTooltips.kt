package local.aiusagetrackers

import java.awt.Component
import java.awt.Container
import javax.swing.JComponent

object PluginTooltips {
    /** Older help remains available to assistive readers; hover text is one compact sentence. */
    fun concise(help: String): String {
        val plain = help.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
        val sentence = plain.split(Regex("(?<=[.!?])\\s+"), limit = 2).first()
        return sentence.split(' ').filter { it.isNotEmpty() }.joinToString(" ").trimEnd('.', '!', '?', ',', ';', ':') + "."
    }

    fun constrain(component: Component) {
        if (component is JComponent) component.toolTipText?.let { component.toolTipText = concise(it) }
        if (component is Container) component.components.forEach(::constrain)
    }
}
