package local.aiusagetrackers

import java.awt.Container
import javax.swing.*
import kotlin.test.*

class SettingsInputRegressionTest {
    private fun controls(root: Container): List<JComponent> = root.components.flatMap {
        listOfNotNull(it as? JComponent) + if (it is Container) controls(it) else emptyList()
    }

    private fun input(root: Container, caption: String): JComponent = controls(root).filterIsInstance<JLabel>()
        .single { it.text == caption }.getClientProperty("settingsRow").let { (it as Pair<*, *>).second as JComponent }

    @Test fun metricOrderHidesIneligibleFiveHourMetricAndPreservesItsPosition() = SwingUtilities.invokeAndWait {
        val form = SettingsForm()
        val share = JCheckBox()
        val label = JLabel("Five-hour share")
        form.addRow(label, share)
        var orderChanges = 0
        val metrics = TokenMetricSettings { orderChanges++ }
        metrics.addRows(form)
        metrics.load(TokenMetricLayout(
            order = TokenMetricOrder.WEEKLY_FIVE_HOUR_TOKENS,
            fiveHourPrefix = "5h ",
        ))
        val order = input(form, "Metric order") as JComboBox<*>
        val prefix = input(form, "5h % prefix")
        val purpose = "Show the period's approximate five-hour share."
        val reason = "Choose a supported period of 1–5 hours or 1–299 minutes to show this share."
        val changesAfterLoad = orderChanges

        metrics.updateFiveHourAvailability(share, purpose, reason)
        assertFalse(share.isEnabled)
        assertEquals(reason, share.toolTipText)
        assertEquals(reason, label.accessibleContext.accessibleDescription)
        assertEquals(reason, label.toolTipText)
        assertFalse(prefix.isVisible)
        assertEquals(listOf("Tokens → Weekly %", "Weekly % → Tokens"),
            (0 until order.itemCount).map { order.getItemAt(it).toString() })
        assertEquals("Weekly % → Tokens", order.selectedItem?.toString())
        assertEquals(changesAfterLoad, orderChanges)

        order.selectedIndex = 0
        assertEquals(TokenMetricOrder.TOKENS_FIVE_HOUR_WEEKLY, metrics.value().order)
        metrics.updateFiveHourAvailability(share, purpose, null)
        assertTrue(share.isEnabled)
        assertEquals(purpose, share.accessibleContext.accessibleDescription)
        assertEquals(purpose, label.toolTipText)
        assertTrue(prefix.isVisible)
        assertEquals(6, order.itemCount)
        assertEquals("Tokens → 5h % → Weekly %", order.selectedItem?.toString())
        assertEquals("5h ", metrics.value().fiveHourPrefix)
        assertEquals(changesAfterLoad + 1, orderChanges)
    }

    @Test fun lastRequestOrderAndTooltipFollowTheAccountQuotaScheme() = SwingUtilities.invokeAndWait {
        AnalyticsSettingsPanel { UsageSnapshot() }.use { panel ->
            val options = AnalyticsDisplayOptions(lastRequestMetricOrder = TokenMetricOrder.FIVE_HOUR_WEEKLY_TOKENS)
            panel.reset(options.copy(planProfile = QuotaPlanProfile.CODEX_WEEKLY))
            val share = input(panel.requestSettings, "Last request five-hour share")
            val order = input(panel.requestSettings, "Metric order") as JComboBox<*>
            assertFalse(share.isEnabled)
            assertEquals(FIVE_HOUR_PLAN_REQUIRED, share.accessibleContext.accessibleDescription)
            assertEquals("Weekly % → Tokens", order.selectedItem?.toString())
            panel.planProfile.selectedItem = QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR
            assertTrue(share.isEnabled)
            assertEquals("5h % → Weekly % → Tokens", order.selectedItem?.toString())
            assertEquals(options.lastRequestMetricOrder, panel.options().lastRequestMetricOrder)
        }
    }

