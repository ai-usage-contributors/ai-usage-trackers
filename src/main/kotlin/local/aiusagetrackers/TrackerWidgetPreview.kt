package local.aiusagetrackers

import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import java.time.ZoneOffset
import javax.swing.*

/** Settings-owned renderer. No service, database, project, timers or live widget instances. */
class TrackerWidgetPreview(private val move: (String, Int) -> Unit) : JPanel(BorderLayout(0, 4)), AutoCloseable {
    internal class Drag internal constructor(
        internal val owner: Any,
        internal val revision: Long,
        internal val id: String
    )

    private val owner = Any()
    private var revision = 0L
    private var definitions = emptyList<TrackerDefinition>()
    private var preferences = DisplayPreferences()
    private var quotaApplicable = true
    private var planLabel = "Codex"
    private var active: Drag? = null
    private var closed = false
    private var insertion: Int? = null
    private val labels = mutableListOf<JLabel>()
    private val sampleState = JComboBox(arrayOf("Sample totals", "Unavailable", "Loading"))
    private val sampleHours = JSpinner(javax.swing.SpinnerNumberModel(84, 0, 168, 1))
    private val paceControls = SettingsForm().apply {
        addRow(JLabel("Hours until reset"), sampleHours)
    }
    private val row = object : JPanel(FlowLayout(FlowLayout.LEADING, 4, 4)) {
        override fun paint(g: Graphics) {
            super.paint(g)
            insertion?.let { index ->
                g.color = UIManager.getColor("Component.focusColor") ?: foreground
                val x = labels.getOrNull(index)?.x?.minus(2)
                    ?: labels.lastOrNull()?.let { it.x + it.width + 2 } ?: 0
                g.fillRect(x, 2, 2, height - 4)
            }
        }
    }
    private val flavor = DataFlavor(
        "${DataFlavor.javaJVMLocalObjectMimeType};class=${Drag::class.java.name}",
        "Settings tracker", Drag::class.java.classLoader,
    )
    private val handler = object : TransferHandler() {
        override fun getSourceActions(c: JComponent): Int = MOVE
        override fun createTransferable(c: JComponent): Transferable? {
            val id = c.getClientProperty("trackerId") as? String ?: return null
            val drag = beginDrag(id) ?: return null
            return object : Transferable {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(flavor)
                override fun isDataFlavorSupported(candidate: DataFlavor): Boolean = candidate == flavor
                override fun getTransferData(candidate: DataFlavor): Any {
                    if (!isDataFlavorSupported(candidate)) throw UnsupportedFlavorException(candidate)
                    return drag
                }
            }
        }

        private fun drag(support: TransferSupport): Drag? = try {
            if (!support.isDrop || !support.isDataFlavorSupported(flavor)) null
            else support.transferable.getTransferData(flavor) as? Drag
        } catch (_: Exception) {
            null
        }

        override fun canImport(support: TransferSupport): Boolean {
            val drag = drag(support)
            val valid = drag != null && accepts(drag)
            insertion = if (valid) dropIndex(support) else null
            row.repaint()
            if (valid) support.setDropAction(MOVE)
            return valid
        }

        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            val drag = drag(support) ?: return false
            return drop(drag, dropIndex(support))
        }

        override fun exportDone(source: JComponent, data: Transferable?, action: Int) = finishDrag()

