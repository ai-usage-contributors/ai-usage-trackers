package local.aiusagetrackers

import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

/** Handles a complete shortcut sequence without moving focus into a replaceable vendor view. */
internal class WidgetNavigationKeys(
    private val active: () -> Boolean,
    private val navigate: (Int) -> Unit,
) : KeyEventDispatcher {
    private val arrows = mutableSetOf<Int>()
    private var suppressAltRelease = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val handled = when (e.id) {
            KeyEvent.KEY_RELEASED -> {
                if (e.keyCode == KeyEvent.VK_ALT && suppressAltRelease) {
                    suppressAltRelease = false
                    true
                } else arrows.remove(e.keyCode)
            }
            KeyEvent.KEY_PRESSED -> handlePress(e)
            else -> false
        }
        if (handled) e.consume()
        return handled
    }

    private fun handlePress(e: KeyEvent): Boolean {
        if (!e.isAltDown || e.isControlDown || e.isMetaDown || e.isShiftDown ||
            e.keyCode !in listOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT) || !active()) return false
        arrows.add(e.keyCode)
        suppressAltRelease = true
        navigate(if (e.keyCode == KeyEvent.VK_LEFT) -1 else 1)
        return true
    }

    fun reset() {
        arrows.clear()
        suppressAltRelease = false
    }
}