    @Test fun generalContainsBasicsAndAdvancedSharesVendorAndAccountDrafts() = SwingUtilities.invokeAndWait {
        val panel = ProviderSettingsPanel({}, {})
        assertTrue(controls(panel).filterIsInstance<JLabel>().none { it.text == "Account alias" })
        val prefix = input(panel, "Widget prefix") as JTextField
        prefix.text = "Personal"
        val vendor = input(panel, "Configure vendor") as JComboBox<*>
        vendor.selectedItem = UsageVendor.CLAUDE
        val advancedVendor = input(panel.advancedPanel, "Configure vendor") as JComboBox<*>
        assertEquals(UsageVendor.CLAUDE, advancedVendor.selectedItem)
        val alias = input(panel.advancedPanel, "Account alias") as JTextField
        alias.text = "Work Claude"
        advancedVendor.selectedItem = UsageVendor.CODEX
        assertEquals(UsageVendor.CODEX, vendor.selectedItem)
        assertEquals("Personal", prefix.text)
        assertEquals("Work Claude", panel.value().account(UsageVendor.CLAUDE)?.alias)
        assertFalse(input(panel.advancedPanel, "Quota and token capture folder").isVisible)
        assertTrue(controls(panel.advancedPanel).filterIsInstance<JTextArea>().filter { it.isEditable }.all { it.parent is JViewport })
        advancedVendor.selectedItem = UsageVendor.ANTIGRAVITY
        assertTrue(input(panel.advancedPanel, "Quota and token capture folder").isVisible)
        assertTrue(controls(panel.advancedPanel).filterIsInstance<JTextArea>().any {
            !it.isEditable && "headless" in it.text && "C:\\UsageCaptures" in it.text
        })
    }

    @Test fun editingSourceRetainsCaretSelectionAndDoesNotRebuildDisplayModel() {
        lateinit var panel: ProviderSettingsPanel
        lateinit var source: JTextArea
        lateinit var combo: JComboBox<*>
        lateinit var model: ComboBoxModel<*>
        SwingUtilities.invokeAndWait {
            panel = ProviderSettingsPanel({}, {})
            combo = input(panel, "Displayed vendor") as JComboBox<*>
            model = combo.model
            source = controls(panel.advancedPanel).filterIsInstance<JTextArea>().single { it.isEditable }
            source.text = "C:\\logs\\one\nC:\\logs\\two"
            source.select(3, 7)
            source.replaceSelection("usage")
            assertEquals(8, source.caretPosition)
        }
        // Drain the deferred document refresh before checking selection and model identity.
        SwingUtilities.invokeAndWait {
            assertSame(model, combo.model)
            assertEquals("C:\\usage\\one\nC:\\logs\\two", source.text)
            assertEquals(8, source.caretPosition)
            assertEquals(source.text, panel.value().account(UsageVendor.CODEX)?.locations)
        }
    }

    @Test fun identicalAccountChoicesPreserveModelAcrossRepeatedSelections() = SwingUtilities.invokeAndWait {
        val choices = listOf(AccountChoice("a", "Personal"), AccountChoice("b", "Work"))
        val combo = JComboBox<AccountChoice>()
        updateSettingsChoices(combo, choices, choices.first())
        val model = combo.model
        repeat(6) { index ->
            val selected = choices[index % 2]
            combo.selectedItem = selected
            updateSettingsChoices(combo, choices, selected)
            assertSame(model, combo.model)
            assertEquals(selected, combo.selectedItem)
        }
        updateSettingsChoices(combo, choices.map { it.copy(label = it.label + " renamed") }, choices.first().copy(label = "Personal renamed"))
        assertNotSame(model, combo.model)
        assertEquals("a", (combo.selectedItem as AccountChoice).id)
    }

