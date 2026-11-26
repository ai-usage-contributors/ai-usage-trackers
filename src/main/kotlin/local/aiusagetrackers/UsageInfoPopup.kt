package local.aiusagetrackers

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.ZoneId
import javax.swing.JButton
import javax.swing.JPanel

/** A reading snapshot: only an explicit Update View replaces text, never a background timer. */
class UsageInfoPanel(
    private val content: () -> String,
    disclaimers: () -> Unit,
    close: () -> Unit,
    copy: (String) -> Unit = { CopyPasteManager.copyTextToClipboard(it) },
    private val details: (() -> UsageDetailPages)? = null,
    private val acknowledge: ((String, Set<String>, (Boolean) -> Unit) -> Unit)? = null,
    private val acknowledgementError: () -> Unit = {
        com.intellij.openapi.ui.Messages.showErrorDialog(
            "Could not save acknowledgement. Please retry.",
            "AI Usage Trackers"
        )
    },
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private var captured = details?.invoke()
    private val text = JBTextArea(captured?.usage ?: content(), 18, 66).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        caretPosition = 0
        accessibleContext.accessibleName = "Usage metrics and diagnostics"
    }
    private val scroll = JBScrollPane(text)
    private val tabs = javax.swing.JTabbedPane()
    private val otherTexts = listOf(
        JBTextArea(captured?.warnings.orEmpty(), 18, 66),
        JBTextArea(captured?.integrity.orEmpty(), 18, 66)
    ).onEach {
        it.isEditable = false; it.lineWrap = true; it.wrapStyleWord = true; it.caretPosition = 0
    }
    private val otherScrolls = otherTexts.map { JBScrollPane(it) }
    private val acknowledgeButton = JButton("Acknowledge Page")
    private var savingPage: Int? = null
    val preferredFocus get() = text

    init {
        border = JBUI.Borders.empty(8)
        if (details == null) add(scroll, BorderLayout.CENTER) else {
            tabs.addTab("Usage", scroll)
            tabs.addTab("Warnings", otherScrolls[0])
            tabs.addTab("Log Integrity", otherScrolls[1])
            otherTexts[0].accessibleContext.accessibleName = "Operational warnings"
            otherTexts[1].accessibleContext.accessibleName = "Log integrity report"
            add(tabs, BorderLayout.CENTER)
        }
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(4), 0)).apply {
                add(JButton("Update View").apply {
                    toolTipText = "Show the latest results already collected."
                    addActionListener { updateView() }
                })
                add(acknowledgeButton.apply {
                    toolTipText = "Mark all issues on this page as reviewed."
                    addActionListener { acknowledgeCurrentPage() }
                })
                add(JButton("Disclaimers").apply { addActionListener { disclaimers() } })
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, JBUI.scale(4), 0)).apply {
                add(JButton("Close").apply { addActionListener { close() } })
                add(JButton(if (details == null) "Copy All" else "Copy Page").apply {
                    addActionListener {
                        copy(if (details == null || tabs.selectedIndex == 0) this@UsageInfoPanel.text.text else otherTexts[tabs.selectedIndex - 1].text)
                    }
                })
            }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        tabs.addChangeListener { refreshAcknowledgement() }
        refreshAcknowledgement()
    }

    private fun selectedIssues(): Set<String> = when (tabs.selectedIndex) {
        1 -> captured?.warningIssues.orEmpty()
        2 -> captured?.integrityFindings.orEmpty()
        else -> emptySet()
    }

    private fun refreshAcknowledgement() {
        acknowledgeButton.isVisible = acknowledge != null && details != null && tabs.selectedIndex in 1..2 &&
                (selectedIssues().isNotEmpty() || savingPage == tabs.selectedIndex)
        acknowledgeButton.isEnabled = savingPage == null && selectedIssues().isNotEmpty()
        if (!acknowledgeButton.isVisible && acknowledgeButton.isFocusOwner) {
            (otherTexts.getOrNull(tabs.selectedIndex - 1) ?: text).requestFocusInWindow()
        }
        revalidate(); repaint()
    }

    private fun acknowledgeCurrentPage() {
        val save = acknowledge ?: return
        val issues = selectedIssues()
        if (savingPage != null || issues.isEmpty()) return
        val snapshot = captured
        val index = tabs.selectedIndex
        savingPage = index
        refreshAcknowledgement()
        save(if (index == 1) "warnings" else "integrity", issues) { saved ->
            if (saved && captured === snapshot) showAcknowledgedPage(index)
            savingPage = null
            refreshAcknowledgement()
            if (!saved) acknowledgementError()
        }
    }

    private fun showAcknowledgedPage(index: Int) {
        val area = otherTexts[index - 1]
        area.text = acknowledgedText(area.text)
        captured = captured?.let {
            if (index == 1) it.copy(warningIssues = emptySet()) else it.copy(integrityFindings = emptySet())
        }
    }

    private fun acknowledgedText(text: String): String {
        return text.lineSequence().joinToString("\n") {
            if (it.startsWith("• ") && !it.startsWith("• [Acknowledged]")) it.replaceFirst(
                "• ",
                "• [Acknowledged] "
            ) else it
        }
    }

    private fun updateView() {
        val position = scroll.viewport.viewPosition
        val start = text.selectionStart
        val end = text.selectionEnd
        captured = details?.invoke()
        text.text = captured?.usage ?: content()
        captured?.let { pages ->
            otherTexts.zip(listOf(pages.warnings, pages.integrity)).forEachIndexed { index, (area, value) ->
                val pagePosition = otherScrolls[index].viewport.viewPosition
                val selectionStart = area.selectionStart
                val selectionEnd = area.selectionEnd
                area.text = value
                area.select(minOf(selectionStart, area.document.length), minOf(selectionEnd, area.document.length))
                otherScrolls[index].viewport.viewPosition = pagePosition
            }
        }
        text.select(minOf(start, text.document.length), minOf(end, text.document.length))
        scroll.viewport.viewPosition = position
        refreshAcknowledgement()
    }
}

data class UsageInfoAccount(
    val id: String = ProviderProfile.LEGACY_CODEX,
    val label: String = "Codex",
    val showLastRequest: () -> Boolean = { true }
)

object UsageInfoPopup {
    fun create(
        project: Project,
        snapshot: () -> UsageSnapshot,
        preferences: () -> DisplayPreferences,
        disclaimers: () -> Unit,
        analyticsDisplay: () -> AnalyticsDisplayOptions = { AnalyticsDisplayOptions() },
        account: UsageInfoAccount = UsageInfoAccount(),
        closed: () -> Unit,
    ): JBPopup {
        lateinit var popup: JBPopup
        val panel = UsageInfoPanel(
            { UsageInfoText.describe(snapshot(), preferences(), ZoneId.systemDefault(), analyticsDisplay()) },
            disclaimers,
            { popup.cancel() },
            acknowledge = { page, issues, done ->
                com.intellij.openapi.components.service<UsageService>()
                    .acknowledgeAccount(account.id, page, issues, done)
            },
            details = {
                UsageDetailPages.capture(
                    snapshot(),
                    preferences(),
                    ZoneId.systemDefault(),
                    analyticsDisplay(),
                    account.showLastRequest()
                )
            },
        )
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel.preferredFocus)
            .setTitle("${account.label} Usage Info")
            .setProject(project)
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setMinSize(JBUI.size(560, 240))
            .setDimensionServiceKey(project, "CodexUsage.Info", true)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = closed()
            })
            .createPopup()
        return popup
    }
}
