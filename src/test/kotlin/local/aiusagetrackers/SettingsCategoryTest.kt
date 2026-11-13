package local.aiusagetrackers

import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.*
import kotlin.test.*

class SettingsCategoryTest {
    @Test fun collapsingRetainsDraftAndShrinksWithoutChangingSiblingState() = SwingUtilities.invokeAndWait {
        val draft = JTextField("Invalid unsaved draft")
        val form = SettingsForm()
        form.section("Period").addRow(JLabel("Zone"), draft)
        form.section("Status bar").addRow(JLabel("Show"), JCheckBox())
        val first = form.components[0] as SettingsCategory
        val second = form.components[1] as SettingsCategory
        val height = form.preferredSize.height
        first.header.doClick()
        assertFalse(first.expanded)
        assertFalse(first.content.isVisible)
        assertTrue(second.expanded)
        assertTrue(form.preferredSize.height < height)
        first.header.actionMap.get("toggleCategory").actionPerformed(ActionEvent(first.header, 0, ""))
        assertTrue(first.expanded)
        assertSame(draft, first.content.components[1])
        assertEquals("Invalid unsaved draft", draft.text)
        assertTrue(first.header.font.isBold)
        assertTrue(first.header.font.size2D > draft.font.size2D)
        assertEquals(Font.BOLD, first.header.font.style)
    }

    @Test fun disabledFieldsDoNotDisableCategoryNavigationAndRebuildResetsRowState() = SwingUtilities.invokeAndWait {
        val form = SettingsForm()
        val field = JTextField()
        form.section("Period").addRow(JLabel("Name"), field)
        val category = form.components.single() as SettingsCategory
        form.enableFields(false)
        assertFalse(field.isEnabled)
        assertTrue(category.header.isEnabled)
        category.header.doClick()
        form.enableFields(true)
        assertFalse(category.expanded)
        assertTrue(field.isEnabled)
        form.removeAll()
        form.addRow(JLabel("Fresh"), JTextField())
        assertEquals(3, form.componentCount)
        assertEquals(0, (form.layout as java.awt.GridBagLayout).getConstraints(form.components[0]).gridy)
    }

    @Test fun validationRevealsCollapsedAncestorsAndCorrectTabWithoutChangingDraft() = SwingUtilities.invokeAndWait {
        val draft = JTextField("bad")
        val inner = SettingsCategory("Period", draft, false)
        val outer = SettingsCategory("Tracker", inner, false)
        val tabs = settingsTabs("Trackers" to outer, "Quota" to JPanel())
        tabs.selectedIndex = 1
        val failure = assertFailsWith<IllegalArgumentException> {
            validateSetting(draft) { require(false) { "Invalid zone" } }
        }
        assertEquals("Invalid zone", failure.message)
        assertEquals(0, tabs.selectedIndex)
        assertTrue(inner.expanded)
        assertTrue(outer.expanded)
        assertEquals("bad", draft.text)
        assertEquals(0, (tabs.getComponentAt(0) as JScrollPane).viewport.viewPosition.x)
    }
}
