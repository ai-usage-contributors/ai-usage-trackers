package local.aiusagetrackers

import com.intellij.ide.IdeEventQueue
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.KeyboardFocusManager
import java.awt.image.BufferedImage
import javax.swing.*

/** Pure scheduling: snapshot refreshes do not reset dwell or persist animation phase. */
class VendorCarouselMotion(private val nanoTime: () -> Long = System::nanoTime) {
    private var next = 0L
    private var signature: Any? = null
    private var forward = 1
    var current: UsageVendor? = null
        private set

    fun advance(
        members: List<UsageVendor>,
        options: VendorDisplayState,
        paused: Boolean,
        manual: Int = 0
    ): UsageVendor? {
        val stamp = listOf(
            members,
            options.motion,
            options.speed,
            options.horizontalSeconds,
            options.verticalSeconds,
            options.direction
        )
        val seconds = when (options.motion) {
            RibbonScrollMode.HORIZONTAL_STEP -> options.horizontalSeconds
            RibbonScrollMode.VERTICAL_STEP -> options.verticalSeconds
            else -> if (options.speed == 0) 0 else 2
        }
        val now = nanoTime()
        if (signature != stamp || current !in members) {
            signature = stamp; current = current?.takeIf { it in members } ?: members.firstOrNull()
            next = now + seconds * 1_000_000_000L; forward = 1
        }
        if (paused) next = now + seconds * 1_000_000_000L
        if (members.size <= 1 || (manual == 0 && (paused || seconds == 0 || now < next))) return current
        val index = members.indexOf(current)
        var step =
            if (manual != 0) manual else if (options.motion == RibbonScrollMode.VERTICAL_STEP) options.direction.increment else forward
        if (manual == 0 && options.motion == RibbonScrollMode.BOUNCE && index + step !in members.indices) {
            forward = -forward; step = forward
        }
        current = members[Math.floorMod(index + step, members.size)]
        next = now + seconds * 1_000_000_000L
        return current
    }
}

