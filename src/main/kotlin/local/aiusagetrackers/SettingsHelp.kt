package local.aiusagetrackers

import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/** Purpose text shared by row labels, nested editors and assistive readers. */
object SettingsHelp {
    fun attach(control: JComponent, text: String) {
        val previous = control.toolTipText
        control.toolTipText = PluginTooltips.concise(text)
        control.accessibleContext?.accessibleDescription = text
        children(control).forEach { child ->
            if (child.toolTipText.isNullOrBlank() || child.toolTipText == previous) {
                child.toolTipText = control.toolTipText
                child.accessibleContext?.accessibleDescription = text
            }
        }
        (control.getClientProperty("settingsRowHelp") as? SettingHelpCell)?.refresh()
    }

    fun detail(control: JComponent, text: String) {
        control.putClientProperty("settingsDetail", text)
        (control.getClientProperty("settingsRowHelp") as? SettingHelpCell)?.refresh()
    }

    private fun children(root: Container): List<JComponent> = root.components.flatMap {
        listOfNotNull(it as? JComponent) + if (it is Container) children(it) else emptyList()
    }

    fun row(label: JComponent, control: JComponent) {
        val name = (label as? JLabel)?.text.orEmpty()
        val text = control.toolTipText?.takeIf(String::isNotBlank)
            ?: label.toolTipText?.takeIf(String::isNotBlank)
            ?: when (name) {
                "Above goal (×)" -> "Usage multiple where the above-goal color is reached; must exceed 1."
                "Warning (×)" -> "Usage multiple where the warning color is reached; must exceed Above goal."
                "Gradient samples" -> "Preview the colors between the configured usage multiples."
                "Percentage label", "Reset label", "Last request label" -> "Hide the label while keeping the value visible."
                else -> when (control) {
                    is JCheckBox -> "Choose whether to ${control.text.replaceFirstChar(Char::lowercase)}."
                    else -> "Configure ${name.replaceFirstChar(Char::lowercase)}."
                }
            }
        attach(control, text)
        attach(label, text)
        if (label is JLabel) label.labelFor = control
        neutralSurface(label)
    }

    fun description(label: JComponent, control: JComponent): String {
        (control.getClientProperty("settingsDetail") as? String)?.let { return it }
        val name = (label as? JLabel)?.text.orEmpty()
        return descriptions[name] ?: control.accessibleContext?.accessibleDescription?.takeIf(String::isNotBlank)
        ?: control.toolTipText.orEmpty()
    }