        private fun dropIndex(support: TransferSupport): Int {
            val point = SwingUtilities.convertPoint(support.component, support.dropLocation.dropPoint, row)
            return insertionAt(point)
        }
    }

    init {
        val form = SettingsForm()
        form.addRow(JLabel("Tracker preview data"), sampleState)
        form.addBlock(paceControls)
        SettingsHelp.attach(sampleState, "Choose illustrative preview data without collecting usage.")
        sampleState.addActionListener { revision++; if (active == null && !closed) render() }
        SettingsHelp.attach(sampleHours, "Preview weekly colors at different hours before quota reset.")
        paceControls.components.filterIsInstance<JLabel>().forEach { SettingsHelp.attach(it, sampleHours.toolTipText) }
        sampleHours.addChangeListener { revision++; if (active == null && !closed) render() }
        row.transferHandler = handler
        val scroll = JScrollPane(row, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.viewport.transferHandler = handler
        scroll.transferHandler = handler
        scroll.preferredSize = JBUI.size(600, 62)
        scroll.minimumSize = JBUI.size(0, 62)
        SettingsHelp.attach(scroll, "Preview sample values and drag visible trackers to change their order.")
        form.addRow(JLabel("Tracker sample data (UTC)"), scroll)
        add(form, BorderLayout.CENTER)
        getAccessibleContext().accessibleName = "Settings widget preview with sample data"
        render()
    }

    fun update(
        trackers: List<TrackerDefinition>,
        display: DisplayPreferences,
        capabilities: PlanCapabilities = PlanCapabilities.CODEX_WEEKLY,
        label: String = "Codex"
    ) {
        if (closed) return
        val copies = trackers.map { it.copy() }
        val normalized = display.normalized().let {
            if (QuotaEligibility.budget(capabilities)) it else it.copy(
                weeklyPacing = false,
                allTokenPercentUsed = false
            )
        }
        val applicable = QuotaEligibility.budget(capabilities)
        if (definitions == copies && preferences == normalized && quotaApplicable == applicable && planLabel == label) return
        definitions = copies
        preferences = normalized
        quotaApplicable = applicable; planLabel = label
        revision++
        // Invalidated drags keep their geometry until release, but can no longer commit.
        if (active == null) render()
    }

    private fun render() {
        paceControls.isVisible = preferences.coloringEnabled && preferences.weeklyPacing
        row.removeAll(); labels.clear()
        row.add(JLabel("$planLabel:").apply { putClientProperty("html.disable", true) })
        val now = Instant.parse("2026-11-04T12:00:00Z")
        val quota = QuotaMetricPresentation(
            java.math.BigDecimal("16621621.6216216216"),
            "Synthetic weekly budget for this preview.",
            reset = now.plusSeconds((sampleHours.value as Number).toLong() * 3600),
            evaluatedAt = now,
            applicable = quotaApplicable
        )
        for (definition in definitions.filter { it.visible }) {
            if (labels.isNotEmpty()) row.add(JLabel("·"))
            val label = sampleLabel(definition, sampleValue(definition, now, quota))
            labels += label
            row.add(label)
        }
        if (labels.isEmpty()) row.add(JLabel("No visible trackers"))
        row.components.filterIsInstance<JLabel>().forEach { it.font = WidgetAppearance.font(preferences, it.font) }
        row.revalidate(); row.repaint()
    }

    private fun sampleValue(
        definition: TrackerDefinition,
        now: Instant,
        quota: QuotaMetricPresentation,
    ): StatusValue {
        val period = ResolvedPeriod(listOf(UsageInterval(now.minusSeconds(3600), now)), ZoneOffset.UTC)
        val total = if (sampleState.selectedIndex == 1) null else 1_230_000L
        val value = WidgetText.trackerValue(TrackerResult(definition, period, total), now, preferences, quota)
        return if (sampleState.selectedIndex == 2) {
            value.copy(text = "loading", raw = null, state = StatusValueState.LOADING)
        } else value
    }

    private fun sampleLabel(definition: TrackerDefinition, value: StatusValue): JLabel {
        return JLabel().apply {
            putClientProperty("html.disable", !WidgetAppearance.usesHtml(value))
            text = WidgetAppearance.text(value, foreground)
            font = WidgetAppearance.font(preferences, font)


            putClientProperty("trackerId", definition.id)
            border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Preview sample values and drag visible trackers to change their order."
            accessibleContext.accessibleName =
                "${definition.name}: ${if (sampleState.selectedIndex == 2) "loading" else value.text} — draggable sample tracker"
            transferHandler = handler
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (active == null && e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK != 0) {
                        handler.exportAsDrag(this@apply, e, TransferHandler.MOVE)
                    }
                }
            })
        }
    }

    internal fun insertionAt(point: Point): Int = labels.indexOfFirst { point.x < it.x + it.width / 2 }
        .let { if (it < 0) labels.size else it }

    internal fun beginDrag(id: String): Drag? {
        if (closed || active != null || definitions.none { it.id == id && it.visible }) return null
        return Drag(owner, revision, id).also { active = it }
    }

    private fun accepts(drag: Drag): Boolean =
        !closed && drag === active && drag.owner === owner && drag.revision == revision

    internal fun drop(drag: Drag, index: Int): Boolean {
        if (!accepts(drag) || index !in 0..definitions.count { it.visible }) return false
        val changed = TrackerOrdering.moveVisible(definitions, drag.id, index) != definitions
        finishDrag()
        if (changed) move(drag.id, index)
        return true
    }

    internal fun finishDrag() {
        active = null; insertion = null
        if (!closed) render()
    }

    override fun close() {
        closed = true; revision++; active = null; insertion = null
        labels.forEach { it.transferHandler = null }
        row.removeAll(); labels.clear(); row.transferHandler = null
        removeAll()
    }
}
