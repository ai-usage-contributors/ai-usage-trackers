package local.aiusagetrackers

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/** A full-width category whose controls keep their identity while hidden. */
class SettingsCategory(title: String, content: JComponent, expanded: Boolean = true) : JPanel(BorderLayout()) {
    val content: SettingsForm = content as? SettingsForm ?: SettingsForm().apply {
        addRow(javax.swing.JLabel(title), content)
    }
    val header = javax.swing.JToggleButton(title, expanded)
    val helpButton = javax.swing.JToggleButton(com.intellij.icons.AllIcons.General.ContextHelp).apply {
        isBorderPainted = false; isContentAreaFilled = false; isOpaque = false
        preferredSize = JBUI.size(24, 24)
    }
    var expanded: Boolean
        get() = header.isSelected
        set(value) {
            if (!value) {
                val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focus != null && SwingUtilities.isDescendingFrom(focus, content)) header.requestFocusInWindow()
            }
            header.isSelected = value
            content.isVisible = value
            header.icon = if (value) com.intellij.icons.AllIcons.General.ArrowDown else com.intellij.icons.AllIcons.General.ArrowRight
            header.accessibleContext.accessibleDescription = if (value) "Expanded category" else "Collapsed category"
            revalidate(); repaint()
        }
    init {
        border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(javax.swing.UIManager.getColor("Component.borderColor") ?: com.intellij.ui.JBColor.GRAY),
            JBUI.Borders.empty(8))
        header.font = header.font.deriveFont(Font.BOLD, header.font.size2D + 2f)
        header.horizontalAlignment = javax.swing.SwingConstants.LEADING
        header.isContentAreaFilled = false
        header.border = JBUI.Borders.empty(4)
        header.addActionListener { this.expanded = header.isSelected }
        header.getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "toggleCategory")
        header.actionMap.put("toggleCategory", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { header.doClick() }
        })
        SettingsHelp.attach(header, "Expand or collapse $title settings.")
        SettingsHelp.attach(helpButton, "Show or hide help beside these settings.")
        helpButton.accessibleContext.accessibleName = "Help for $title"
        helpButton.margin = JBUI.insets(2)
        helpButton.addActionListener {
            this.content.showHelp(helpButton.isSelected)
            if (helpButton.isSelected) this.expanded = true
            revalidate(); repaint()
        }
        add(JPanel(BorderLayout()).apply { add(header, BorderLayout.CENTER); add(helpButton, BorderLayout.EAST) }, BorderLayout.NORTH)
        add(this.content, BorderLayout.CENTER)
        this.expanded = expanded
    }
}

/** Three aligned columns: label, input, and optional row-specific help. */
open class SettingsForm : JPanel(GridBagLayout()) {
    private var active: SettingsForm? = null
    private var rows = 0
    var showingHelp: Boolean = false
        private set
    fun showHelp(visible: Boolean) {
        showingHelp = visible
        fun visit(component: Component) {
            if (component is SettingsCategory) return
            if (component is SettingsForm && component !== this) { component.showHelp(visible); return }
            if (component is SettingHelpCell) {
                component.isVisible = visible && component.control.isVisible
            } else if (component is Container) component.components.forEach(::visit)
        }
        components.forEach(::visit)
        revalidate(); repaint()
    }
    fun addBlock(block: JComponent) {
        add(block, GridBagConstraints().apply {
            gridx = 0; gridy = rows++; gridwidth = 3; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
        })
    }
    fun section(title: String): SettingsForm {
        val form = SettingsForm()
        addCategory(SettingsCategory(title, form))
        active = form
        return form
    }
    fun addCategory(category: SettingsCategory) {
        add(category, GridBagConstraints().apply {
            gridx = 0; gridy = rows++; gridwidth = 3; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL; anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(0, 0, 12, 0)
        })
    }
    fun addRow(label: JComponent, control: JComponent) {
        if (control is javax.swing.JSeparator) {
            section((label as javax.swing.JLabel).text)
            return
        }
        active?.let { it.addRow(label, control); return }
        SettingsHelp.row(label, control)
        val help = SettingHelpCell(label, control).apply { isVisible = showingHelp && control.isVisible }
        control.putClientProperty("settingsRowHelp", help)
        val row = rows++
        val pair = label to control
        label.putClientProperty("settingsRow", pair); control.putClientProperty("settingsRow", pair)
        add(label, GridBagConstraints().apply {
            gridx = 0; gridy = row; anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(3, 4)
        })
        add(control, GridBagConstraints().apply {
            gridx = 1; gridy = row; weightx = 0.6; fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(3, 4)
        })
        add(help, GridBagConstraints().apply {
            gridx = 2; gridy = row; weightx = 0.4; fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST; insets = JBUI.insets(3, 12, 3, 4)
        })
    }
    override fun removeAll() { super.removeAll(); active = null; rows = 0 }
    fun enableFields(enabled: Boolean) {
        fun visit(component: Component) {
            if (component is SettingsCategory) { visit(component.content); return }
            if (component is SettingHelpCell) return
            component.isEnabled = enabled
            if (component is Container) component.components.forEach(::visit)
        }
        components.forEach(::visit)
    }
}

