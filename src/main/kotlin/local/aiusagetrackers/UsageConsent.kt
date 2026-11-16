package local.aiusagetrackers

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel

/** Consent belongs to this IDE installation and is never included in portable configuration. */
@Service(Service.Level.APP)
@State(name = "AIUsageTrackersConsent", storages = [Storage(value = "aiUsageTrackersConsent.xml", roamingType = RoamingType.DISABLED)])
class UsageConsent : PersistentStateComponent<UsageConsent.ConsentState> {
    private val prompted = AtomicBoolean(false)
    class ConsentState {
        var acceptedVersion: Int = 0
    }

    @Volatile private var acceptedVersion = 0
    val accepted: Boolean get() = acceptedVersion == CURRENT_VERSION
    fun setAccepted(value: Boolean) { acceptedVersion = if (value) CURRENT_VERSION else 0 }
    fun requestAgreement(project: Project) {
        val application = ApplicationManager.getApplication()
        if (accepted || application.isUnitTestMode || !prompted.compareAndSet(false, true)) return
        application.invokeLater {
            if (project.isDisposed) prompted.set(false)
            else if (!accepted && UsageConsentDialog(project).showAndGet()) setAccepted(true)
        }
    }
    override fun getState(): ConsentState = ConsentState().also { it.acceptedVersion = acceptedVersion }
    override fun loadState(state: ConsentState) { acceptedVersion = state.acceptedVersion }

    companion object {
        const val CURRENT_VERSION = 1
        const val PRIVACY_URL = "https://github.com/ai-usage-contributors/ai-usage-trackers/blob/master/PRIVACY.md"
        const val REQUIRED_MESSAGE = "Local usage processing requires your agreement in Settings > Tools > AI Usage Trackers > General."
    }
}

private class UsageConsentDialog(project: Project) : DialogWrapper(project) {
    init {
        title = "AI Usage Trackers — Local Usage Processing"
        setOKButtonText("Agree and Enable")
        setCancelButtonText("Not Now")
        init()
    }

    override fun createCenterPanel(): javax.swing.JComponent = SettingsForm().apply {
        addBlock(JLabel("<html>AI Usage Trackers reads local usage logs and capture files to calculate metrics<br>" +
            "and stores usage history and settings on your computer.<br><br>" +
            "No usage data is sent to ai-usage-contributors or third-party servers.<br>" +
            "Agree to enable processing for your configured accounts. Not Now keeps it disabled.<br>" +
            "You can withdraw agreement in General settings; saved history is retained.</html>"))
        addBlock(JButton("Read Privacy Policy").apply {
            SettingsHelp.attach(this, "Open the Privacy Policy in your browser without accepting the agreement or enabling collection.")
            addActionListener { BrowserUtil.browse(UsageConsent.PRIVACY_URL) }
        })
    }
}

internal class UsageConsentPanel(openPrivacy: () -> Unit = { BrowserUtil.browse(UsageConsent.PRIVACY_URL) }) : SettingsForm() {
    private val agreement = JCheckBox("I agree to local usage processing")
    var accepted: Boolean
        get() = agreement.isSelected
        set(value) { agreement.isSelected = value }

    init {
        section("Privacy and agreement")
        addBlock(JLabel("<html>Read local usage logs and capture files, calculate metrics, and store history locally.<br>" +
            "No usage data is sent to ai-usage-contributors or third-party servers.<br>" +
            "Apply your agreement to begin. Clear it and Apply to stop processing; saved history is retained.</html>"))
        SettingsHelp.attach(agreement, "Allow local usage processing for all configured accounts after Apply or OK. Clearing this agreement stops processing without deleting history. Cancel discards this change.")
        addRow(JLabel("User agreement"), agreement)
        val privacy = JButton("Read Privacy Policy").apply {
            SettingsHelp.attach(this, "Open the public Privacy Policy in your browser. This does not grant agreement or start collection.")
            addActionListener { openPrivacy() }
        }
        addRow(JLabel("Privacy policy"), privacy)
    }
}
