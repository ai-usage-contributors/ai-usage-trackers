package local.aiusagetrackers

/** Tracks resize decisions independently of popup dismissal. */
internal class RibbonResizeState {
    var pending: String? = null
        private set
    private var announced: String? = null
    fun observe(message: String?): Boolean {
        if (message == null) { pending = null; announced = null; return false }
        if (message == announced) return false
        announced = message
        pending = message
        return true
    }
    fun acknowledge() { pending = null }
}

class RibbonWidthStatistics {
    var count: Long = 0
    var average: Double = 0.0
    var maximum: Int = 0
    var maximumValue: String = ""
    fun record(width: Int, value: String) {
        if (width <= 0 || count == Long.MAX_VALUE) return
        count++
        average += (width - average) / count
        if (width > maximum) { maximum = width; maximumValue = value }
    }
    fun copy() = RibbonWidthStatistics().also {
        it.count = count; it.average = average; it.maximum = maximum; it.maximumValue = maximumValue
    }
}
