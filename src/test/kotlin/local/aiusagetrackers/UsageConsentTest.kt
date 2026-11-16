package local.aiusagetrackers

import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.awt.Container
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class UsageConsentTest {
    @get:Rule val temp = TemporaryFolder()
    private fun descendants(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    @Test fun agreementIsExplicitVersionedAndRestoredLocally() {
        val consent = UsageConsent()
        assertFalse(consent.accepted)
        consent.loadState(UsageConsent.ConsentState())
        assertFalse(consent.accepted)
        consent.setAccepted(true)
        val restored = UsageConsent()
        restored.loadState(consent.state)
        assertTrue(restored.accepted)
        restored.setAccepted(false)
        assertFalse(restored.accepted)
        restored.loadState(UsageConsent.ConsentState().also { it.acceptedVersion = UsageConsent.CURRENT_VERSION + 1 })
        assertFalse(restored.accepted)
    }

    @Test fun privacyLinkDoesNotGrantAgreementAndControlsExplainTheirEffect() = SwingUtilities.invokeAndWait {
        var opened = false
        val panel = UsageConsentPanel { opened = true }
        val controls = descendants(panel)
        val checkbox = controls.filterIsInstance<JCheckBox>().single()
        val button = controls.filterIsInstance<JButton>().single()
        assertFalse(panel.accepted)
        button.doClick()
        assertTrue(opened)
        assertFalse(panel.accepted)
        for (control in listOf(checkbox, button)) {
            assertFalse(control.toolTipText.isNullOrBlank())
            assertFalse(control.accessibleContext.accessibleDescription.isNullOrBlank())
            val label = controls.filterIsInstance<JLabel>().single { it.labelFor === control }
            assertFalse(label.toolTipText.isNullOrBlank())
            assertFalse(label.accessibleContext.accessibleDescription.isNullOrBlank())
        }
        checkbox.doClick()
        assertTrue(panel.accepted)
        panel.accepted = false
        assertFalse(panel.accepted)
    }

    @Test fun monitorCannotCreateDatabaseOrCollectBeforeAgreementAndStopsWhenWithdrawn() = runBlocking {
        val allowed = AtomicBoolean(false)
        val paths = AtomicInteger()
        val scans = AtomicInteger()
        val location = temp.root.toPath().resolve("usage.sqlite")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val monitor = UsageMonitor(scope, Clock.systemUTC(), { ZoneOffset.UTC },
                { paths.incrementAndGet(); location }, { emptyList() },
                { error("No source discovery should be needed by this fixture") }, { AnalyticsDisplayOptions() },
                customCollect = { _, _, _, check -> check(); scans.incrementAndGet() },
                allowed = allowed::get)
            monitor.refresh()
            delay(400.milliseconds)
            assertEquals(0, paths.get())
            assertEquals(0, scans.get())
            assertFalse(Files.exists(location))
            allowed.set(true)
            withTimeout(5000.milliseconds) { while (scans.get() == 0) delay(25.milliseconds) }
            assertTrue(Files.exists(location))
            allowed.set(false)
            val previous = scans.get()
            monitor.refresh()
            delay(500.milliseconds)
            assertEquals(previous, scans.get())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
    }

    @Test fun widgetExplainsAgreementInsteadOfLoadingOrShowingOldUsage() {
        val values = WidgetText.values(UsageSnapshot(agreementRequired = true))
        assertEquals(listOf("agreement required"), values.map { it.displayText() })
    }
}