/** Reveal the tab and all collapsed ancestors before requesting focus on an invalid control. */
fun revealSetting(control: JComponent) {
    setSettingVisible(control, true)
    var child: Component = control
    var parent = child.parent
    while (parent != null) {
        parent.isVisible = true
        if (parent is SettingsCategory) parent.expanded = true
        if (parent is JTabbedPane) parent.selectedComponent = child
        child = parent; parent = parent.parent
    }
    SwingUtilities.invokeLater {
        control.scrollRectToVisible(Rectangle(control.size))
        control.requestFocusInWindow()
    }
}

fun settingsTabs(vararg tabs: Pair<String, JComponent>): JTabbedPane = JTabbedPane().apply {
    preferredSize = JBUI.size(760, 560)
    minimumSize = JBUI.size(0, 80)
    for ((title, content) in tabs) {
        val holder = SettingsTabContent(content)
        val focusScroll = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                holder.scrollRectToVisible(
                    SwingUtilities.convertRectangle(
                        e.component.parent,
                        e.component.bounds,
                        holder
                    )
                )
            }
        }

        fun follow(component: Component) {
            component.addFocusListener(focusScroll)
            if (component is Container) component.components.forEach(::follow)
        }
        follow(content)
        SettingsHelp.neutralSurface(holder)
        PluginTooltips.constrain(content)
        addTab(title, JBScrollPane(holder).apply {
            verticalScrollBar.unitIncrement = JBUI.scale(24)
            accessibleContext.accessibleName = "$title settings"
        })
    }
}

/** Width follows the viewport; long forms scroll vertically instead of stealing page space. */
class SettingsTabContent(content: JComponent) : JPanel(BorderLayout()), javax.swing.Scrollable {
    val footer = JPanel(BorderLayout())

    init {
        isFocusable = true
        add(
            JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH); add(footer, BorderLayout.SOUTH) },
            BorderLayout.NORTH
        )
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
    override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(24)

    override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int): Int =
        maxOf(JBUI.scale(24), r.height - JBUI.scale(24))
}

/** One preview instance moves between the active tab's footer and a reserved dock. */
class SettingsPreviewLayout(
    private val tabs: JTabbedPane, private val preview: JComponent,
    initiallyPinned: Boolean = com.intellij.openapi.components.service<UsageSettings>().state.previewPinned,
    private val savePin: (Boolean) -> Unit = {
        com.intellij.openapi.components.service<UsageSettings>().setPreviewPinned(it)
    },
) : JPanel(BorderLayout()) {
    private val pin = javax.swing.JCheckBox("Pin preview", initiallyPinned)
    private val dock = JPanel(BorderLayout())
    private val previewHost = JPanel(BorderLayout()).apply {
        add(pin, BorderLayout.NORTH); add(preview, BorderLayout.CENTER)
        border = JBUI.Borders.emptyTop(12)
    }

    init {
        add(tabs, BorderLayout.CENTER); add(dock, BorderLayout.SOUTH)
        pin.accessibleContext.accessibleDescription = "Keep the preview visible at the bottom while settings scroll."
        SettingsHelp.attach(pin, "Keep the preview visible while settings scroll.")
        pin.addActionListener {
            savePin(pin.isSelected)
            relocate()
        }
        tabs.addChangeListener { relocate() }
        SettingsHelp.neutralSurface(previewHost)
        relocate()
    }

    private fun relocate() {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        previewHost.parent?.remove(previewHost)
        val scroll = tabs.selectedComponent as JBScrollPane
        val footer = (scroll.viewport.view as SettingsTabContent).footer
        (if (pin.isSelected) dock else footer).add(previewHost, BorderLayout.CENTER)
        revalidate(); repaint()
        if (focus != null && SwingUtilities.isDescendingFrom(focus, previewHost)) focus.requestFocusInWindow()
    }
}

fun validateSetting(control: JComponent, validation: () -> Unit) {
    try { validation() } catch (failure: Exception) {
        revealSetting(control)
        throw failure
    }
}

/** Hide a complete row, including wrappers, without discarding its controls or draft text. */
fun setSettingVisible(control: JComponent, visible: Boolean, fallback: JComponent? = null) {
    var owner: Component? = control
    var row: Pair<*, *>? = null
    while (owner is JComponent) {
        row = owner.getClientProperty("settingsRow") as? Pair<*, *>
        if (row != null) break
        owner = owner.parent
    }
    val parts = row?.let { listOf(it.first as JComponent, it.second as JComponent) } ?: listOf(control)
    val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    if (!visible && focus != null && parts.any { focus === it || SwingUtilities.isDescendingFrom(focus, it) }) {
        fallback?.requestFocusInWindow()
    }
    parts.forEach { it.isVisible = visible }
    val help = (row?.second as? JComponent)?.getClientProperty("settingsRowHelp") as? SettingHelpCell
    help?.isVisible = visible && (help.parent as? SettingsForm)?.showingHelp == true
    var parent = control.parent
    while (parent != null) {
        if (parent is SettingsCategory) {
            parent.isVisible = parent.content.components.any { it.isVisible }
        }
        parent.revalidate(); parent.repaint()
        parent = parent.parent
    }
}