    private val descriptions = mapOf(
        "Displayed vendor" to "Select the vendor shown by the widget. Other enabled vendors continue collecting; this does not change any tracker's account.",
        "Configure vendor" to "Choose which vendor's configuration you are editing. This is separate from the vendor displayed in the widget.",
        "Collection" to "Track all configured, verified accounts for this vendor, including when another vendor is displayed or this vendor is excluded from the carousel. Turning tracking off retains history.",
        "Widget prefix" to "Text before this vendor's widget values, for example Codex personal:. Blank uses the vendor name. This label does not select a subscription or change quota rules.",
        "Mode" to "The multi-vendor carousel rotates entire vendor views, including their inner ribbons. Turning it off restores the remembered single-vendor choice.",
        "Include in carousel" to "Include this vendor in rotation. Excluding it affects display only; tracking remains controlled by Collection.",
        "Account to configure and display" to "Select the account to edit below and to display for this vendor. Other configured, verified accounts continue collecting.",
        "Accounts" to "Add a separate local configuration for another account or subscription. This does not sign in or create a vendor account. Keep each account's sources separate.",
        "Account alias" to "A readable name such as Personal or Work. It identifies this account in selectors and portable configuration mappings; it is not a login or authenticated identity.",
        "Source attribution" to "Confirm that the configured files belong to this account. Collection requires this confirmation. It does not verify subscription limits, supported formats or quota freshness.",
        "Status" to "Reports whether source mapping is configured. Sources mapped does not prove successful imports or fresh quota data; inspect Usage Info for those results.",
        "Account / token dataset" to "Count usage from this explicit account. Changing the displayed vendor or account never rebinds the tracker.",
        "Quota pool" to "Use this account's specific quota limit for reset-aligned periods and estimates. A missing selected pool stays unavailable. Account default preserves legacy behavior.",
        "Period" to "Calendar uses calendar boundaries, Elapsed uses a rolling duration, and Provider week follows the selected quota pool's verified weekly reset. Reset weeks include every day.",
        "Time zone" to "Leave blank for the system zone, or use a region such as Europe/Budapest. Calendar boundaries and daylight-saving changes follow this zone.",
        "Placement" to "Automatic pins single-week, single-day and single-hour trackers. Fixed stays outside the inner ribbon; Ribbon places this tracker inside it.",
        "Percentage used" to "Show the approximate share of an eligible weekly token-budget estimate. Missing estimates show a dash. This is separate from reported quota used/remaining percentages.",
        "All token metrics" to "Show the approximate share of an eligible weekly token-budget estimate. Missing estimates show a dash. This is separate from reported quota used/remaining percentages.",
        "Quota scheme" to "Choose limits confirmed for this account. A weekly quota does not imply a five-hour limit; missing observations cannot establish which scheme applies.",
        "Other quota pools" to "Show reported percentages and reset times for pools with unverified boundaries. These reports do not enable weekly pacing or token-budget estimates.",
        "Quota data" to "Use the newest available observation or pin a local report source. Source selection does not establish that the provider's quota reading is current.",
        "Weekly budget" to "Estimate from matching local tokens and quota after at least 1% usage. This is not a provider allowance; incomplete logs affect it. Only eligible Codex plans support this policy.",
        "Percentage" to "Display the captured quota percentage when the account's verified scheme supports it. Reports can be missing or stale; Usage Info explains availability.",
        "Reset time" to "Display the time until the captured reset timestamp. A configured quota scheme alone does not guarantee a valid reset observation.",
        "Keep outside the ribbon" to "Selected auxiliary values stay outside the inner ribbon when shown. Their tracker or quota visibility settings still control whether they appear.",
        "Ribbon width (logical pixels)" to "Width of the inner metrics viewport, from 80 to 1200 logical pixels. It respects IDE scaling and does not set the whole multi-vendor carousel's width.",
        "Oversized ribbon entries" to "Automatically widen the inner ribbon for long values. The resize notice lets you keep or revert the proposed width.",
        "Above goal (×)" to "Usage multiple at which the Above goal color is reached; it must exceed 1. For example, 2 means twice the estimated goal.",
        "Warning (×)" to "Usage multiple at which the Warning color is reached; it must exceed Above goal. For example, 3 means three times the estimated goal.",
        "Date format (preset or custom)" to "Choose a preset or valid custom date pattern. For example, yyyy-MM-dd displays 2026-11-11 and dd/MM/yyyy displays 11/11/2026.",
        "Additional Codex folders" to "Add home folders alongside automatic discovery for the original Codex account, one absolute path per line. Examples: C:\\Users\\Alex\\.codex or /home/alex/.codex. Select the home containing sessions and archived_sessions. Removing a path retains history.",
        "Quota account" to "Choose the account whose quota scheme and display options you are editing. Switching preserves each account's draft; it does not change the displayed vendor or tracker bindings.",
        "Period details" to "Inspect resolved intervals and stored token totals. Starts are included and ends excluded. Calculations run in the background and do not change recorded usage.",
        "Tracker limit" to "Maximum tracker count, from 1 to 32; the default is 5. Remove excess trackers before lowering it. No trackers are deleted automatically.",
        "Tracker sample data (UTC)" to "Illustrative UTC values only. Drag visible trackers to reorder them; Apply or OK saves the order. This preview does not collect usage.",
        "Quota preview" to "Preview the draft quota options with collected or sample readings. Samples do not change account data or establish quota availability.",
    )

    /** Labels and blank page surfaces release the old editor without global event hooks. */
    fun neutralSurface(component: Component) {
        if (component is JComponent && (component is JLabel || component is JPanel) &&
            component.getClientProperty("neutralSettingsSurface") != true
        ) {
            component.putClientProperty("neutralSettingsSurface", true)
            component.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    var parent: Component? = component
                    while (parent != null && parent !is SettingsTabContent) parent = parent.parent
                    parent?.requestFocusInWindow()
                }
            })
        }
        if (component is Container) component.components.forEach(::neutralSurface)
    }
}

/** A cell shares its input's grid row and never owns a separate sidebar. */
class SettingHelpCell(private val label: JComponent, val control: JComponent) : JTextArea(0, 26) {
    init {
        isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
        minimumSize = java.awt.Dimension(80, 0)
        refresh()
    }

    fun refresh() {
        val explanation = SettingsHelp.description(label, control)
        if (text != explanation) {
            text = explanation; caretPosition = 0
        }
        getAccessibleContext().accessibleName = "${(label as? JLabel)?.text.orEmpty()} help"
    }
}

/** Preserve popup models on unrelated edits; callers guard their own selection listeners. */
fun <T> updateSettingsChoices(combo: JComboBox<T>, choices: List<T>, selected: T?) {
    if ((0 until combo.itemCount).map(combo::getItemAt) != choices) {
        combo.model = DefaultComboBoxModel<T>().apply {
            choices.forEach(::addElement)
            selectedItem = selected
        }
    }
    if (combo.selectedItem != selected) combo.selectedItem = selected
}
