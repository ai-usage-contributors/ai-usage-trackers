package local.aiusagetrackers

import java.awt.Color
import java.math.BigDecimal
import kotlin.math.pow

enum class StatusValueState { AVAILABLE, STALE, UNAVAILABLE, LOADING, EXPIRED }

data class StatusValue(
    val id: String, val label: String, val text: String, val raw: BigDecimal? = null,
    val state: StatusValueState = StatusValueState.AVAILABLE, val style: String = "",
    val description: String = "",
    val quotaGoalRatio: BigDecimal? = null,
    val quotaColorEnabled: Boolean = false,
    val warnings: List<String> = emptyList(),
    val palette: String? = null,
    val aboveQuotaMultiplier: String = "2",
    val warningQuotaMultiplier: String = "3",
) {
    val accessibleText: String get() = "$label: $text" + if (state == StatusValueState.STALE) " (stale)" else ""
    fun displayText(): String =
        if (label.isEmpty() || StatusValueStyle.forDisplay(style).hideLabel) text else "$label: $text"
}

/** Compact, portable settings value. Empty means inherit the current IDE theme. */
data class StatusValueStyle(val base: Color?, val hideLabel: Boolean, val thresholds: List<Pair<BigDecimal, Color>>) {
    fun color(value: StatusValue, theme: Color): Color {
        value.palette?.let { spec ->
            val palette = UsagePalette.parse(spec)
            return if (value.state in setOf(StatusValueState.AVAILABLE, StatusValueState.STALE) && value.quotaColorEnabled)
                value.quotaGoalRatio?.let { palette.color(it, value.aboveQuotaMultiplier, value.warningQuotaMultiplier) } ?: palette.neutral ?: theme
            else palette.neutral ?: theme
        }
        if (value.state !in setOf(StatusValueState.AVAILABLE, StatusValueState.STALE)) return theme
        if (value.quotaColorEnabled) return value.quotaGoalRatio?.let(QuotaMetricPresentation::color) ?: theme
        return value.raw?.let { raw -> thresholds.lastOrNull { raw >= it.first }?.second } ?: base ?: theme
    }

    companion object {
        fun forDisplay(spec: String): StatusValueStyle = try {
            parse(spec)
        } catch (_: IllegalArgumentException) {
            parse("")
        }

        fun parse(spec: String): StatusValueStyle {
            require(spec.length <= 2048) { "Color settings exceed 2048 characters" }
            if (spec.isBlank()) return StatusValueStyle(null, false, emptyList())
            val parts = spec.split('|')
            require(parts.size == 3 && parts[1] in setOf("true", "false")) { "Invalid color settings" }
            fun color(text: String): Color {
                require(text.matches(Regex("#[0-9a-fA-F]{6}"))) { "Use a color such as #FF8800" }
                return Color.decode(text)
            }

            val base = parts[0].takeIf { it.isNotEmpty() }?.let(::color)
            val rules = if (parts[2].isBlank()) emptyList() else parts[2].split(',').map { entry ->
                val pair = entry.trim().split('=')
                require(pair.size == 2) { "Thresholds use amount=#RRGGBB, separated by commas" }
                val minimum = pair[0].trim().toBigDecimalOrNull()
                require(minimum != null && minimum >= BigDecimal.ZERO && minimum.precision() <= 24 && minimum.scale() in 0..9) { "Use a nonnegative threshold with at most 9 decimal places" }
                minimum to color(pair[1].trim())
            }
            require(
                rules.size <= 16 && rules.zipWithNext()
                    .all { (a, b) -> a.first < b.first }) { "Use at most 16 unique thresholds in ascending order" }
            return StatusValueStyle(base, parts[1].toBoolean(), rules)
        }

        fun contrast(first: Color, second: Color): Double {
            fun luminance(c: Color): Double = listOf(c.red, c.green, c.blue).map { channel ->
                val s = channel / 255.0
                if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }.let { it[0] * 0.2126 + it[1] * 0.7152 + it[2] * 0.0722 }

            val a = luminance(first)
            val b = luminance(second)
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }
    }
}
