package local.aiusagetrackers

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*

class UsageWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "CodexDailyTokens"
    override fun getDisplayName(): String = "AI Usage Trackers"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = UsageWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class UsageWidget(private val project: Project) : CustomStatusBarWidget, Disposable {
    private val views = linkedMapOf<UsageVendor, VendorUsageWidget>()
    private val carousel = VendorCarousel().apply {
        transitionChanged = { active -> views.values.forEach { it.pauseForTransition(active) } }
    }
    private val root = JPanel(java.awt.BorderLayout()).apply { isOpaque = false; add(carousel) }
    private var timer: Timer? = null
    private var disposed = false
    override fun ID() = "CodexDailyTokens"
    override fun getComponent(): JComponent = root
    override fun install(statusBar: StatusBar) {
        update(); timer = Timer(250) { if (!disposed) update() }.also { it.start() }
    }

    private fun update() {
        val config = service<UsageSettings>().providers
        val visible = config.display.visible(config.eligible())
        for (vendor in visible) views.getOrPut(vendor) { VendorUsageWidget(project, vendor) }.updatePresentation()
        carousel.paused =
            { views.values.any { it.hasOpenPopup() } || MenuSelectionManager.defaultManager().selectedPath.isNotEmpty() }
        carousel.configure(visible.associateWith { views.getValue(it).component }, config.display)
        if (visible.isEmpty()) {
            if (root.getComponent(0) === carousel) {
                root.removeAll(); root.add(JButton("No vendors enabled — Settings").apply {
                    addActionListener {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, UsageConfigurable::class.java)
                    }
                }); root.revalidate(); root.repaint()
            }
        } else if (root.getComponent(0) !== carousel) {
            root.removeAll(); root.add(carousel); root.revalidate(); root.repaint()
        }
    }

    override fun dispose() {
        disposed = true
        val cleanup =
            Runnable { timer?.stop(); carousel.close(); views.values.forEach { Disposer.dispose(it) }; views.clear() }
        if (SwingUtilities.isEventDispatchThread()) cleanup.run() else ApplicationManager.getApplication()
            .invokeLater(cleanup)
    }
}

