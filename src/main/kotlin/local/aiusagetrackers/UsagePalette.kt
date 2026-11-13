package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import kotlin.math.roundToInt

data class UsagePalette(val neutral: Color?, val stops: List<Color>) {
    fun color(ratio: BigDecimal, above: String = "2", warning: String = "3"): Color {
        validateBreakpoints(above, warning)
        val anchors = listOf(BigDecimal.ZERO, BigDecimal.ONE, above.toBigDecimal(), warning.toBigDecimal())
        val value = ratio.coerceIn(BigDecimal.ZERO, anchors.last())
        val index = anchors.indexOfLast { value >= it }.coerceAtMost(2)
        val fraction = value.subtract(anchors[index]).divide(anchors[index + 1].subtract(anchors[index]), java.math.MathContext.DECIMAL128).toDouble()
        fun channel(a: Int, b: Int) = (a + (b - a) * fraction).roundToInt()
        val a = stops[index]; val b = stops[index + 1]
        return Color(channel(a.red, b.red), channel(a.green, b.green), channel(a.blue, b.blue))
    }

    companion object {
        fun validateBreakpoints(above: String, warning: String) {
            val pattern = Regex("[0-9]{1,9}(\\.[0-9]{1,9})?")
            require(pattern.matches(above) && pattern.matches(warning)) { "Use plain decimal quota multiples with at most nine digits per part" }
            require(BigDecimal.ONE < above.toBigDecimal() && above.toBigDecimal() < warning.toBigDecimal()) {
                "Quota multiples must satisfy 1 < above quota < warning"
            }
        }
        fun parse(spec: String): UsagePalette {
            val parts = spec.split(',')
            require(parts.size == 5 && parts.drop(1).all { it.matches(Regex("#[0-9a-fA-F]{6}")) } &&
                (parts[0].isEmpty() || parts[0].matches(Regex("#[0-9a-fA-F]{6}")))) { "Choose valid palette colors" }
            return UsagePalette(parts[0].takeIf { it.isNotEmpty() }?.let(Color::decode), parts.drop(1).map(Color::decode))
        }
    }
}
