package local.aiusagetrackers

import java.awt.Container
import java.awt.Point

/** Null means AWT temporarily cannot resolve the pointer's display device. */
internal object WidgetPointer {
    fun inside(component: Container, lookup: () -> Point? = { component.getMousePosition(true) }): Boolean? {
        if (!component.isShowing) return false
        return try {
            lookup() != null
        } catch (_: ArrayIndexOutOfBoundsException) {
            // A display change can leave AWT's native screen index ahead of its device array.
            null
        }
    }

    fun anyInside(vararg readings: Boolean?): Boolean? = when {
        readings.any { it == true } -> true
        readings.any { it == null } -> null
        else -> false
    }
}