private class VendorUsageWidget(private val project: Project, private val vendor: UsageVendor) : CustomStatusBarWidget,
    Disposable {
    private var transitioning = false
    fun pauseForTransition(value: Boolean) {
        transitioning = value; ribbon.overlayPaused = value || hasOpenPopup()
    }

    private var timer: Timer? = null
    private var infoPopup: JBPopup? = null
    private var disclaimersPage: DisclaimersPage? = null
    private val disposed = AtomicBoolean()
    private var renderedValues: List<StatusValue>? = null
    private val fixedPanel = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(2), 0)).apply { isOpaque = false }
    private val ribbon = UsageRibbon()
    private val ribbonPopup = RibbonEntryPopup(project, ribbon)
    private val resizeState = RibbonResizeState()
    private var resizePopup: JBPopup? = null
    private var renderedFont: java.awt.Font? = null
    private var renderedTheme: java.awt.Color? = null
    private var renderedScrollMode: RibbonScrollMode? = null
    private var renderedPins: Set<String> = emptySet()
    private val usage get() = service<UsageService>()
    private val settings get() = service<UsageSettings>()
    private val textButton = object : JButton("loading") {
        override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
            // The themed JButton UI reserves a minimum width for ordinary dialog buttons.
            // Status text needs only its measured text width, even for short totals.
            width = getFontMetrics(font).stringWidth(text.orEmpty()) + insets.left + insets.right
        }
    }.apply {
        putClientProperty("html.disable", true)
        isOpaque = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(0, 2, 0, 0)
        horizontalAlignment = JButton.LEADING
        accessibleContext.accessibleName = "AI usage actions"
        addActionListener { showActions() }
    }
    private val infoButton = JToggleButton(AllIcons.General.Information).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2)
        preferredSize = JBUI.size(22, 22)
        toolTipText = "Open detailed usage information for all trackers and the current quota."
        accessibleContext.accessibleName = "AI Usage Info"
        addActionListener { if (resizeState.pending != null) showResizeBubble() else toggleInfo() }
    }
    private val panel = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(2), 0)).apply {
        isOpaque = false
        add(textButton)
        add(fixedPanel)
        add(ribbon)

        add(infoButton)
    }

    override fun ID(): String = "CodexDailyTokens"
    override fun getComponent(): JComponent = panel
    override fun install(statusBar: StatusBar) {
        // Collection belongs to the shared application service; this timer only renders its cached snapshot.
        updatePresentation()
        timer = Timer(250) { if (!disposed.get()) updatePresentation() }.also { it.start() }
    }

    fun hasOpenPopup(): Boolean =
        infoPopup?.isDisposed == false || resizePopup?.isDisposed == false || ribbon.chooserActive

    fun updatePresentation() {
        val config = settings.providers
        val profile = config.account(vendor) ?: return
        val snapshot = usage.snapshot(profile.id)
        ribbon.overlayPaused =
            transitioning || hasOpenPopup() || MenuSelectionManager.defaultManager().selectedPath.isNotEmpty()
        val options = profile.analytics
        val display = settings.display
        val values = WidgetText.values(snapshot, display, settings.showLastRequest, options)
        textButton.text = "${config.label(vendor)}:"
        textButton.accessibleContext.accessibleName = "${config.label(vendor)} usage actions"
        val widgetFont = WidgetAppearance.font(display, textButton.font)
        val fontChanged = textButton.font != widgetFont
        textButton.font = widgetFont
        val pins = buildSet {
            addAll(settings.trackers.filter { it.fixedInWidget() }
                .map { it.id }); addAll(display.fixedAuxiliary.split(",")); add("state")
        }
        ribbon.configure(
            display.ribbonWidth,
            display.ribbonSpeed,
            display.ribbonScrollMode,
            display.ribbonHorizontalStepIntervalSeconds,
            display.ribbonVerticalStepIntervalSeconds,
            display.ribbonVerticalStepDirection,
            display.ribbonAutoExpand
        )
        updateRibbonEntries(values, pins, display, widgetFont)
        ribbonPopup.refresh()
        if (panel.isShowing && resizeState.observe(ribbon.widthExpansionWarning)) showResizeBubble()
        if (fontChanged) {
            panel.revalidate(); panel.repaint()
        }
        textButton.toolTipText = "Open usage actions, settings, and detailed information for the displayed trackers."
        updateWarningButton(snapshot, values, config.label(vendor))
    }

    private fun updateRibbonEntries(values: List<StatusValue>, pins: Set<String>, display: DisplayPreferences, widgetFont: java.awt.Font) {
        if (renderedValues != values || pins != renderedPins || renderedScrollMode != display.ribbonScrollMode ||
            renderedFont != widgetFont || renderedTheme != textButton.foreground
        ) {
            renderedPins = pins
            renderedScrollMode = display.ribbonScrollMode
            renderedValues = values
            renderedFont = widgetFont
            renderedTheme = textButton.foreground
            fixedPanel.removeAll()
            values.filter { it.id in pins }.forEachIndexed { index, value ->
                fixedPanel.add(RibbonValueButton(value, widgetFont, textButton.foreground, false, ::showActions).apply {
                    render(if (index == 0) "" else "· ")
                })
            }
            val moving = values.filter { it.id !in pins }
            val repeated =
                List(if (display.ribbonScrollMode == RibbonScrollMode.CONTINUOUS) 2 else 1) { moving }.flatten()
            val buttons = repeated.map { value ->
                RibbonValueButton(value, widgetFont, textButton.foreground, true) { if (!ribbon.dragged) showActions() }
            }
            buttons.distinctBy { it.value.id }
                .forEach { settings.recordRibbonWidth(it.measuredWidth(), it.value.displayText()) }
            ribbon.setEntries(buttons)
            panel.revalidate(); panel.repaint()
        }
    }

    private fun updateWarningButton(snapshot: UsageSnapshot, values: List<StatusValue>, label: String) {
        val warnings = WidgetWarnings.messages(snapshot, values) + listOfNotNull(resizeState.pending)
        infoButton.icon = if (warnings.isEmpty()) AllIcons.General.Information else WidgetWarnings.icon
        infoButton.toolTipText =
            if (warnings.isEmpty()) "Open detailed usage information for all trackers and the current quota."
            else resizeState.pending
                ?: "Open usage details to review outstanding operational warnings and quota availability."
        infoButton.accessibleContext.accessibleName =
            if (warnings.isEmpty()) "$label Usage Info" else "$label usage warnings: ${warnings.joinToString("; ")}"
    }

    private fun toggleInfo() {
        if (disposed.get()) return
        infoPopup?.takeUnless { it.isDisposed }?.let { it.cancel(); return }
        val profile = settings.providers.account(vendor) ?: return
        val label = settings.providers.label(vendor)
        val popup = UsageInfoPopup.create(
            project,
            { usage.snapshot(profile.id) },
            { settings.display },
            ::showDisclaimers,
            { settings.providers.profiles.firstOrNull { it.id == profile.id }?.analytics ?: profile.analytics },
            account = UsageInfoAccount(profile.id, label) { settings.showLastRequest }) {
            infoPopup = null
            infoButton.isSelected = false
        }
        infoPopup = popup
        infoButton.isSelected = true
        Disposer.register(this, popup)
        popup.showUnderneathOf(infoButton)
    }

    private fun showDisclaimers() {
        if (disposed.get()) return
        disclaimersPage?.takeUnless { it.isDisposed }?.let { it.toFront(); return }
        DisclaimersPage(project, settings.trackers).also {
            disclaimersPage = it
            it.show()
        }
    }

    private fun showActions() {
        if (disposed.get()) return
        val actions = DefaultActionGroup()
        val config = settings.providers
        val eligible = config.eligible()
        val agents = DefaultActionGroup("Agents", true)
        populateAgents(agents, config, eligible)
        actions.add(agents)
        fun action(name: String, run: () -> Unit) {
            actions.add(object : DumbAwareAction(name) {
                override fun actionPerformed(e: AnActionEvent) = run()
            })
        }
        if (resizeState.pending != null) action("Review ribbon resizing", ::showResizeBubble)
        action("Refresh Now") { usage.refresh() }
        if (settings.display.ribbonAutoExpand) action(
            "Restore configured ribbon width (disable automatic widening)",
            ::restoreRibbonWidth
        )
        action("Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, UsageConfigurable::class.java)
        }
        action("Disclaimers", ::showDisclaimers)
        ActionManager.getInstance().createActionPopupMenu("CodexDailyTokens", actions).component.show(
            textButton, 0, textButton.height
        )
    }

    private fun populateAgents(agents: DefaultActionGroup, config: ProviderConfiguration, eligible: List<UsageVendor>) {
        val ordered =
            eligible.sortedWith(compareBy<UsageVendor> { if (it == vendor) 0 else if (it in config.display.members) 1 else 2 }.thenBy {
                eligible.indexOf(it)
            })
        ordered.forEach { item ->
            agents.add(object : ToggleAction(config.menuLabel(item)), com.intellij.openapi.project.DumbAware {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun isSelected(e: AnActionEvent): Boolean =
                    if (settings.providers.display.carousel) item in settings.providers.display.members else settings.providers.display.selected == item

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (settings.providers.display.carousel) settings.toggleCarouselVendor(item) else settings.selectVendor(
                        item
                    )
                }

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    val current = settings.providers
                    e.presentation.isEnabled =
                        item in current.eligible() && !(current.display.carousel && item in current.display.members && current.display.visible(
                            current.eligible()
                        ).size <= 1)
                    if (!e.presentation.isEnabled) e.presentation.description =
                        "Keep one vendor included or turn the carousel off in General"
                }
            })
        }
    }

    private fun showResizeBubble() {
        val message = resizeState.pending ?: return
        resizePopup?.cancel()
        val content = JPanel(java.awt.BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10)
            add(JTextArea(message, 4, 48).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            }, java.awt.BorderLayout.CENTER)
            add(JPanel(FlowLayout()).apply {
                add(JButton("Acknowledge").apply {
                    addActionListener {
                        resizeState.acknowledge(); resizePopup?.cancel(); updatePresentation()
                    }
                })
                add(JButton("Revert").apply {
                    toolTipText = "Restore the configured width and disable automatic ribbon widening."
                    addActionListener { restoreRibbonWidth(); resizePopup?.cancel() }
                })
            }, java.awt.BorderLayout.SOUTH)
        }
        resizePopup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null).setTitle("Ribbon Resized")
            .setFocusable(true).setRequestFocus(true).setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true).createPopup().also {
                Disposer.register(this, it); it.showUnderneathOf(infoButton)
            }
    }

    private fun restoreRibbonWidth() {
        if (disposed.get()) return
        settings.update(settings.extraHomes, settings.trackers, settings.display.copy(ribbonAutoExpand = false))
        updatePresentation()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val cleanup = Runnable {
            resizePopup?.cancel()
            ribbon.close()
            timer?.stop()
            timer = null
            infoPopup?.cancel()
            infoPopup = null
            infoButton.isSelected = false
            disclaimersPage?.disposeIfNeeded()
            disclaimersPage = null
        }
        if (SwingUtilities.isEventDispatchThread()) cleanup.run()
        else ApplicationManager.getApplication().invokeLater(cleanup)
    }
}