/** A whole-view carousel. Geometry is supplied by vendor panels, with no outer width setting. */
class VendorCarousel(private val nanoTime: () -> Long = System::nanoTime) : JPanel(BorderLayout()), AutoCloseable {
    private val motion = VendorCarouselMotion(nanoTime)
    private val views = linkedMapOf<UsageVendor, JComponent>()
    private var options = VendorDisplayState()
    private var members = emptyList<UsageVendor>()
    private var outgoing: BufferedImage? = null
    private var began = 0L
    private var duration = 160_000_000L
    private var travelSign = 1
    var paused: () -> Boolean = { false }
    var transitionChanged: (Boolean) -> Unit = {}
    private var wasTransitioning = false
    var selected: UsageVendor? = null
        private set
    private val timer = Timer(25) { tick() }
    private val navigationKeys = WidgetNavigationKeys({
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        isShowing && SwingUtilities.getWindowAncestor(this) === manager.focusedWindow &&
            !paused() && MenuSelectionManager.defaultManager().selectedPath.isEmpty() &&
            (getMousePosition(true) != null || manager.focusOwner?.let { SwingUtilities.isDescendingFrom(it, this) } == true)
    }, ::navigate)
    private val navigationDispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
        override fun dispatch(e: java.awt.AWTEvent): Boolean =
            e is java.awt.event.KeyEvent && navigationKeys.dispatchKeyEvent(e)
    }
    private var navigationRegistered = false

    init {
        isOpaque = false; isFocusable = true
        toolTipText = "Alt+Left/Right changes agents while hovering or focusing the widget. Scroll or drag the agent label to change agents; ribbon gestures change its entries."
    }

    fun navigate(step: Int) {
        tick(step)
    }

    fun configure(items: Map<UsageVendor, JComponent>, state: VendorDisplayState) {
        views.clear(); views.putAll(items); members = items.keys.toList(); options = state.isolatedCopy()
        tick()
    }

    private fun tick(manual: Int = 0) {
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val interacting = paused() || (isShowing && getMousePosition(true) != null) || focus?.let {
            SwingUtilities.isDescendingFrom(
                it,
                this
            )
        } == true
        val animating = outgoing != null && nanoTime() - began < duration
        if (!animating) outgoing = null
        val next = motion.advance(members, options, interacting || animating, manual)
        if (next != selected || (componentCount == 0 && next != null)) {
            switchView(next, manual)
        }
        val transitioning = outgoing != null
        val transitionEnded = wasTransitioning && !transitioning
        if (transitioning != wasTransitioning) {
            wasTransitioning = transitioning; transitionChanged(transitioning)
        }
        if (transitioning || transitionEnded) {
            doLayout(); repaint()
        }
    }

    private fun switchView(next: UsageVendor?, manual: Int) {
        val old = components.firstOrNull()
        val restartWrap =
            options.motion == RibbonScrollMode.RESTART && members.indexOf(next) == 0 && members.indexOf(selected) == members.lastIndex
        travelSign = when {
            manual != 0 -> if (manual > 0) 1 else -1
            options.motion == RibbonScrollMode.VERTICAL_STEP -> options.direction.increment
            options.motion == RibbonScrollMode.BOUNCE && members.indexOf(next) < members.indexOf(selected) -> -1
            else -> 1
        }
        outgoing =
            if (options.carousel && !restartWrap && old != null && width > 0 && height > 0 && isShowing) BufferedImage(
                width,
                height,
                BufferedImage.TYPE_INT_ARGB
            ).also { image ->
                val graphics = image.createGraphics(); try {
                old.paint(graphics)
            } finally {
                graphics.dispose()
            }
            } else null
        selected = next; removeAll(); views[next]?.let { add(it, BorderLayout.CENTER) }
        began = nanoTime()
        duration = if (options.motion.stepped) 160_000_000L else (1_000_000_000L * maxOf(1, width) / maxOf(
            1,
            options.speed
        )).coerceAtLeast(160_000_000L)
        getAccessibleContext().accessibleName = "Displayed vendor: ${next?.presentationLabel ?: "none"}"
        revalidate()
    }

    override fun getPreferredSize(): Dimension = Dimension(views.values.maxOfOrNull { it.preferredSize.width } ?: 1,
        views.values.maxOfOrNull { it.preferredSize.height } ?: 1)

    override fun doLayout() {
        val fraction = if (outgoing == null) 1.0 else ((nanoTime() - began).toDouble() / duration).coerceIn(0.0, 1.0)
        val vertical = options.motion == RibbonScrollMode.VERTICAL_STEP
        val sign = travelSign
        // Move the actual panel as well as its pixels, keeping hit targets aligned.
        components.firstOrNull()?.setBounds(
            if (vertical) 0 else (sign * width * (1 - fraction)).toInt(),
            if (vertical) (sign * height * (1 - fraction)).toInt() else 0, width, height
        )
    }

    override fun paintChildren(g: Graphics) {
        val previous = outgoing
        if (previous == null) {
            super.paintChildren(g); return
        }
        val fraction = ((nanoTime() - began).toDouble() / duration).coerceIn(0.0, 1.0)
        val vertical = options.motion == RibbonScrollMode.VERTICAL_STEP
        val distance = if (vertical) height else width
        val offset = (distance * fraction).toInt()
        val sign = travelSign
        g.drawImage(previous, if (vertical) 0 else -sign * offset, if (vertical) -sign * offset else 0, null)
        super.paintChildren(g)
    }

    override fun addNotify() {
        super.addNotify()
        IdeEventQueue.getInstance().addDispatcher(navigationDispatcher, null as com.intellij.openapi.Disposable?)
        navigationRegistered = true
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        removeNavigationDispatcher()
        navigationKeys.reset()
        super.removeNotify()
    }

    override fun close() {
        removeNavigationDispatcher()
        navigationKeys.reset()
        timer.stop(); outgoing = null; transitionChanged(false); transitionChanged = {}; removeAll(); views.clear()
    }

    private fun removeNavigationDispatcher() {
        if (navigationRegistered) {
            IdeEventQueue.getInstance().removeDispatcher(navigationDispatcher)
            navigationRegistered = false
        }
    }
}
