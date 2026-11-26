package local.aiusagetrackers

import com.intellij.openapi.wm.IdeGlassPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.*

/** Owns only presentation and gestures; never reads usage files or queries storage. */
class UsageRibbon(private val nanoTime: () -> Long = System::nanoTime) : JViewport(), AutoCloseable,
    IdeGlassPane.TopComponent {
    override fun canBePreprocessed(e: MouseEvent): Boolean = e !is MouseWheelEvent && !SwingUtilities.isLeftMouseButton(e)

    internal var pointerInside: (Container) -> Boolean? = { WidgetPointer.inside(it) }
    private var logicalWidth = 280
    private var autoExpand = false
    private var chooserWidthReserve = 0
    private var retainedWidth = 0
    private fun requestedWidth(): Int {
        val base = JBUI.scale(logicalWidth)
        val widest = if (autoExpand) entries.maxOfOrNull { it.measuredWidth() } ?: 0 else 0
        if (!autoExpand) return base
        retainedWidth = maxOf(retainedWidth, base, if (widest > base) widest + chooserWidthReserve else base)
        return retainedWidth
    }

    internal fun reserveChooserWidth(pixels: Int) {
        if (autoExpand && pixels > chooserWidthReserve) {
            chooserWidthReserve = pixels; revalidate()
        }
    }

    internal val widthExpansionWarning: String?
        get() {
            if (!autoExpand || requestedWidth() <= JBUI.scale(logicalWidth)) return null
            return "The ribbon widened to fit a long value. Acknowledge keeps the new width. " +
                    "Revert restores the previous width and turns off automatic widening."
        }
    private var speed = 30
    private var scrollMode = RibbonScrollMode.RESTART
    private var direction = 1
    private var offset = 0.0
    private var pressedX: Int? = null
    private var pressedY: Int? = null
    private var wheelRemainder = 0.0
    private var dragRemainder = 0.0
    private var horizontalInterval = 3
    private var verticalInterval = 3
    private var verticalDirection = RibbonStepDirection.UP
    private var nextStep = nanoTime() + 3_000_000_000L
    private var anchor: String? = null
    private var entries = emptyList<RibbonValueButton>()
    private var layoutStamp: List<Any?> = emptyList()
    private var outgoingRow: BufferedImage? = null
    private var transitionStarted = 0L
    private val transitionNanos = 160_000_000L
    private fun transitionFraction(): Float {
        val fraction = ((nanoTime() - transitionStarted).toDouble() / transitionNanos).coerceIn(0.0, 1.0)
        return (fraction * fraction * (3 - 2 * fraction)).toFloat()
    }

    private fun finishTransition() {
        if (outgoingRow != null) {
            outgoingRow = null; repaint()
        }
    }

    private val content = object : JPanel(null), IdeGlassPane.TopComponent {
        override fun canBePreprocessed(e: MouseEvent): Boolean = e !is MouseWheelEvent && !SwingUtilities.isLeftMouseButton(e)
    }.apply { isOpaque = false }

    override fun paintChildren(g: Graphics) {
        val outgoing = outgoingRow
        if (outgoing == null) {
            super.paintChildren(g); return
        }
        val fraction = transitionFraction()
        val graphics = g.create() as Graphics2D
        try {
            graphics.composite = AlphaComposite.SrcOver.derive(1f - fraction)
            graphics.drawImage(outgoing, 0, 0, null)
            graphics.composite = AlphaComposite.SrcOver.derive(fraction)
            super.paintChildren(graphics)
        } finally {
            graphics.dispose()
        }
    }

    internal var chooserActive = false
        set(value) {
            if (value) finishTransition()
            field = value
            entries.forEach { it.toolTipText = if (value) null else it.value.accessibleText }
        }
    internal var hoverRequested: (() -> Unit)? = null
    internal var hoverEnded: (() -> Unit)? = null
    internal var closeChooser: (() -> Unit)? = null
    internal val vertical: Boolean get() = scrollMode == RibbonScrollMode.VERTICAL_STEP
    internal val stepDirection: RibbonStepDirection get() = verticalDirection
    internal val selectedEntry: String? get() = anchor
    internal var dragged = false
        private set
    private var previousTick = nanoTime()
    private var pauseUntil = 0L
    private val animation = Timer(25) { tick() }
    internal var overlayPaused = false
    private val gestures = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            finishTransition()
            pressedX = e.xOnScreen
            pressedY = e.yOnScreen
            dragRemainder = 0.0
            dragged = false
            e.consume()
        }

        override fun mouseDragged(e: MouseEvent) {
            val previous = pressedX ?: return
            val delta = if (vertical) (pressedY ?: e.yOnScreen) - e.yOnScreen else previous - e.xOnScreen
            if (kotlin.math.abs(delta) >= JBUI.scale(3) || dragged) {
                dragged = true
                if (scrollMode.stepped) {
                    dragRemainder += delta
                    val steps = (dragRemainder / JBUI.scale(30)).toInt()
                    if (steps != 0) {
                        manualStep(steps); dragRemainder -= steps * JBUI.scale(30)
                    }
                } else scrollBy(delta.toDouble())
                pressedX = e.xOnScreen
                pressedY = e.yOnScreen
                (e.component as? JButton)?.model?.isArmed = false
            }
            e.consume()
        }

        override fun mouseReleased(e: MouseEvent) {
            if (pressedX == null) return
            if (dragged) (e.component as? JButton)?.model?.isArmed = false
            pressedX = null
            pressedY = null
            manualPause()
            e.consume()
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            if (scrollMode.stepped) {
                val rotation = e.preciseWheelRotation
                if (wheelRemainder * rotation < 0) wheelRemainder = 0.0
                wheelRemainder += rotation
                val steps = wheelRemainder.toInt()
                if (steps != 0) {
                    manualStep(steps); wheelRemainder -= steps
                }
                manualPause()
            } else scrollBy(e.preciseWheelRotation * JBUI.scale(30))
            e.consume()
        }

        override fun mouseEntered(e: MouseEvent) {
            if (vertical && pressedX == null) hoverRequested?.invoke()
        }
    }

    init {
        isOpaque = false
        attach(this)
        attach(content)
    }

    fun attach(component: JComponent) {
        component.addMouseListener(gestures)
        component.addMouseMotionListener(gestures)
        component.addMouseWheelListener(gestures)
    }

    fun configure(
        width: Int, pixelsPerSecond: Int, mode: RibbonScrollMode = RibbonScrollMode.RESTART,
        horizontalStepSeconds: Int = 3, verticalStepSeconds: Int = 3,
        stepDirection: RibbonStepDirection = RibbonStepDirection.UP, expandToFit: Boolean = false
    ) {
        if (autoExpand != expandToFit) {
            autoExpand = expandToFit; chooserWidthReserve = 0; retainedWidth = 0; closeChooser?.invoke(); revalidate()
        }
        val directionChanged = mode == RibbonScrollMode.VERTICAL_STEP && verticalDirection != stepDirection
        val intervalChanged = when (mode) {
            RibbonScrollMode.HORIZONTAL_STEP -> horizontalInterval != horizontalStepSeconds
            RibbonScrollMode.VERTICAL_STEP -> verticalInterval != verticalStepSeconds
            else -> false
        }
        val timingChanged = scrollMode != mode || intervalChanged || directionChanged
        if (timingChanged) finishTransition()
        if (scrollMode != mode || directionChanged) {
            closeChooser?.invoke(); scrollMode = mode; offset = 0.0; direction = 1; pauseUntil = 0L
            anchor = if (mode == RibbonScrollMode.HORIZONTAL_STEP) null else entries.firstOrNull()?.value?.id
            wheelRemainder = 0.0; dragRemainder = 0.0; pressedX = null; pressedY = null
            revalidate()
        }
        horizontalInterval = horizontalStepSeconds; verticalInterval = verticalStepSeconds; verticalDirection =
            stepDirection
        if (timingChanged) nextStep = nanoTime() + intervalNanos()
        if (logicalWidth != width) {
            logicalWidth = width; revalidate()
        }
        speed = pixelsPerSecond
        renderEntries()
        isVisible = (view?.preferredSize?.width ?: 0) > 0
    }

    internal fun setEntries(buttons: List<RibbonValueButton>) {
        finishTransition()
        if (scrollMode != RibbonScrollMode.HORIZONTAL_STEP || anchor != null)
            anchor = RibbonStepLayout.survivingAnchor(
                entries.map { it.value.id }, buttons.map { it.value.id }, anchor,
                if (vertical) verticalDirection.increment else 1
            )
        entries = buttons
        content.removeAll()
        buttons.forEach {
            attach(it); content.add(it)
            if (chooserActive) it.toolTipText = null
        }
        if (view !== content) view = content
        renderEntries(); isVisible = buttons.isNotEmpty(); revalidate(); repaint()
    }

    private fun geometry() =
        entries.distinctBy { it.value.id }.map { RibbonStepLayout.Entry(it.value.id, it.measuredWidth()) }

    private fun gap(): Int =
        (entries.firstOrNull()?.let { it.getFontMetrics(it.font).stringWidth("· ") } ?: 0) + JBUI.scale(2)

    private fun availableWidth(): Int = extentSize.width.takeIf { it > 0 } ?: minOf(requestedWidth(), contentWidth())
    private fun intervalNanos(): Long = (if (vertical) verticalInterval else horizontalInterval) * 1_000_000_000L

    internal fun chooserRows(packingWidth: Int = availableWidth()): List<List<RibbonValueButton>> {
        if (!vertical) return emptyList()
        val ordered = RibbonStepLayout.ordered(geometry(), anchor, verticalDirection.increment)
        val visible = RibbonStepLayout.row(ordered, availableWidth(), gap()).map { it.id }.toSet()
        val rows = RibbonStepLayout.remainingRows(ordered, visible, packingWidth, gap())
            .map { row -> row.map { entry -> entries.first { it.value.id == entry.id } } }
        return if (verticalDirection == RibbonStepDirection.UP) rows.reversed() else rows
    }

    internal fun selectEntry(id: String) {
        if (entries.none { it.value.id == id }) return
        finishTransition()
        anchor = id; manualPause(); renderEntries(); repaint()
    }

    private fun renderEntries() {
        if (view !== content) return
        val width = availableWidth()
        val stamp = listOf(scrollMode, width, anchor, entries)
        if (stamp == layoutStamp) return
        finishTransition()
        layoutStamp = stamp
        val height = entries.maxOfOrNull { it.preferredSize.height } ?: 0
        if (vertical) renderVertical(width, height) else renderHorizontal(width, height)
    }

    private fun renderVertical(width: Int, height: Int) {
        val natural = geometry()
        val ordered = if (RibbonStepLayout.fits(natural, width, gap())) natural
        else RibbonStepLayout.ordered(natural, anchor, verticalDirection.increment)
        val row = RibbonStepLayout.row(ordered, width, gap())
        entries.forEach { it.isVisible = false }
        var x = if (row.size == 1) ((width - row.single().width) / 2).coerceAtLeast(0) else 0
        row.forEachIndexed { index, entry ->
            val button = entries.first { it.value.id == entry.id }
            val prefix = if (index == 0) "" else "· "
            button.render(prefix, width)
            val buttonWidth = minOf(width, entry.width + button.getFontMetrics(button.font).stringWidth(prefix))
            if (index > 0) x += JBUI.scale(2)
            button.setBounds(x, 0, buttonWidth, height); button.isVisible = true; x += buttonWidth
        }
        content.preferredSize = Dimension(width, height)
        content.setSize(width, height)
        offset = 0.0; viewPosition = Point(0, 0)
    }

    private fun renderHorizontal(width: Int, height: Int) {
        val natural = geometry()
        var x = 0
        entries.forEachIndexed { index, button ->
            val prefix = if (index == 0 && scrollMode != RibbonScrollMode.CONTINUOUS) "" else "· "
            button.render(prefix)
            if (index > 0) x += JBUI.scale(2)
            val size = button.preferredSize
            button.isVisible = true; button.setBounds(x, 0, size.width, height); x += size.width
        }
        content.preferredSize = Dimension(x, height)
        content.setSize(x, height)
        if (scrollMode == RibbonScrollMode.HORIZONTAL_STEP) {
            val offsets = RibbonStepLayout.horizontalOffsets(natural, width, gap())
            offset = offsets.firstOrNull { it.first == anchor }?.second?.toDouble() ?: 0.0
            viewPosition = Point(offset.toInt(), 0)
        }
    }

    override fun doLayout() {
        super.doLayout(); renderEntries()
    }

    private fun manualPause() {
        val now = nanoTime()
        pauseUntil = now + 2_000_000_000L
        nextStep = now + maxOf(2_000_000_000L, intervalNanos())
    }

    private fun manualStep(steps: Int) {
        advanceEntry(steps); manualPause()
    }

    private fun advanceEntry(steps: Int) {
        val natural = geometry()
        if (natural.isEmpty() || RibbonStepLayout.fits(natural, availableWidth(), gap())) return
        val previousAnchor = anchor
        val snapshot = if (width > 0 && height > 0) {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also {
                val graphics = it.createGraphics()
                try {
                    printAll(graphics)
                } finally {
                    graphics.dispose()
                }
            }
        } else null
        if (vertical) {
            val index = natural.indexOfFirst { it.id == anchor }.coerceAtLeast(0)
            anchor = natural[Math.floorMod(index + steps * verticalDirection.increment, natural.size)].id
        } else {
            val offsets = RibbonStepLayout.horizontalOffsets(natural, availableWidth(), gap())
            val index = offsets.indexOfFirst { it.first == anchor }.coerceAtLeast(0)
            anchor = offsets[Math.floorMod(index + steps, offsets.size)].first
        }
        renderEntries()
        if (snapshot != null && anchor != previousAnchor) {
            outgoingRow = snapshot; transitionStarted = nanoTime()
        }
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(
        if (autoExpand && requestedWidth() > JBUI.scale(logicalWidth)) requestedWidth() else minOf(
            requestedWidth(),
            contentWidth()
        ),
        view?.preferredSize?.height ?: 0
    )

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize

    override fun addNotify() {
        super.addNotify()
        previousTick = nanoTime()
        nextStep = previousTick + intervalNanos()
        animation.start()
    }

    override fun removeNotify() {
        animation.stop(); finishTransition(); pressedX = null; closeChooser?.invoke(); super.removeNotify()
    }

    override fun close() {
        animation.stop(); finishTransition(); closeChooser?.invoke(); hoverRequested = null; hoverEnded =
            null; closeChooser = null
    }

    private fun contentWidth(): Int {
        if (view === content) {
            val natural = geometry()
            val base = natural.sumOf { it.width } + gap() * (natural.size - 1).coerceAtLeast(0)
            return base + if (scrollMode == RibbonScrollMode.CONTINUOUS) gap() else 0
        }
        val width = view?.preferredSize?.width ?: 0
        if (scrollMode != RibbonScrollMode.CONTINUOUS) return width
        val gap = ((view as? JPanel)?.layout as? FlowLayout)?.hgap ?: 0
        return ((width - gap) / 2).coerceAtLeast(0)
    }

    private fun maximumOffset(): Int =
        if (scrollMode == RibbonScrollMode.CONTINUOUS && contentWidth() > extentSize.width)
            contentWidth() else (contentWidth() - extentSize.width).coerceAtLeast(0)

    private fun scrollBy(delta: Double) {
        val maximum = maximumOffset().toDouble()
        offset =
            if (scrollMode == RibbonScrollMode.CONTINUOUS && maximum > 0) ((offset + delta) % maximum + maximum) % maximum
            else (offset + delta).coerceIn(0.0, maximum)
        viewPosition = Point(offset.toInt(), 0)
        pauseUntil = nanoTime() + 2_000_000_000L
    }

    internal fun tick() {
        val now = nanoTime()
        val elapsed = ((now - previousTick) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        previousTick = now
        if (overlayPaused) {
            nextStep = now + intervalNanos(); return
        }
        refreshTransition(now)
        val maximum = maximumOffset()
        offset = offset.coerceIn(0.0, maximum.toDouble())
        val pointerInside = pointerInside(this)
        if (pointerInside == null) {
            nextStep = now + intervalNanos(); return
        }
        val focused = isFocusOwner || KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let {
            isAncestorOf(it)
        } == true
        if (scrollMode.stepped) {
            tickStepped(now, pointerInside, focused); return
        }
        if (maximum > 0 && speed > 0 && pressedX == null && !pointerInside && !focused && now >= pauseUntil) {
            val step = JBUI.scale(speed) * elapsed
            moveOffset(step, maximum, now)
        }
        if (viewPosition.x != offset.toInt()) viewPosition = Point(offset.toInt(), 0)
    }

    private fun refreshTransition(now: Long) {
        if (outgoingRow == null) return
        if (now - transitionStarted >= transitionNanos) finishTransition() else repaint()
    }

    private fun tickStepped(now: Long, pointerInside: Boolean, focused: Boolean) {
        renderEntries()
        if (pointerInside || focused || pressedX != null || chooserActive) nextStep = now + intervalNanos()
        else if (intervalNanos() > 0 && now >= nextStep && now >= pauseUntil) {
            advanceEntry(1); nextStep = now + intervalNanos()
        }
        if (pointerInside && vertical && pressedX == null) hoverRequested?.invoke()
        if (!pointerInside && !chooserActive) hoverEnded?.invoke()
    }

    private fun moveOffset(step: Double, maximum: Int, now: Long) {
        when (scrollMode) {
            RibbonScrollMode.CONTINUOUS -> offset = (offset + step) % maximum
            RibbonScrollMode.BOUNCE -> {
                offset += step * direction
                if (offset >= maximum || offset <= 0) {
                    offset = offset.coerceIn(0.0, maximum.toDouble())
                    direction = if (offset >= maximum) -1 else 1
                    pauseUntil = now + 1_000_000_000L
                }
            }

            RibbonScrollMode.RESTART -> {
                if (offset >= maximum) offset = 0.0 else offset += step
                if (offset >= maximum) {
                    offset = maximum.toDouble()
                    pauseUntil = now + 1_000_000_000L
                }
            }

            RibbonScrollMode.HORIZONTAL_STEP, RibbonScrollMode.VERTICAL_STEP -> Unit
        }
    }
}
