package local.aiusagetrackers

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

enum class ClockFormat(private val label: String) {
    TWENTY_FOUR_HOUR("24-hour (13:00)"), TWELVE_HOUR("12-hour (1:00 PM)");

    override fun toString(): String = label
}

enum class RibbonScrollMode(private val label: String) {
    RESTART("Restart"), BOUNCE("Bounce"), CONTINUOUS("Continuous"),
    HORIZONTAL_STEP("Horizontal step"), VERTICAL_STEP("Vertical step");
    override fun toString(): String = label
    val stepped: Boolean get() = this == HORIZONTAL_STEP || this == VERTICAL_STEP
}

enum class RibbonStepDirection(private val label: String, val increment: Int) {
    UP("Up", 1), DOWN("Down", -1);
    override fun toString(): String = label
}

/** Mutable settings bean; callers receive copies. These options never change accounting. */
data class DisplayPreferences(
    var datePattern: String = "yyyy-MM-dd",
    var clockFormat: ClockFormat = ClockFormat.TWENTY_FOUR_HOUR,
    var widgetFontSize: Int = 0,
    var allTokenPercentUsed: Boolean = false,
    var weeklyPacing: Boolean = false,
    var palette: String = ",#00FF00,#FFFF00,#FFA500,#FF0000",
    var aboveQuotaMultiplier: String = "2",
    var warningQuotaMultiplier: String = "3",
    var ribbonWidth: Int = 280,
    var ribbonSpeed: Int = 30,
    var fixedAuxiliary: String = "",
    var ribbonScrollMode: RibbonScrollMode = RibbonScrollMode.RESTART,
    var ribbonHorizontalStepIntervalSeconds: Int = 3,
    var ribbonVerticalStepIntervalSeconds: Int = 3,
    var ribbonVerticalStepDirection: RibbonStepDirection = RibbonStepDirection.UP,
    var ribbonAutoExpand: Boolean = false,
    var coloringEnabled: Boolean = true,
) {
    fun validate() {
        require(fixedAuxiliary.split(",").all { it.isEmpty() || it in setOf("request", "quota:10080", "quota:300", "reset:10080", "reset:300") }) { "Unknown fixed auxiliary metric" }
        require(ribbonWidth in 80..1200) { "Ribbon width must be 80–1200 logical pixels" }
        require(ribbonSpeed in 0..200) { "Ribbon speed must be 0–200 logical pixels per second" }
        require(ribbonHorizontalStepIntervalSeconds in 0..300 && ribbonVerticalStepIntervalSeconds in 0..300) {
            "Ribbon step intervals must be 0–300 seconds"
        }
        UsagePalette.validateBreakpoints(aboveQuotaMultiplier, warningQuotaMultiplier)
        UsagePalette.parse(palette)
        require(widgetFontSize == 0 || widgetFontSize in 8..24) { "Widget font size must be IDE default (0), or 8–24 points" }
        require(datePattern.isNotBlank() && datePattern.length <= 80) { "Enter a date pattern of 1–80 characters" }
        val sample = DateTimeFormatter.ofPattern(datePattern, Locale.ENGLISH).format(LocalDate.of(2026, 11, 2))
        require(sample.isNotBlank() && sample.length <= 120) { "Date format must produce a readable date" }
    }

    fun normalized(): DisplayPreferences = try {
        validate()
        copy()
    } catch (_: IllegalArgumentException) {
        DisplayPreferences()
    } catch (_: java.time.DateTimeException) {
        DisplayPreferences()
    }
}

class DisplayFormats(preferences: DisplayPreferences = DisplayPreferences()) {
    private val options = preferences.normalized()
    private val date = DateTimeFormatter.ofPattern(options.datePattern, Locale.ENGLISH)

    fun date(value: LocalDate): String = date.format(value)

    fun time(instant: Instant, zone: ZoneId, seconds: Boolean = false): String {
        val pattern = when (options.clockFormat) {
            ClockFormat.TWENTY_FOUR_HOUR -> if (seconds) "HH:mm:ss" else "HH:mm"
            ClockFormat.TWELVE_HOUR -> if (seconds) "h:mm:ss a" else "h:mm a"
        }
        if (!seconds) return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(instant.atZone(zone))
        return DateTimeFormatterBuilder().appendPattern(if (options.clockFormat == ClockFormat.TWELVE_HOUR) "h:mm:ss" else "HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .appendPattern(if (options.clockFormat == ClockFormat.TWELVE_HOUR) " a" else "")
            .toFormatter(Locale.ENGLISH).format(instant.atZone(zone))
    }

    fun dateTime(instant: Instant, zone: ZoneId, offset: Boolean = false): String =
        "${date.format(instant.atZone(zone))} ${time(instant, zone, true)}" +
                if (offset) " ${instant.atZone(zone).offset}" else ""

    fun shortEndpoint(instant: Instant, reference: Instant, zone: ZoneId, offset: Boolean = false): String {
        val local = instant.atZone(zone)
        val prefix = if (local.toLocalDate() != reference.atZone(zone).toLocalDate()) "${date.format(local)} " else ""
        return prefix + time(instant, zone, local.second != 0 || local.nano != 0) +
                if (offset) " ${local.offset}" else ""
    }
}
