package local.aiusagetrackers

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WidgetLifecycleTest {
    @Test
    fun installingBeforeConsentOffersSettingsWithoutOpeningThem() = SwingUtilities.invokeAndWait {
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            check(method.name == "isDisposed") { "Unexpected project access: ${method.name}" }
            false
        } as Project
        val statusBar = Proxy.newProxyInstance(StatusBar::class.java.classLoader, arrayOf(StatusBar::class.java)) { _, method, _ ->
            error("Unexpected status-bar access: ${method.name}")
        } as StatusBar
        var settingsOpened = 0
        val widget = UsageWidget(project, { false }, { settingsOpened++ })
        try {
            widget.install(statusBar)
            assertEquals(0, settingsOpened)
            val button = (widget.component as Container).getComponent(0) as JButton
            assertEquals("Review usage agreement", button.text)
            assertFalse(button.accessibleContext.accessibleDescription.isNullOrBlank())
            button.doClick(0)
            assertEquals(1, settingsOpened)
        } finally {
            widget.dispose()
        }
    }

    @Test
    fun factoryOwnsIndependentWidgetsAndDisposalIsIdempotentBeforeInstall() = SwingUtilities.invokeAndWait {
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            error("Uninstalled widget must not access project services: ${method.name}")
        } as Project
        val factory = UsageWidgetFactory()
        val first = factory.createWidget(project) as UsageWidget
        val second = factory.createWidget(project) as UsageWidget
        var firstDisposed = false
        var secondDisposed = false
        Disposer.register(first) { firstDisposed = true }
        Disposer.register(second) { secondDisposed = true }
        try {
            assertNotSame(first, second)
            assertNotSame(first.component, second.component)
            assertEquals("CodexDailyTokens", first.ID())
            factory.disposeWidget(first)
            factory.disposeWidget(first)
            assertTrue(firstDisposed)
            // Disposed controls cannot open popups or consult the application service.
            (first.component as Container).components.filterIsInstance<AbstractButton>().forEach { it.doClick() }
            assertFalse(secondDisposed)
        } finally {
            factory.disposeWidget(first)
            factory.disposeWidget(second)
        }
    }
}
