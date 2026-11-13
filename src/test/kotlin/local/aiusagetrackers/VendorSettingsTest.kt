package local.aiusagetrackers

import java.awt.Container
import java.awt.Dimension
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.*

class VendorSettingsTest {
    @Test fun genericQuotaPoolsAllowRemainingPercentageWithoutAWeeklyScheme() = SwingUtilities.invokeAndWait {
        AnalyticsSettingsPanel { UsageSnapshot() }.use { panel ->
            panel.vendor(UsageVendor.ANTIGRAVITY)
            panel.reset(AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.ANTIGRAVITY_REPORTED, showReportedPools = true))
            fun controls(root: Container): List<java.awt.Component> = root.components.toList() + root.components.filterIsInstance<Container>().flatMap(::controls)
            val mode = controls(panel).filterIsInstance<javax.swing.JComboBox<*>>().single { it.selectedItem is QuotaPercentMode }
            assertTrue(mode.isEnabled)
            mode.selectedItem = QuotaPercentMode.REMAINING
            assertEquals(QuotaPercentMode.REMAINING, panel.options().quotaPercentMode)
            assertFalse(panel.options().capabilities().weekly)
        }
    }
    @Test fun previewUsesThePlanLabelAndCannotInventAnUnsupportedBudget() = SwingUtilities.invokeAndWait {
        TrackerWidgetPreview { _, _ -> }.use { preview ->
            preview.update(listOf(TrackerDefinition(showPercentUsed = true, colorByQuotaGoal = true)),
                DisplayPreferences(weeklyPacing = true, allTokenPercentUsed = true), PlanCapabilities.UNKNOWN, "My Claude")
            fun labels(root: Container): List<javax.swing.JLabel> = root.components.filterIsInstance<javax.swing.JLabel>() +
                root.components.filterIsInstance<Container>().flatMap(::labels)
            assertTrue(labels(preview).any { it.text == "My Claude:" })
            val tracker = labels(preview).single { it.getClientProperty("trackerId") != null }
            assertFalse(tracker.text.contains('%'))
        }
    }
    @Test fun generalTrackingAndCarouselDraftsDoNotAlterOtherVendorSettings() = SwingUtilities.invokeAndWait {
        val config = ProviderConfiguration()
        val panel = ProviderSettingsPanel({}, {})
        panel.reset(config)
        assertEquals(config, panel.value())
        fun controls(root: Container): List<java.awt.Component> = root.components.toList() + root.components.filterIsInstance<Container>().flatMap(::controls)
        controls(panel).filterIsInstance<JCheckBox>().single { it.text == "Track usage" }.doClick()
        assertTrue(panel.value().eligible().isEmpty())
        assertEquals(3, panel.value().profiles.size)
        controls(panel).filterIsInstance<JCheckBox>().single { it.text == "Track usage" }.doClick()
        controls(panel).filterIsInstance<JCheckBox>().single { it.text == "Multi-vendor carousel" }.doClick()
        assertEquals(listOf(UsageVendor.CODEX), panel.value().display.members)
        assertTrue(panel.value().vendors.first { it.vendor == UsageVendor.CODEX }.tracking)
        assertFalse(panel.value().vendors.first { it.vendor == UsageVendor.CLAUDE }.tracking)
        val external = config.isolatedCopy().also { it.display.selected = UsageVendor.CLAUDE }
        assertEquals(UsageVendor.CLAUDE, panel.reconcileExternal(external).display.selected)
    }

    @Test fun wholeVendorPanelsHaveStableGeometryAndIndependentManualNavigation() = SwingUtilities.invokeAndWait {
        val first = JPanel().apply { preferredSize = Dimension(200, 25) }
        val second = JPanel().apply { preferredSize = Dimension(400, 30) }
        val carousel = VendorCarousel { 0L }
        carousel.use {
            carousel.configure(linkedMapOf(UsageVendor.CODEX to first, UsageVendor.CLAUDE to second), VendorDisplayState(verticalSeconds = 0))
            assertEquals(Dimension(400, 30), carousel.preferredSize)
            assertEquals(first, carousel.components.single())
            carousel.actionMap.get("alt RIGHT").actionPerformed(java.awt.event.ActionEvent(carousel, 0, "next"))
            assertEquals(UsageVendor.CLAUDE, carousel.selected)
            assertEquals(second, carousel.components.single())
            assertEquals(Dimension(400, 30), carousel.preferredSize)
        }
        assertEquals(0, carousel.componentCount)
    }
}
