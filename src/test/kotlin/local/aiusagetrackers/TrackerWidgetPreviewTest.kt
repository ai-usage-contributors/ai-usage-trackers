package local.aiusagetrackers

import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerWidgetPreviewTest {
    private fun definitions() = listOf("a", "b", "c").map {
        TrackerDefinition(id = it, name = "Tracker $it", visible = it != "b")
    }

    @Test
    fun renderedSegmentsShareDraftOrderButDoNotSaveUntilApply() = SwingUtilities.invokeAndWait {
        val settings = UsageSettings()
        settings.update(emptyList(), definitions())
        var draft = settings.trackers
        var moves = 0
        lateinit var preview: TrackerWidgetPreview
        preview = TrackerWidgetPreview { id, insertion ->
            moves++
            draft = TrackerOrdering.moveVisible(draft, id, insertion)
            preview.update(draft, DisplayPreferences())
        }
        preview.use {
            preview.update(draft, DisplayPreferences())
            assertEquals(listOf("Tracker a: 1.2M", "Tracker c: 1.2M"), preview.segmentTexts())
            assertEquals("Settings widget preview with sample data", preview.getAccessibleContext().accessibleName)
            assertTrue(preview.descendants().filterIsInstance<JLabel>()
                .filter { it.getClientProperty("trackerId") != null }
                .all { "draggable sample tracker" in it.accessibleContext.accessibleName })
            preview.setSize(900, 110)
            preview.layoutTree()
            val image = BufferedImage(900, 110, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try { preview.printAll(graphics) } finally { graphics.dispose() }
            val imagePath = Path.of("build/reports/preview/tracker-widget-preview.png")
            Files.createDirectories(imagePath.parent)
            assertTrue(ImageIO.write(image, "png", imagePath.toFile()))
            assertNull(preview.beginDrag("b"))
            val ticket = assertNotNull(preview.beginDrag("c"))
            assertTrue(preview.drop(ticket, 0))
            assertEquals(1, moves)
            assertEquals(listOf("c", "b", "a"), draft.map { it.id })
            assertEquals(listOf("a", "b", "c"), settings.trackers.map { it.id })
            assertEquals(listOf("Tracker c: 1.2M", "Tracker a: 1.2M"), preview.segmentTexts())
            // Reset/Cancel discards the draft; saving explicitly persists only the order.
            draft = settings.trackers
            preview.update(draft, DisplayPreferences())
            assertTrue(preview.drop(assertNotNull(preview.beginDrag("a")), 2))
            settings.update(emptyList(), draft)
            assertEquals(listOf("c", "b", "a"), settings.trackers.map { it.id })
            val portable = TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(draft)))
            assertEquals(draft.map { it.name }, portable.trackers.map { it.name })
        }
    }

    @Test
    fun transferPayloadStaysInJvmAndClipboardPasteIsRejected() = SwingUtilities.invokeAndWait {
        var moves = 0
        val preview = TrackerWidgetPreview { _, _ -> moves++ }
        preview.use {
            preview.update(definitions(), DisplayPreferences())
            val label = preview.descendants().filterIsInstance<JLabel>()
                .first { it.getClientProperty("trackerId") != null }
            val clipboard = Clipboard("Private test clipboard")
            label.transferHandler.exportToClipboard(label, clipboard, TransferHandler.MOVE)
            val payload = assertNotNull(clipboard.getContents(null))
            assertTrue(payload.transferDataFlavors.single().isMimeTypeEqual(DataFlavor.javaJVMLocalObjectMimeType))
            val support = TransferHandler.TransferSupport(label, payload)
            assertFalse(label.transferHandler.canImport(support))
            assertFalse(label.transferHandler.importData(support))
            assertEquals(0, moves)
        }
    }

    @Test
    fun staleExternalCancelledAndDisposedDragsCannotChangeDraft() = SwingUtilities.invokeAndWait {
        var moves = 0
        val preview = TrackerWidgetPreview { _, _ -> moves++ }
        val other = TrackerWidgetPreview { _, _ -> error("Cross-dialog transfer") }
        try {
            val draft = definitions()
            preview.update(draft, DisplayPreferences())
            other.update(draft, DisplayPreferences())
            val ticket = assertNotNull(preview.beginDrag("a"))
            assertFalse(other.drop(ticket, 2))
            assertFalse(preview.drop(ticket, 3))
            preview.update(draft.map { it.copy(name = "Edited") }, DisplayPreferences())
            // Layout stays frozen during the gesture; committing an old revision is rejected.
            assertTrue(preview.segmentTexts().first().startsWith("Tracker a"))
            assertFalse(preview.drop(ticket, 2))
            preview.finishDrag()
            assertTrue(preview.segmentTexts().all { it.startsWith("Edited") })
            val cancelled = assertNotNull(preview.beginDrag("a"))
            preview.finishDrag()
            assertFalse(preview.drop(cancelled, 2))
            val disposed = assertNotNull(preview.beginDrag("a"))
            preview.close()
            assertFalse(preview.drop(disposed, 2))
            assertNull(preview.beginDrag("a"))
            assertEquals(0, moves)
            assertEquals(0, preview.componentCount)
        } finally { preview.close(); other.close() }
    }

    @Test
    fun samplesAreExplicitAndDisplayStatesNeverNeedCollectedUsage() = SwingUtilities.invokeAndWait {
        val preview = TrackerWidgetPreview { _, _ -> error("No-op move must not publish") }
        preview.use {
            preview.update(definitions(), DisplayPreferences(datePattern = "invalid['"))
            assertTrue(preview.descendants().filterIsInstance<JLabel>().any { "sample data" in it.text })
            val states = preview.descendants().filterIsInstance<JComboBox<*>>().single()
            states.selectedIndex = 1
            assertEquals(listOf("Tracker a: unavailable", "Tracker c: unavailable"), preview.segmentTexts())
            states.selectedIndex = 2
            assertEquals(listOf("Tracker a: loading", "Tracker c: loading"), preview.segmentTexts())
            assertTrue(preview.drop(assertNotNull(preview.beginDrag("a")), 0))
            preview.setSize(700, 100)
            preview.doLayout()
            assertEquals(0, preview.insertionAt(Point(-100, 0)))
            assertEquals(2, preview.insertionAt(Point(Int.MAX_VALUE, 0)))
        }
    }

    private fun TrackerWidgetPreview.segmentTexts() = descendants().filterIsInstance<JLabel>()
        .filter { it.getClientProperty("trackerId") != null }.map { it.text }

    private fun Container.descendants(): List<Component> = components.flatMap {
        listOf(it) + if (it is Container) it.descendants() else emptyList()
    }

    private fun Container.layoutTree() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutTree() }
    }
}
