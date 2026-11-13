package local.aiusagetrackers

import com.google.gson.JsonParser
import kotlin.test.*

class RibbonStepSettingsTest {
    private fun json(display: DisplayPreferences) = TrackerConfigurationJson.encode(TrackerConfiguration(TrackerDefinition.defaults(), display))
    private val newFields = listOf("ribbonHorizontalStepIntervalSeconds", "ribbonVerticalStepIntervalSeconds", "ribbonVerticalStepDirection")

    @Test fun modesIntervalsAndDirectionsRoundTripLocallyAndPortably() {
        for (mode in RibbonScrollMode.entries) for (direction in RibbonStepDirection.entries) {
            val display = DisplayPreferences(ribbonScrollMode = mode, ribbonHorizontalStepIntervalSeconds = 0,
                ribbonVerticalStepIntervalSeconds = 300, ribbonVerticalStepDirection = direction)
            val encoded = json(display)
            assertEquals(11, JsonParser.parseString(encoded).asJsonObject["version"].asInt)
            assertEquals(display, TrackerConfigurationJson.decode(encoded).display)
            val settings = UsageSettings().apply { update(emptyList(), TrackerDefinition.defaults(), display) }
            assertEquals(display, UsageSettings().apply { loadState(settings.state) }.display)
            settings.display.ribbonVerticalStepIntervalSeconds = 5
            assertEquals(300, settings.display.ribbonVerticalStepIntervalSeconds)
        }
    }

    @Test fun invalidOrMistypedStepPreferencesRejectTheEntireImport() {
        for (field in newFields.take(2)) for (invalid in listOf("-1", "301", "1.5", "\"3\"", "null")) {
            val root = JsonParser.parseString(json(DisplayPreferences())).asJsonObject
            root.getAsJsonObject("display").add(field, JsonParser.parseString(invalid))
            assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.decode(root.toString()) }
        }
        assertFailsWith<IllegalArgumentException> { DisplayPreferences(ribbonHorizontalStepIntervalSeconds = -1).validate() }
        assertFailsWith<IllegalArgumentException> { DisplayPreferences(ribbonVerticalStepIntervalSeconds = 301).validate() }
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.decode(json(DisplayPreferences()).replace("\"up\"", "\"sideways\""))
        }
    }
}
