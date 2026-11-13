package local.aiusagetrackers

import com.google.gson.JsonParser
import com.intellij.openapi.components.State
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenameCompatibilityTest {
    @Test fun previousPackageSettingsLoadAndSerializeWithoutChangingTheXmlContract() {
        // Captured from the 0.18.2 packaged classes using entirely synthetic values.
        val xml = JDOMUtil.load(requireNotNull(javaClass.getResourceAsStream("/legacy-settings-0.18.2.xml")))
        val state = XmlSerializer.deserialize(xml, UsageSettings.SettingsState::class.java)
        val rewritten = XmlSerializer.serialize(state).apply {
            name = "component"
            setAttribute("name", "CodexDailyTokens")
        }
        assertEquals(JDOMUtil.writeElement(xml), JDOMUtil.writeElement(rewritten))
        val settings = UsageSettings().apply { loadState(state) }
        assertEquals("before OtherWidget", settings.state.widgetAnchor)
        assertTrue(settings.state.previewPinned)
        assertEquals(12, settings.trackerLimit)
        assertTrue(settings.showLastRequest)
        assertEquals(listOf("My daily total", "This week"), settings.trackers.map { it.name })
        assertEquals(listOf("today", "week"), settings.trackers.map { it.id })
        assertTrue(settings.trackers.all { it.accountId == ProviderProfile.LEGACY_CODEX })
        assertEquals(UsageVendor.entries.toSet(), settings.providers.profiles.map { it.vendor }.toSet())
        assertEquals(ProviderProfile.LEGACY_CODEX, settings.providers.collecting().single().id)
        val isolated = settings.state
        isolated.trackers.first().name = "Changed copy"
        assertEquals("My daily total", settings.trackers.first().name)
    }

    @Test fun renamedImplementationRetainsInstallationAndWidgetIdentities() {
        val descriptor = JDOMUtil.load(Path.of("src/main/resources/META-INF/plugin.xml"))
        assertEquals("local.codexusage.daily", descriptor.getChildText("id"))
        assertEquals("AI Usage Trackers", descriptor.getChildText("name"))
        val extensions = requireNotNull(descriptor.getChild("extensions"))
        val factoryElement = requireNotNull(extensions.getChild("statusBarWidgetFactory"))
        val factory = Class.forName(factoryElement.getAttributeValue("implementation")).getConstructor().newInstance() as UsageWidgetFactory
        assertEquals("CodexDailyTokens", factory.id)
        assertEquals(factory.id, factoryElement.getAttributeValue("id"))
        assertEquals("AI Usage Trackers", factory.displayName)
        val configurable = requireNotNull(extensions.getChild("applicationConfigurable"))
        assertEquals("local.codexusage.daily.settings", configurable.getAttributeValue("id"))
        val settingsPage = Class.forName(configurable.getAttributeValue("instance")).getConstructor().newInstance() as UsageConfigurable
        assertEquals(configurable.getAttributeValue("displayName"), settingsPage.displayName)
        val state = requireNotNull(UsageSettings::class.java.getAnnotation(State::class.java))
        assertEquals("CodexDailyTokens", state.name)
        assertEquals("codexDailyTokens.xml", state.storages.single().value)
    }

    @Test fun portableContractUsesCurrentFormat() {
        val old = """{"format":"codex-usage-trackers","version":11,"trackers":[{"name":"Legacy","account":"legacy-codex","unit":"day","amount":1,"mode":"calendar"}]}"""
        val imported = TrackerConfigurationJson.decode(old)
        val exported = TrackerConfigurationJson.encode(imported)
        val root = JsonParser.parseString(exported).asJsonObject
        assertEquals("codex-usage-trackers", root.get("format").asString)
        assertEquals(11, root.get("version").asInt)
        assertEquals("Legacy", TrackerConfigurationJson.decode(exported).trackers.single().name)
        assertFalse(exported.contains("local.aiusagetrackers"))
        val failure = assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.decode(old.replace("codex-usage-trackers", "other"))
        }
        assertEquals("Not an AI Usage Trackers configuration", failure.message)
    }
}