    @Test fun groupHelpIsBesideInputsAndDoesNotToggleExpansion() = SwingUtilities.invokeAndWait {
        val draft = JTextField("unsaved")
        val category = SettingsCategory("Additional Codex folders", draft)
        category.helpButton.doClick()
        assertTrue(category.expanded)
        val help = controls(category).filterIsInstance<JTextArea>().single()
        assertTrue(help.isVisible)
        assertTrue("C:\\Users\\Alex\\.codex" in help.text)
        assertTrue("/home/alex/.codex" in help.text)
        val layout = help.parent.layout as java.awt.GridBagLayout
        assertEquals(2, layout.getConstraints(help).gridx)
        assertEquals(layout.getConstraints(draft).gridy, layout.getConstraints(help).gridy)
        assertNotNull(category.helpButton.icon)
        assertTrue(category.helpButton.text.isNullOrEmpty())
        assertFalse(category.helpButton.isBorderPainted)
        category.helpButton.doClick()
        assertFalse(help.isVisible)
        assertTrue(category.expanded)
        assertEquals("unsaved", draft.text)
    }

    @Test fun helpCellsFollowIndividualRowsAndLateRowsInheritTheToggle() = SwingUtilities.invokeAndWait {
        val form = SettingsForm()
        val zone = JTextField("Europe/Budapest")
        val name = JTextField("Daily")
        SettingsHelp.attach(name, "Name this tracker.")
        form.addRow(JLabel("Time zone"), zone)
        form.addRow(JLabel("Name"), name)
        val category = SettingsCategory("Period", form)
        val zoneHelp = zone.getClientProperty("settingsRowHelp") as SettingHelpCell
        val nameHelp = name.getClientProperty("settingsRowHelp") as SettingHelpCell
        assertFalse(zoneHelp.isVisible)
        category.helpButton.doClick()
        assertTrue(zoneHelp.isVisible); assertTrue(nameHelp.isVisible)
        assertTrue("Europe/Budapest" in zoneHelp.text)
        assertEquals("Name this tracker.", nameHelp.text)
        setSettingVisible(zone, false)
        assertFalse(zoneHelp.isVisible); assertTrue(nameHelp.isVisible)
        setSettingVisible(zone, true)
        assertTrue(zoneHelp.isVisible)
        val late = JTextField()
        form.addRow(JLabel("Late row"), late)
        assertTrue((late.getClientProperty("settingsRowHelp") as SettingHelpCell).isVisible)
        SettingsHelp.detail(name, "Name this tracker, for example Daily work.")
        assertTrue("Daily work" in nameHelp.text)
        form.enableFields(false)
        assertTrue(nameHelp.isEnabled)
        category.helpButton.doClick()
        assertTrue(form.components.filterIsInstance<SettingHelpCell>().none { it.isVisible })
        assertEquals("Daily", name.text)
    }

    @Test fun nestedFormsShareHelpVisibilityButNestedGroupsKeepTheirOwnToggle() = SwingUtilities.invokeAndWait {
        val nestedForm = SettingsForm().apply { addRow(JLabel("Name"), JTextField()) }
        val nestedGroup = SettingsCategory("Nested", JTextField())
        val form = SettingsForm().apply { addBlock(nestedForm); addCategory(nestedGroup) }
        val group = SettingsCategory("Outer", form)
        group.helpButton.doClick()
        assertTrue(nestedForm.showingHelp)
        assertFalse(nestedGroup.content.showingHelp)
        nestedGroup.helpButton.doClick()
        group.helpButton.doClick()
        assertFalse(nestedForm.showingHelp)
        assertTrue(nestedGroup.content.showingHelp)
    }

    @Test fun editableSettingsAndLabelsHavePurposeTooltipsIncludingNestedEditors() = SwingUtilities.invokeAndWait {
        val providers = ProviderSettingsPanel({}, {})
        val analytics = AnalyticsSettingsPanel { UsageSnapshot() }
        analytics.use {
            val roots = listOf(providers, providers.advancedPanel, analytics, analytics.requestSettings, PaletteEditor {})
            roots.forEach { root ->
                controls(root).filter { it is JTextField || it is JCheckBox || it is JComboBox<*> ||
                    it is JSpinner || it is JButton || (it is JTextArea && it.isEditable) }.forEach { control ->
                    assertFalse(control.toolTipText.isNullOrBlank(), "Missing tooltip: ${control.javaClass.simpleName}")
                    assertFalse(control.accessibleContext.accessibleDescription.isNullOrBlank())
                    assertFalse(control.toolTipText.startsWith("Configure ."))
                }
                controls(root).filterIsInstance<JLabel>().filter { it.getClientProperty("settingsRow") != null }
                    .forEach { assertFalse(it.toolTipText.isNullOrBlank(), it.text) }
            }
        }
    }

