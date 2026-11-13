package local.aiusagetrackers

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.Container
import java.lang.reflect.Proxy
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WidgetLifecycleTest {
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
