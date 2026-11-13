package local.aiusagetrackers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerConfigurationJsonTest {
    @get:Rule
    val temp = TemporaryFolder()
    private fun encoded() = TrackerConfigurationJson.encode(TrackerConfiguration(TrackerDefinition.defaults()))

    @Test
    fun roundTripAllPeriodOptionsOrderAndDisplayWithoutInternalIds() {
        val definitions = listOf(
            TrackerDefinition(
                name = "Hóurly \"custom\"",
                unit = SpanUnit.HOUR,
                amount = 2,
                startTime = "00:15:30",
                showPeriodStart = true
            ),
            TrackerDefinition(
                name = "Workdays", amount = 3, selection = DaySelection.WORKING, zoneId = "Europe/Budapest",
                startTime = "09:30", endTime = "17:45", includeCurrent = false, visible = false
            ),
            TrackerDefinition(name = "Active", selection = DaySelection.ACTIVE),
            TrackerDefinition(name = "Elapsed", mode = PeriodMode.ELAPSED, endAnchor = EndAnchor.WEEK, endOffset = 2),
            TrackerDefinition(
                name = "Anchored",
                mode = PeriodMode.REPEATING,
                unit = SpanUnit.MONTH,
                anchor = "2026-01-31T09:15"
            ),
            TrackerDefinition(
                name = "Fixed",
                mode = PeriodMode.FIXED,
                fixedStart = "2026-01-01T00:00",
                fixedEnd = "2026-02-01T12:00"
            ),
        )
        val preferences = DisplayPreferences("dd/MM/yyyy", ClockFormat.TWELVE_HOUR)
        val json = TrackerConfigurationJson.encode(TrackerConfiguration(definitions, preferences))
        definitions.forEach { assertFalse(it.id in json) }
        assertFalse("extraHomes" in json)
        assertFalse("database" in json)
        val restored = TrackerConfigurationJson.decode(json)
        assertEquals(preferences, restored.display)
        assertEquals(
            definitions,
            restored.trackers.mapIndexed { index, definition -> definition.copy(id = definitions[index].id) })
        assertEquals(restored.trackers.size, restored.trackers.map { it.id }.distinct().size)
        assertTrue(json.contains("\n"))
    }

    @Test
    fun minimalContractSuppliesDefaultsAndAcceptsEmptyLists() {
        val minimal =
            """{"format":"codex-usage-trackers","version":11,"trackers":[{"name":"Hourly","account":"legacy-codex","unit":"hour","amount":1,"mode":"calendar"}]}"""
        val result = TrackerConfigurationJson.decode(minimal)
        assertEquals(SpanUnit.HOUR, result.trackers.single().unit)
        assertEquals("00:00", result.trackers.single().startTime)
        assertFalse(result.trackers.single().showPeriodStart)
        assertEquals(null, result.display)
        assertTrue(TrackerConfigurationJson.decode(TrackerConfigurationJson.encode(TrackerConfiguration(emptyList()))).trackers.isEmpty())
    }

    @Test
    fun mergeAndReplaceCopyDraftsRegenerateIdsAndPreserveFileOrder() {
        val original = TrackerDefinition.defaults()
        val imported = TrackerConfiguration(listOf(original[1].copy(), original[0].copy()))
        val merged = imported.applyTo(original, false)
        assertEquals(listOf("Today", "This week", "This week", "Today"), merged.map { it.name })
        assertEquals(4, merged.map { it.id }.distinct().size)
        merged[0].name = "Edited draft"
        assertEquals("Today", original[0].name)
        val replaced = imported.applyTo(original, true)
        assertEquals(listOf("This week", "Today"), replaced.map { it.name })
        assertTrue(replaced.none { entry -> original.any { it.id == entry.id } })
        assertEquals(listOf("This week", "Today"), imported.duplicateNames(original))
        assertFailsWith<IllegalArgumentException> { imported.applyTo(List(32) { TrackerDefinition() }, false) }
        assertEquals(2, imported.applyTo(List(32) { TrackerDefinition() }, true).size)
    }

    @Test
    fun invalidFieldsTypesEnumsRangesAndDatesRejectTheWholeFile() {
        fun reject(change: (JsonObject) -> Unit) {
            val root = JsonParser.parseString(encoded()).asJsonObject
            change(root)
            assertFailsWith<RuntimeException> { TrackerConfigurationJson.decode(root.toString()) }
        }
        (1..10).forEach { version -> reject { it.addProperty("version", version) } }
        reject { it.addProperty("version", 12) }
        reject { it.addProperty("version", "1") }
        reject { it.addProperty("format", "other") }
        reject { it.addProperty("extraHomes", "private path") }
        reject {
            it.add(
                "display",
                JsonObject().apply {
                    addProperty("datePattern", "yyyy-MM-dd HH"); addProperty(
                    "clockFormat",
                    "twenty_four_hour"
                )
                })
        }
        val badTrackers: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("amount", 0) },
            { it.addProperty("amount", 1001) },
            { it.addProperty("amount", "24") },
            { it.addProperty("amount", 1.5) },
            { it.addProperty("visible", "true") },
            { it.addProperty("unit", "century") },
            { it.addProperty("zoneId", "Missing/Zone") },
            { it.addProperty("startTime", "25:00") },
            { it.addProperty("id", "collision") },
            { it.addProperty("name", "") },
            { it.addProperty("name", "a".repeat(257)) },
            {
                it.addProperty("mode", "fixed"); it.addProperty(
                "fixedStart",
                "2026-02-02T12:00"
            ); it.addProperty("fixedEnd", "2026-01-01T12:00")
            },
            { it.addProperty("mode", "elapsed"); it.addProperty("unit", "month") },
        )
        badTrackers.forEach { change -> reject { change(it.getAsJsonArray("trackers")[1].asJsonObject) } }
    }

    @Test
    fun malformedDuplicateDeepOversizedAndTrailingJsonAreRejected() {
        val text = encoded()
        listOf(
            "null", "[]", "$text{}", "/* comment */$text",
            text.replace("\"version\": 11", "\"version\": 11, \"version\": 11"),
            text.replace("\"amount\": 1", "\"amount\": null"),
            "[".repeat(14) + "0" + "]".repeat(14), " ".repeat(TrackerConfigurationJson.MAX_BYTES + 1)
        )
            .forEach { assertFailsWith<Exception> { TrackerConfigurationJson.decode(it) } }
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.encode(TrackerConfiguration(List(33) { TrackerDefinition() }))
        }
    }

    @Test
    fun localFileRoundTripReplacesAtomicallyAndInvalidExportPreservesTarget() {
        val directory = temp.newFolder().toPath()
        val path = directory.resolve("trackers.json")
        Files.writeString(path, "previous file")
        TrackerConfigurationJson.write(path, TrackerConfiguration(TrackerDefinition.defaults()))
        assertEquals(listOf("Today", "This week"), TrackerConfigurationJson.read(path).trackers.map { it.name })
        val preserved = Files.readString(path)
        assertFailsWith<IllegalArgumentException> {
            TrackerConfigurationJson.write(path, TrackerConfiguration(listOf(TrackerDefinition(amount = 0))))
        }
        assertEquals(preserved, Files.readString(path))
        Files.list(directory).use { assertEquals(listOf(path), it.toList()) }
    }

    @Test
    fun failedReplacementCleansTemporaryFileAndLeavesExistingDirectoryAlone() {
        val directory = temp.newFolder().toPath()
        val target = Files.createDirectory(directory.resolve("cannot-replace.json"))
        val retained = target.resolve("retained.txt")
        Files.writeString(retained, "preserved")
        assertFailsWith<Exception> {
            TrackerConfigurationJson.write(
                target,
                TrackerConfiguration(TrackerDefinition.defaults())
            )
        }
        assertEquals("preserved", Files.readString(retained))
        Files.list(directory).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test
    fun readingIsBoundedAndRejectsInvalidUtf8() {
        val path = temp.newFile().toPath()
        Files.write(path, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<Exception> { TrackerConfigurationJson.read(path) }
        Files.write(path, ByteArray(TrackerConfigurationJson.MAX_BYTES + 1))
        assertFailsWith<IllegalArgumentException> { TrackerConfigurationJson.read(path) }
    }
}