    @Test fun accountModelReplacementExposesOnlyTheDestinationSchemeToSynchronousReaders() = SwingUtilities.invokeAndWait {
        AnalyticsSettingsPanel { UsageSnapshot() }.use { panel ->
            var expected = QuotaPlanProfile.CODEX_WEEKLY
            val observed = mutableListOf<QuotaPlanProfile>()
            panel.planProfile.addPropertyChangeListener("model") {
                val options = panel.options() // Same reader used by isModified and capability previews.
                assertEquals(expected, options.planProfile)
                observed.add(options.planProfile)
            }
            panel.planProfile.addActionListener { assertNotNull(panel.options().planProfile) }
            val accounts = listOf(
                UsageVendor.CODEX to AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR, weeklyQuotaLabel = "Personal"),
                UsageVendor.CLAUDE to AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CLAUDE_WEEKLY, weeklyQuotaLabel = "Work"),
                UsageVendor.ANTIGRAVITY to AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.ANTIGRAVITY_REPORTED, weeklyQuotaLabel = "Other"),
            )
            repeat(3) {
                accounts.forEach { (vendor, options) ->
                    expected = options.planProfile
                    panel.account(vendor, options)
                    assertEquals(options, panel.options())
                    assertFalse(panel.isUpdating)
                }
            }
            assertEquals(9, observed.size)
        }
    }

    @Test fun updateGuardRecoversAfterAnExternalModelListenerThrows() = SwingUtilities.invokeAndWait {
        AnalyticsSettingsPanel { UsageSnapshot() }.use { panel ->
            val listener = java.beans.PropertyChangeListener { throw IllegalStateException("Synthetic callback failure") }
            panel.planProfile.addPropertyChangeListener("model", listener)
            assertFailsWith<IllegalStateException> {
                panel.account(UsageVendor.CLAUDE, AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CLAUDE_WEEKLY))
            }
            assertFalse(panel.isUpdating)
            assertEquals(QuotaPlanProfile.CLAUDE_WEEKLY, panel.options().planProfile)
            panel.planProfile.removePropertyChangeListener("model", listener)
            val options = AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR)
            panel.account(UsageVendor.CODEX, options)
            assertEquals(options, panel.options())
            assertFalse(panel.isUpdating)
        }
    }

    @Test fun emptySelectionAndProviderTextCallbacksKeepTheLastVerifiedScheme() {
        lateinit var analytics: AnalyticsSettingsPanel
        lateinit var providers: ProviderSettingsPanel
        val observed = mutableListOf<QuotaPlanProfile>()
        SwingUtilities.invokeAndWait {
            analytics = AnalyticsSettingsPanel { UsageSnapshot() }
            analytics.account(UsageVendor.CODEX, AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR))
            analytics.planProfile.selectedItem = null
            assertEquals(QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR, analytics.options().planProfile)
            providers = ProviderSettingsPanel({ observed.add(analytics.options().planProfile) }, {})
            val prefix = input(providers, "Widget prefix") as JTextField
            prefix.text = "draft"
        }
        SwingUtilities.invokeAndWait {
            val prefix = input(providers, "Widget prefix") as JTextField
            prefix.document.remove(0, 2)
        }
        SwingUtilities.invokeAndWait {
            val prefix = input(providers, "Widget prefix") as JTextField
            prefix.document.remove(0, prefix.document.length)
        }
        SwingUtilities.invokeAndWait {
            assertEquals(3, observed.size)
            assertTrue(observed.all { it == QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR })
            val settings = UsageSettings()
            settings.update(emptyList(), TrackerDefinition.defaults(), analyticsDisplay = analytics.options())
            assertEquals(QuotaPlanProfile.CODEX_WEEKLY_AND_FIVE_HOUR, settings.analyticsDisplay.planProfile)
            analytics.close()
        }
    }
}
