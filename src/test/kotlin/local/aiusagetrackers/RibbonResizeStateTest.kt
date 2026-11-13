package local.aiusagetrackers

import kotlin.test.*

class RibbonResizeStateTest {
    @Test fun dismissalKeepsWarningAndAcknowledgementClearsOnlyCurrentResize() {
        val state = RibbonResizeState()
        assertTrue(state.observe("80 to 140"))
        assertFalse(state.observe("80 to 140"))
        assertEquals("80 to 140", state.pending)
        state.acknowledge()
        assertFalse(state.observe("80 to 140"))
        assertNull(state.pending)
        assertTrue(state.observe("80 to 160"))
        state.observe(null)
        assertNull(state.pending)
        assertTrue(state.observe("80 to 160"))
    }

    @Test fun statisticsAccumulateAndSurviveSettingsUpdatesAndRestart() {
        val settings = UsageSettings()
        settings.recordRibbonWidth(100, "first")
        settings.recordRibbonWidth(200, "full longest value")
        settings.recordRibbonWidth(150, "third")
        settings.update(settings.extraHomes, settings.trackers)
        val loaded = UsageSettings().apply { loadState(settings.state) }
        assertEquals(3L, loaded.ribbonWidths.count)
        assertEquals(150.0, loaded.ribbonWidths.average)
        assertEquals(200, loaded.ribbonWidths.maximum)
        assertEquals("full longest value", loaded.ribbonWidths.maximumValue)
    }
}
