package local.aiusagetrackers

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel

/** Consent belongs to this IDE installation and is never included in portable configuration. */
@Service(Service.Level.APP)
@State(name = "AIUsageTrackersConsent", storages = [Storage(value = "aiUsageTrackersConsent.xml", roamingType = RoamingType.DISABLED)])
class UsageConsent : PersistentStateComponent<UsageConsent.ConsentState> {
    class ConsentState {
        var acceptedVersion: Int = 0
    }

    @Volatile private var acceptedVersion = 0
    val accepted: Boolean get() = acceptedVersion == CURRENT_VERSION
    fun setAccepted(value: Boolean) { acceptedVersion = if (value) CURRENT_VERSION else 0 }
    override fun getState(): ConsentState = ConsentState().also { it.acceptedVersion = acceptedVersion }
    override fun loadState(state: ConsentState) { acceptedVersion = state.acceptedVersion }

    companion object {
        const val CURRENT_VERSION = 1
        const val PRIVACY_URL = "https://github.com/ai-usage-contributors/ai-usage-trackers/blob/master/PRIVACY.md"
        const val REQUIRED_MESSAGE = "Local usage processing requires your agreement in Settings > Tools > AI Usage Trackers > General."
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
