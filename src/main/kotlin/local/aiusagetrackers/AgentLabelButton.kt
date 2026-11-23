package local.aiusagetrackers

import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Keeps label navigation independent of the metric ribbon and ordinary action clicks. */
internal open class AgentLabelButton(
    private val vertical: () -> Boolean,
    private val navigate: (Int) -> Unit,
) : JButton("loading"), IdeGlassPane.TopComponent {
    private var pressed: Point? = null
    private var dragged = false
    private var wheelRemainder = 0.0

    init {
        isRequestFocusEnabled = false
        addMouseWheelListener { e ->
            val rotation = e.preciseWheelRotation
            if (wheelRemainder * rotation < 0) wheelRemainder = 0.0
            wheelRemainder += rotation
            val steps = wheelRemainder.toInt()
            if (steps != 0) {
                wheelRemainder -= steps
                navigate(steps)
            }
            e.consume()
        }
    }

    override fun canBePreprocessed(e: MouseEvent): Boolean =
        e !is MouseWheelEvent && !SwingUtilities.isLeftMouseButton(e)

    override fun processMouseEvent(e: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            when (e.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    pressed = e.locationOnScreen
                    dragged = false
                }
                MouseEvent.MOUSE_RELEASED -> if (finishDrag(e)) return
            }
        }
        super.processMouseEvent(e)
    }

    private fun finishDrag(e: MouseEvent): Boolean {
        val origin = pressed
        pressed = null
        if (!dragged || origin == null) return false
        model.isArmed = false
        model.isPressed = false
        val delta = if (vertical()) origin.y - e.yOnScreen else origin.x - e.xOnScreen
        val steps = delta / JBUI.scale(30)
        if (steps != 0) navigate(steps)
        e.consume()
        return true
    }

    override fun processMouseMotionEvent(e: MouseEvent) {
        val origin = pressed
        if (e.id == MouseEvent.MOUSE_DRAGGED && origin != null) {
            val delta = if (vertical()) origin.y - e.yOnScreen else origin.x - e.xOnScreen
            if (abs(delta) >= JBUI.scale(3)) dragged = true
            if (dragged) {
                model.isArmed = false
                e.consume()
                return
            }
        }
        super.processMouseMotionEvent(e)
    }
}
