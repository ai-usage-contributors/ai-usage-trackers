package local.aiusagetrackers

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/** One warning indicator per widget; tracker colors never affect its fixed light-orange paint. */
object WidgetWarnings {
    fun messages(snapshot: UsageSnapshot, values: List<StatusValue>): List<String> = buildSet {
        addAll(snapshot.operationalIssues)
        addAll(snapshot.collection.operationalIssues)
        addAll(snapshot.analytics.operationalIssues)
        snapshot.trackers.forEach { addAll(it.issues) }
        if (snapshot.analytics.simultaneousRequests) add("Multiple request observations share a timestamp; order or overlap is unknown")
        values.forEach { value ->
            addAll(value.warnings)
            if (value.warnings.isEmpty() && value.state in setOf(StatusValueState.STALE, StatusValueState.UNAVAILABLE, StatusValueState.EXPIRED)) {
                add(value.description.ifBlank { "${value.label.ifEmpty { "Usage" }}: ${value.state.name.lowercase(java.util.Locale.ROOT)}" })
            }
        }
    }.filter { it.isNotBlank() && it !in snapshot.acknowledgedWarnings }

    val icon: Icon = object : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)
        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.translate(x, y)
                g.scale(iconWidth / 16.0, iconHeight / 16.0)
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.color = Color(0xFFB86C)
                val triangle = Path2D.Double().apply {
                    moveTo(8.0, 1.0); lineTo(15.0, 14.0); lineTo(1.0, 14.0); closePath()
                }
                g.fill(triangle)
                g.color = Color(0x5A3500)
                g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(8, 6, 8, 9)
                g.fillOval(7, 11, 2, 2)
            } finally {
                g.dispose()
            }
        }
    }
}
