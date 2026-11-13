package local.aiusagetrackers

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A widget-owned, non-focus-stealing hover chooser using only public popup APIs. */
internal class RibbonEntryPopup(private val project: Project, private val ribbon: UsageRibbon) {
    private var popup: JBPopup? = null
    private var scroller: JScrollPane? = null
    private var signature: List<Any> = emptyList()
    private var suppressed = false
    private val hoverLifetime = HoverLifetime()
    private val monitor = Timer(50) {
        val current = popup
        if (current != null) {
            val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            val inside = ribbon.getMousePosition(true) != null || current.content.getMousePosition(true) != null ||
                focused?.let { SwingUtilities.isDescendingFrom(it, current.content) } == true
            if (hoverLifetime.shouldClose(inside)) close()
        }
    }

    init {
        ribbon.hoverRequested = { show() }
        ribbon.hoverEnded = { suppressed = false }
        ribbon.closeChooser = { close() }
        ribbon.isFocusable = true
        ribbon.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("alt DOWN"), "entries")
        ribbon.actionMap.put("entries", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) { suppressed = false; show() }
        })
    }

    private fun show() {
        if (suppressed || popup != null || !ribbon.vertical || !ribbon.isShowing || ribbon.chooserRows().isEmpty()) return
        val rows = JPanel(null)
        val scroll = JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
            border = JBUI.Borders.empty()
        }
        scroller = scroll
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setProject(project).setFocusable(true).setRequestFocus(ribbon.isFocusOwner)
            .setResizable(false).setMovable(false).setShowBorder(false)
            .setCancelOnClickOutside(true).setCancelOnWindowDeactivation(true)
            .setCancelKeyEnabled(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    popup = null; scroller = null; ribbon.chooserActive = false; monitor.stop()
                    suppressed = true; signature = emptyList()
                }
            }).createPopup()
        popup = created
        hoverLifetime.shouldClose(true)
        ribbon.chooserActive = true
        refresh()
        created.showInScreenCoordinates(ribbon, location(created.size.height))
        if (ribbon.isFocusOwner) {
            (scroll.viewport.view as JPanel).components.firstOrNull()?.requestFocusInWindow()
        }
        monitor.start()
    }

    private fun location(height: Int): Point = ribbon.locationOnScreen.apply { y -= height }

    fun refresh() {
        val current = popup ?: return
        if (!ribbon.vertical || !ribbon.isShowing || ribbon.chooserRows().isEmpty()) { close(); return }
        val scroll = scroller ?: return
        val width = ribbon.width
        val screen = ribbon.graphicsConfiguration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(ribbon.graphicsConfiguration)
        val heightAvailable = (ribbon.locationOnScreen.y - screen.y - insets.top).coerceAtLeast(1)
        var rows = ribbon.chooserRows()
        val rowHeight = rows.flatten().maxOf { it.preferredSize.height }.coerceAtLeast(JBUI.scale(22))
        val rowWidth = if (rows.size * rowHeight > heightAvailable)
            (width - scroll.verticalScrollBar.preferredSize.width).coerceAtLeast(1) else width
        if (rowWidth < width) ribbon.reserveChooserWidth(scroll.verticalScrollBar.preferredSize.width)
        if (rowWidth != width) rows = ribbon.chooserRows(rowWidth)
        val key = buildList {
            add(width); add(heightAvailable); add(ribbon.stepDirection)
            rows.forEach { row -> add(row.map { Triple(it.value, it.font, it.theme) }) }
        }
        if (key == signature) return
        val focusedId = (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? RibbonValueButton)?.value?.id
        signature = key
        val rowsPanel = buildRows(rows, rowWidth, rowHeight) { id ->
            ribbon.selectEntry(id); close()
        }
        val oldPosition = scroll.viewport.viewPosition
        scroll.setViewportView(rowsPanel)
        scroll.viewport.viewPosition = oldPosition
        val size = Dimension(width, minOf(heightAvailable, rows.size * rowHeight))
        scroll.preferredSize = size
        current.size = size
        if (current.isVisible) current.setLocation(location(size.height))
        rowsPanel.revalidate(); rowsPanel.repaint()
        if (focusedId != null) rowsPanel.components.filterIsInstance<RibbonValueButton>().firstOrNull { it.value.id == focusedId }?.requestFocusInWindow()
    }

    fun close() { popup?.cancel(); monitor.stop() }

    companion object {
        internal class HoverLifetime(private val nanoTime: () -> Long = System::nanoTime) {
            private var outsideSince: Long? = null
            fun shouldClose(inside: Boolean): Boolean {
                if (inside) { outsideSince = null; return false }
                val now = nanoTime()
                val start = outsideSince ?: now.also { outsideSince = it }
                return now - start >= 200_000_000L
            }
        }

        internal fun buildRows(rows: List<List<RibbonValueButton>>, width: Int, height: Int, select: (String) -> Unit): JPanel {
            val panel = JPanel(null)
            val buttons = mutableListOf<RibbonValueButton>()
            rows.forEachIndexed { rowIndex, row ->
                var x = if (row.size == 1) ((width - row.single().measuredWidth()) / 2).coerceAtLeast(0) else 0
                row.forEachIndexed { index, source ->
                    val button = RibbonValueButton(source.value, source.font, source.theme, false) { select(source.value.id) }
                    button.render(if (index == 0) "" else "· ", width)
                    if (index > 0) x += JBUI.scale(2)
                    val buttonWidth = minOf(width, button.preferredSize.width)
                    button.setBounds(x, rowIndex * height, buttonWidth, height)
                    x += buttonWidth; buttons.add(button); panel.add(button)
                }
            }
            installNavigation(buttons)
            panel.preferredSize = Dimension(width, rows.size * height)
            return panel
        }

        internal fun installNavigation(buttons: List<RibbonValueButton>) {
            buttons.forEachIndexed { index, button ->
                fun bind(key: String, action: () -> Unit) {
                    button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key)
                    button.actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
                }
                for (key in listOf("RIGHT", "DOWN")) bind(key) {
                    buttons[(index + 1) % buttons.size].apply { requestFocusInWindow(); scrollRectToVisible(java.awt.Rectangle(size)) }
                }
                for (key in listOf("LEFT", "UP")) bind(key) {
                    buttons[Math.floorMod(index - 1, buttons.size)].apply { requestFocusInWindow(); scrollRectToVisible(java.awt.Rectangle(size)) }
                }
                bind("ENTER") { button.doClick() }
            }
        }
    }
}
