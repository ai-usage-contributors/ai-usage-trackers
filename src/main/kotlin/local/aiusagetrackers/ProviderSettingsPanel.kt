package local.aiusagetrackers

import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.JComponent
import javax.swing.SwingUtilities
import com.intellij.ui.components.JBScrollPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Draft-only editor. Applying settings never changes external vendor configuration. */
class ProviderSettingsPanel(private val changed: () -> Unit, private val addDefaults: (String) -> Unit,
    private val accountInUse: (String) -> Boolean = { false }) : JPanel(BorderLayout()) {
    private var draft = ProviderConfiguration()
    private var loading = false
    private val form = SettingsForm()
    val advanced = SettingsForm()
    var openAdvanced: () -> Unit = {}
    private val advancedVendor = JComboBox(UsageVendor.entries.toTypedArray()).apply { renderer = providerChoiceRenderer() }
    private val sourceLabel = JLabel()
    private val captureLabel = JLabel()
    private val sourceInput = JPanel(BorderLayout(4, 4))
    private val captureInput = JPanel(BorderLayout(4, 0))
    private val advancedCategory = SettingsCategory("Accounts and usage sources", advanced)
    val advancedPanel: JComponent = advancedCategory
    private val setup = JButton("Setup instructions…")
    private val export = JButton("Export capture helper and instructions…")
    private var editQueued = false
    private val displayed = JComboBox<UsageVendor>()
    private val editorVendor = JComboBox(UsageVendor.entries.toTypedArray()).apply { renderer = providerChoiceRenderer() }
    private val track = JCheckBox("Track usage")
    private val label = JTextField()
    private val account = JComboBox<AccountChoice>()
    private val addAccount = JButton("Add account")
    private val removeAccount = JButton("Remove account")
    private val alias = JTextField()
    private val locations = JTextArea(3, 40)
    private val capture = JTextField()
    private val verified = JCheckBox("I verified these sources belong to this account")
    private val status = JLabel()
    private val carousel = JCheckBox("Multi-vendor carousel")
    private val members = UsageVendor.entries.associateWith { JCheckBox(it.toString()).apply { putClientProperty("html.disable", true) } }
    private val motion = JComboBox(RibbonScrollMode.entries.toTypedArray())
    private val speed = JSpinner(SpinnerNumberModel(30, 0, 200, 5))
    private val horizontal = JSpinner(SpinnerNumberModel(8, 0, 300, 1))
    private val vertical = JSpinner(SpinnerNumberModel(8, 0, 300, 1))
    private val direction = JComboBox(RibbonStepDirection.entries.toTypedArray())
    private var profileId = ProviderProfile.LEGACY_CODEX
    private var vendor = UsageVendor.CODEX
    private var baselineDisplay = VendorDisplayState()

    init {
        displayed.renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: javax.swing.JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): java.awt.Component {
                putClientProperty("html.disable", true)
                return super.getListCellRendererComponent(list, (value as? UsageVendor)?.let(draft::menuLabel).orEmpty(), index, selected, focus)
            }
        }
        form.section("Displayed usage")
        form.addRow(JLabel("Displayed vendor"), displayed)
        form.addRow(JLabel("Mode"), carousel)
        members.forEach { (_, box) -> form.addRow(JLabel("Include in carousel"), box) }
        form.addRow(JLabel("Carousel motion"), motion)
        form.addRow(JLabel("Speed (pixels/second)"), speed)
        form.addRow(JLabel("Horizontal step (seconds)"), horizontal)
        form.addRow(JLabel("Vertical step (seconds)"), vertical)
        form.addRow(JLabel("Vertical direction"), direction)
        form.section("Vendor tracking and widget prefix")
        form.addRow(JLabel("Configure vendor"), editorVendor)
        form.addRow(JLabel("Collection"), track)
        form.addRow(JLabel("Widget prefix"), label)
        form.addRow(JLabel("Account and source setup"), JButton("Advanced…").apply {
            SettingsHelp.attach(this, "Open account and usage source settings for this vendor.")
            addActionListener { openAdvanced() }
        })
        advanced.addRow(JLabel("Configure vendor"), advancedVendor)
        advanced.addRow(JLabel("Account to configure and display"), account)
        advanced.addRow(JLabel("Accounts"), JPanel(java.awt.GridLayout(1, 2, 4, 0)).apply {
            SettingsHelp.attach(this, "Add an account or remove an unused account from the settings draft. Removing an account retains imported history.")
            add(addAccount.apply {
            SettingsHelp.attach(this, "Add a separate local account configuration for this vendor.")
            addActionListener {
            if (draft.profiles.size >= 32) return@addActionListener
            saveEditor()
            val p = ProviderProfile(vendor = vendor, alias = "${vendor.displayName} account ${draft.profiles.count { it.vendor == vendor } + 1}")
            draft.profiles.add(p); draft.vendors.first { it.vendor == vendor }.accountId = p.id
            loadEditor(); notifyChanged()
        } })
            add(removeAccount.apply {
                SettingsHelp.attach(this, "Remove this account from the settings draft after reassigning or removing its trackers. Imported history is retained; the original Codex and last account for each vendor are protected.")
                addActionListener {
                    saveEditor()
                    try {
                        draft.removeAccount(profileId, accountInUse(profileId))
                        loadEditor(); notifyChanged()
                    } catch (failure: IllegalArgumentException) {
                        com.intellij.openapi.ui.Messages.showErrorDialog(this, failure.message ?: "Account cannot be removed", "Remove Account")
                    }
                }
            })
        })
        advanced.addRow(JLabel("Account alias"), alias)
        locations.border = com.intellij.util.ui.JBUI.Borders.empty(4)
        sourceInput.add(JBScrollPane(locations), BorderLayout.CENTER)
        sourceInput.add(JPanel(java.awt.GridLayout(1, 2, 4, 0)).apply {
            add(JButton("Add folder…").apply {
                SettingsHelp.attach(this, "Choose a usage source folder to add to the list.")
                addActionListener { chooseSource(false) }
            })
            add(JButton("Add log file…").apply {
                SettingsHelp.attach(this, "Choose a usage JSONL file to add to the list.")
                addActionListener { chooseSource(true) }
            })
        }, BorderLayout.SOUTH)
        advanced.addRow(sourceLabel, sourceInput)
        captureInput.add(capture, BorderLayout.CENTER)
        captureInput.add(JButton("Browse…").apply {
            SettingsHelp.attach(this, "Choose the folder where this account's capture helper writes reports.")
            addActionListener { chooseCapture() }
        }, BorderLayout.EAST)
        advanced.addRow(captureLabel, captureInput)
        advanced.addRow(JLabel("Source attribution"), verified)
        advanced.addRow(JLabel("Status"), status)
        advanced.addRow(JLabel("Trackers"), JButton("Create default trackers for this account").apply {
            SettingsHelp.attach(this, "Create starter trackers if this account has none.")
            addActionListener { saveEditor(); addDefaults(profileId) }
        })
        advanced.addRow(JLabel("Setup"), setup)
        advanced.addRow(JLabel("Setup files"), export)
        setup.addActionListener { advancedCategory.helpButton.isSelected = false; advancedCategory.helpButton.doClick() }
        export.addActionListener { exportSetup() }
        listOf(editorVendor, advancedVendor).forEach { SettingsHelp.attach(it, "Choose the vendor to configure; this does not switch the widget.") }
        SettingsHelp.attach(track, "Collect usage for this vendor even when another vendor is displayed.")
        SettingsHelp.attach(label, "Text before this vendor's widget values; blank uses the vendor name.")
        SettingsHelp.attach(account, "Choose the account to edit and display; other verified accounts keep collecting.")
        SettingsHelp.attach(alias, "Name this account so you can distinguish it from other subscriptions.")
        SettingsHelp.attach(verified, "Confirm that these usage sources belong to this account before collection starts.")
        SettingsHelp.attach(status, "Configuration status; successful imports and quota availability are shown in Usage Info.")
        SettingsHelp.attach(setup, "Show setup instructions for the selected vendor beside these fields.")
        SettingsHelp.attach(export, "Copy the optional Python capture helper and instructions to an empty folder.")
        SettingsHelp.attach(displayed, "Choose the vendor shown by the widget without stopping other collectors.")
        SettingsHelp.attach(carousel, "Rotate complete vendor widgets instead of displaying one vendor.")
        members.forEach { (_, box) -> SettingsHelp.attach(box, "Include this vendor in the carousel without changing collection.") }
        SettingsHelp.attach(motion, "Choose smooth scrolling or timed steps between vendor views.")
        SettingsHelp.attach(speed, "Carousel scrolling speed in pixels per second; zero pauses motion.")
        listOf(horizontal, vertical).forEach { SettingsHelp.attach(it, "Seconds between carousel steps; zero disables automatic steps.") }
        SettingsHelp.attach(direction, "Choose the direction of vertical carousel steps.")
        refreshRowHelp(form); refreshRowHelp(advanced)
        add(form, BorderLayout.NORTH)
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = documentEdited()
            override fun removeUpdate(e: DocumentEvent) = documentEdited()
            override fun changedUpdate(e: DocumentEvent) = documentEdited()
        }
        listOf(label.document, alias.document, locations.document, capture.document).forEach { it.addDocumentListener(listener) }
        editorVendor.addActionListener { if (!loading) { saveEditor(); vendor = editorVendor.selectedItem as UsageVendor; loadEditor() } }
        advancedVendor.addActionListener { if (!loading) { saveEditor(); vendor = advancedVendor.selectedItem as UsageVendor; loadEditor() } }
        account.addActionListener { if (!loading) {
            saveEditor(); draft.vendors.first { it.vendor == vendor }.accountId = (account.selectedItem as AccountChoice).id
            loadEditor(); notifyChanged()
        } }
        track.addActionListener { edit() }; verified.addActionListener { edit() }
        displayed.addActionListener { if (!loading) { (displayed.selectedItem as? UsageVendor)?.let { draft.display.selected = it }; notifyChanged() } }
        carousel.addActionListener { if (!loading) { saveEditor(); draft.display = draft.display.enable(carousel.isSelected, draft.eligible()); refreshDisplay(); notifyChanged() } }
        members.forEach { (v, box) -> box.addActionListener { if (!loading) {
            val list = draft.display.members
            if (box.isSelected) { if (v !in list) list.add(v) } else list.remove(v)
            notifyChanged()
        } } }
        motion.addActionListener { edit() }
        listOf(speed, horizontal, vertical).forEach { it.addChangeListener { edit() } }; direction.addActionListener { edit() }
        reset(draft)
    }
    private fun edit() { if (!loading) { saveEditor(); refreshDisplay(); notifyChanged() } }
    private fun documentEdited() {
        if (loading) return
        saveEditor()
        if (editQueued) return
        editQueued = true
        SwingUtilities.invokeLater { editQueued = false; refreshDisplay(); notifyChanged() }
    }
    private fun refreshRowHelp(root: java.awt.Container) {
        root.components.forEach {
            if (it is JLabel) {
                val pair = it.getClientProperty("settingsRow") as? Pair<*, *>
                (pair?.second as? JComponent)?.let { control -> SettingsHelp.row(it, control) }
            }
            if (it is java.awt.Container) refreshRowHelp(it)
        }
    }
    private fun chooseSource(file: Boolean) {
        val descriptor = if (file) com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
            else com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
        val path = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, this, null, null)?.toNioPath() ?: return
        locations.append((if (locations.text.isBlank() || locations.text.endsWith('\n')) "" else "\n") + path)
    }
    private fun chooseCapture() {
        val path = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor(), this, null, null)?.toNioPath() ?: return
        capture.text = path.toString()
    }
    private fun refreshVendorHelp() {
        val notice = if (vendor.experimental) "Experimental support. Synthetic tests pass, but contributors with a subscription must validate actual usage and quotas before verified support can be claimed. " else ""
        SettingsHelp.detail(sourceInput, notice + when (vendor) {
            UsageVendor.CODEX -> "Use one Codex home per line, containing sessions and archived_sessions. Examples: C:\\Users\\Alex\\.codex or /home/alex/.codex. The original account keeps automatic discovery; additional accounts need explicit, separate homes."
            UsageVendor.CLAUDE -> "Choose this account's native usage JSONL files or projects folder, including subagents. Examples: C:\\Users\\Alex\\.claude\\projects or /home/alex/.claude/projects. These logs supply tokens; quota reports use optional capture separately."
            UsageVendor.ANTIGRAVITY -> "Use sanitized helper output for completed headless DONE steps. Example folder: C:\\UsageCaptures\\antigravity-personal or /home/alex/usage-captures/antigravity-personal. Ordinary interactive, desktop and ACP token traffic is not covered."
        })
        SettingsHelp.detail(captureInput, notice + when (vendor) {
            UsageVendor.CLAUDE -> "Optional status-line quota reports from this account's helper. Example: C:\\UsageCaptures\\claude-personal or /home/alex/usage-captures/claude-personal. Captures can be cached; capture time does not prove a current provider balance."
            else -> "Helper output for this account's quota reports and completed headless token steps. Example: C:\\UsageCaptures\\antigravity-personal or /home/alex/usage-captures/antigravity-personal. Keep accounts separate. Reported pool names do not establish weekly resets."
        })
        SettingsHelp.detail(setup, notice + when (vendor) {
            UsageVendor.CODEX -> "No capture helper is needed. Keep automatic discovery for the original Codex account, or configure explicit home folders for additional accounts. Verify each source belongs to the intended account."
            UsageVendor.CLAUDE -> "Export the helper, then manually compose it with Claude's documented status-line command, preserving any existing renderer. Token history comes from the usage logs above. Callbacks are activity-driven, not live idle quota polling. Full commands are in the exported README."
            UsageVendor.ANTIGRAVITY -> "Export the helper for optional status-line quota capture. For token steps, capture stream-json from an already intended headless task; do not run a model just to collect usage. Preserve any existing status renderer. Full commands and coverage limits are in the exported README."
        })
        SettingsHelp.detail(export, "Copy capture.py and README.txt into an empty folder, for example C:\\UsageTools or /home/alex/usage-tools. Python 3.10+ and a local filesystem supporting hard links are required. Export never edits vendor configuration or starts an agent.")
    }
    private fun exportSetup() {
        val target = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
            com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor(), this, null, null)?.toNioPath() ?: return
        com.intellij.openapi.components.service<ConfigurationTransferService>().execute({
            for (name in listOf("capture.py", "README.txt")) {
                val destination = target.resolve(name)
                require(!java.nio.file.Files.exists(destination)) { "Setup file already exists; select an empty destination" }
            }
            for (name in listOf("capture.py", "README.txt")) javaClass.getResourceAsStream("/quota-capture/$name").use { stream ->
                requireNotNull(stream) { "Packaged capture helper missing" }
                java.nio.file.Files.copy(stream, target.resolve(name))
            }
        }) { result -> result.exceptionOrNull()?.let { com.intellij.openapi.ui.Messages.showErrorDialog(this, it.message ?: "Export failed", "Capture Setup") } }
    }
    private fun notifyChanged() { if (!loading) changed() }
    private fun saveEditor() {
        if (loading) return
        draft.vendors.first { it.vendor == vendor }.also { it.tracking = track.isSelected; it.label = label.text }
        draft.profiles.firstOrNull { it.id == profileId }?.also {
            it.alias = alias.text; it.locations = locations.text; it.captureDirectory = capture.text.trim(); it.verified = verified.isSelected
        }
        draft.display.motion = motion.selectedItem as RibbonScrollMode
        draft.display.speed = (speed.value as Number).toInt()
        draft.display.horizontalSeconds = (horizontal.value as Number).toInt()
        draft.display.verticalSeconds = (vertical.value as Number).toInt()
        draft.display.direction = direction.selectedItem as RibbonStepDirection
    }
    private fun loadEditor() {
        loading = true
        val v = draft.vendors.first { it.vendor == vendor }
        val p = draft.account(vendor) ?: draft.profiles.first { it.vendor == vendor }
        profileId = p.id
        editorVendor.selectedItem = vendor; advancedVendor.selectedItem = vendor
        track.isSelected = v.tracking; label.text = v.label
        updateSettingsChoices(account, draft.profiles.filter { it.vendor == vendor }.map { AccountChoice(it.id, it.vendor.supportLabel(it.alias)) }, AccountChoice(p.id, p.vendor.supportLabel(p.alias)))
        alias.text = p.alias; locations.text = p.locations; capture.text = p.captureDirectory; verified.isSelected = p.verified
        loading = false; refreshDisplay()
    }
    private fun refreshDisplay() {
        loading = true
        addAccount.isEnabled = draft.profiles.size < 32
        removeAccount.isEnabled = profileId != ProviderProfile.LEGACY_CODEX && draft.profiles.count { it.vendor == vendor } > 1
        val eligible = draft.eligible()
        updateSettingsChoices(displayed, eligible, draft.display.selected.takeIf { it in eligible } ?: eligible.firstOrNull())
        updateSettingsChoices(account, draft.profiles.filter { it.vendor == vendor }.map { AccountChoice(it.id, it.vendor.supportLabel(it.alias)) }, draft.account(vendor)?.let { AccountChoice(it.id, it.vendor.supportLabel(it.alias)) })
        displayed.repaint()
        displayed.isEnabled = !draft.display.carousel
        status.text = if (draft.account(vendor)?.configured() == true) "Sources mapped; inspect Usage Info for import and quota availability" else "Setup/verified account mapping required"
        if (vendor.experimental) status.text = "Experimental — ${if (draft.account(vendor)?.configured() == true) "sources mapped" else "setup required"}; subscription validation pending"
        carousel.isSelected = draft.display.carousel
        sourceLabel.text = when (vendor) {
            UsageVendor.CODEX -> "Codex home folders"
            UsageVendor.CLAUDE -> "Claude (experimental) usage log files or folders"
            UsageVendor.ANTIGRAVITY -> "Antigravity (experimental) captured usage files or folders"
        }
        captureLabel.text = if (vendor == UsageVendor.CLAUDE) "Quota capture folder" else "Quota and token capture folder"
        SettingsHelp.attach(sourceInput, "Enter one absolute ${if (vendor == UsageVendor.CODEX) "Codex home folder" else "usage source path"} per line.")
        SettingsHelp.attach(locations, sourceInput.toolTipText)
        SettingsHelp.attach(sourceLabel, sourceInput.toolTipText)
        SettingsHelp.attach(captureInput, "Folder containing this account's reports from the optional capture helper.")
        SettingsHelp.attach(captureLabel, captureInput.toolTipText)
        setSettingVisible(captureInput, vendor != UsageVendor.CODEX, advancedVendor)
        setSettingVisible(export, vendor != UsageVendor.CODEX, advancedVendor)
        sourceInput.components.filterIsInstance<JPanel>().flatMap { it.components.toList() }.filterIsInstance<JButton>()
            .forEach { it.isVisible = it.text != "Add log file…" || vendor != UsageVendor.CODEX }
        refreshVendorHelp()
        members.forEach { (v, box) -> box.text = draft.menuLabel(v); box.isSelected = v in draft.display.members; box.isEnabled = v in eligible; setSettingVisible(box, carousel.isSelected, carousel) }
        val m = draft.display.motion
        listOf(motion, speed, horizontal, vertical, direction).forEach { control ->
            val relevant = when (control) { speed -> !m.stepped; horizontal -> m == RibbonScrollMode.HORIZONTAL_STEP; vertical, direction -> m == RibbonScrollMode.VERTICAL_STEP; else -> true }
            setSettingVisible(control, carousel.isSelected && relevant, carousel)
        }
        loading = false
    }
    fun value(): ProviderConfiguration { saveEditor(); return draft.isolatedCopy() }
    fun setOptions(id: String, options: AnalyticsDisplayOptions) { draft.profiles.firstOrNull { it.id == id }?.analytics = options.copy() }
    fun reset(value: ProviderConfiguration) {
        draft = value.isolatedCopy(); baselineDisplay = value.display.isolatedCopy()
        loading = true
        motion.selectedItem = draft.display.motion; speed.value = draft.display.speed
        horizontal.value = draft.display.horizontalSeconds; vertical.value = draft.display.verticalSeconds; direction.selectedItem = draft.display.direction
        loading = false; loadEditor()
    }
    fun reconcileExternal(current: ProviderConfiguration): ProviderConfiguration = value().also {
        if (it.display.selected == baselineDisplay.selected) it.display.selected = current.display.selected
        if (it.display.members == baselineDisplay.members) it.display.members = current.display.members.toMutableList()
    }
    fun validateDraft() {
        val value = value()
        for (profile in value.profiles) {
            try { profile.validate() } catch (failure: IllegalArgumentException) {
                saveEditor()
                vendor = profile.vendor
                draft.vendors.first { it.vendor == vendor }.accountId = profile.id
                loadEditor()
                revealSetting(if (profile.alias.isBlank()) alias else locations)
                throw failure
            }
        }
        value.validate()
        require(!value.display.carousel || value.eligible().isEmpty() || value.display.visible(value.eligible()).isNotEmpty()) { "Select at least one eligible carousel vendor" }
    }
}
