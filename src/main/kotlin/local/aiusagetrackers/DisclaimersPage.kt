package local.aiusagetrackers

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.time.ZoneId
import javax.swing.Action
import javax.swing.JComponent

class DisclaimersPage(project: Project, private val trackers: List<TrackerDefinition>) : DialogWrapper(project, false) {
    init {
        title = "AI Usage Trackers — Disclaimers"
        isModal = false
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = JBScrollPane(
        JBTextArea(
            DisclaimersText.describe(trackers, ZoneId.systemDefault()), 22, 72
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            caretPosition = 0
            border = JBUI.Borders.empty(8)
            accessibleContext.accessibleName = "Usage disclaimers and resolved time zones"
        }).apply { preferredSize = JBUI.size(680, 460) }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun getDimensionServiceKey(): String = "CodexUsage.Disclaimers"
}
