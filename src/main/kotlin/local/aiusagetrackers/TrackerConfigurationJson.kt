package local.aiusagetrackers

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

data class PortableAccountBinding(val reference: String, val vendor: UsageVendor, val alias: String)
data class PortableVendorDisplay(
    val labels: Map<UsageVendor, String> = emptyMap(),
    val state: VendorDisplayState = VendorDisplayState()
)

data class TrackerConfiguration(
    val trackers: List<TrackerDefinition>, val display: DisplayPreferences? = null,
    val bindings: List<PortableAccountBinding> = emptyList(), val general: PortableVendorDisplay? = null
) {
    fun remap(mapping: Map<String, String>): TrackerConfiguration = copy(trackers = trackers.map { tracker ->
        tracker.copy(accountId = mapping[tracker.accountId] ?: tracker.accountId)
    })

    fun applyTo(existing: List<TrackerDefinition>, replace: Boolean): List<TrackerDefinition> {
        val result = (if (replace) emptyList() else existing.map { it.copy() }) + trackers.map {
            it.copy(
                id = UUID.randomUUID().toString()
            )
        }
        require(result.size <= 32) { "Import would exceed the limit of 32 trackers" }
        return result
    }

    fun duplicateNames(existing: List<TrackerDefinition>): List<String> = (existing + trackers)
        .groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.sorted()
}

/** Versioned portable DTO: explicitly excludes homes, database metadata and internal identities. */
object TrackerConfigurationJson {
    const val MAX_BYTES = 1_048_576
    private val stringFields = setOf("name", "zoneId", "startTime", "endTime", "anchor", "fixedStart", "fixedEnd")
    private val booleanFields = setOf("includeCurrent", "visible", "showPeriodStart")
    private val trackerFields =
        buildSet {
            addAll(stringFields); addAll(booleanFields); addAll(
            setOf(
                "amount",
                "unit",
                "mode",
                "selection",
                "endAnchor",
                "endOffset"
            )
        )
        }

    fun encode(configuration: TrackerConfiguration): String {
        require(configuration.trackers.size <= 32) { "At most 32 trackers can be exported" }
        val version = 11
        val root = JsonObject().apply {
            addProperty("format", "codex-usage-trackers")
            addProperty("version", version)
            encodeBindings(configuration)
            add("trackers", JsonArray().also { array ->
                configuration.trackers.forEach { t ->
                    array.add(encodeTracker(t, configuration))
                }
            })
            configuration.display?.let {
                it.validate()
                add("display", JsonObject().apply {
                    addProperty("datePattern", it.datePattern); addProperty("clockFormat", key(it.clockFormat))
                    addProperty("widgetFontSize", it.widgetFontSize)
                    addProperty("allTokenPercentUsed", it.allTokenPercentUsed)
                    addProperty("weeklyPacing", it.weeklyPacing)
                    addProperty("palette", it.palette)
                    addProperty("aboveQuotaMultiplier", it.aboveQuotaMultiplier)
                    addProperty("warningQuotaMultiplier", it.warningQuotaMultiplier)
                    addProperty("ribbonWidth", it.ribbonWidth)
                    addProperty("ribbonAutoExpand", it.ribbonAutoExpand)
                    addProperty("coloringEnabled", it.coloringEnabled)
                    addProperty("ribbonSpeed", it.ribbonSpeed)
                    addProperty("ribbonScrollMode", key(it.ribbonScrollMode))
                    addProperty("ribbonHorizontalStepIntervalSeconds", it.ribbonHorizontalStepIntervalSeconds)
                    addProperty("ribbonVerticalStepIntervalSeconds", it.ribbonVerticalStepIntervalSeconds)
                    addProperty("ribbonVerticalStepDirection", key(it.ribbonVerticalStepDirection))
                    addProperty("fixedAuxiliary", it.fixedAuxiliary)
                })
            }
        }
        val text = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n"
        // Validate our exported contract as well as incoming files, including string limits.
        decode(text)
        return text
    }

    private fun JsonObject.encodeBindings(configuration: TrackerConfiguration) {
        add("bindings", JsonArray().apply {
            configuration.bindings.forEachIndexed { index, binding ->
                add(JsonObject().apply {
                    addProperty("reference", "binding_$index"); addProperty(
                    "vendor",
                    key(binding.vendor)
                ); addProperty("alias", binding.alias)
                })
            }
        })
        configuration.general?.let { general ->
            general.state.validate()
            add("general", JsonObject().apply {
                add(
                    "labels",
                    JsonObject().apply {
                        general.labels.forEach { (vendor, label) ->
                            addProperty(
                                key(vendor),
                                label
                            )
                        }
                    })
                val state = general.state
                addProperty("selected", key(state.selected)); addProperty("carousel", state.carousel)
                add("members", JsonArray().apply { state.members.forEach { add(key(it)) } })
                addProperty("motion", key(state.motion)); addProperty("speed", state.speed)
                addProperty("horizontalSeconds", state.horizontalSeconds); addProperty(
                "verticalSeconds",
                state.verticalSeconds
            )
                addProperty("direction", key(state.direction))
            })
        }
    }

    private fun encodeTracker(t: TrackerDefinition, configuration: TrackerConfiguration): JsonObject {
        UsagePeriodResolver().validate(t)
        return JsonObject().apply {
            addProperty("name", t.name); addProperty("amount", t.amount)
            val index = configuration.bindings.indexOfFirst { it.reference == t.accountId }
            require(index >= 0 || t.accountId == ProviderProfile.LEGACY_CODEX) { "Provide a logical account binding before export" }
            addProperty("account", if (index >= 0) "binding_$index" else "legacy-codex")
            addProperty("quotaPool", t.quotaPoolId)
            addProperty("unit", key(t.unit)); addProperty("mode", key(t.mode)); addProperty(
            "selection",
            key(t.selection)
        )
            addProperty("includeCurrent", t.includeCurrent); addProperty("visible", t.visible)
            addProperty("showPeriodStart", t.showPeriodStart); addProperty("zoneId", t.zoneId)
            addProperty("startTime", t.startTime); addProperty("endTime", t.endTime); addProperty(
            "anchor",
            t.anchor
        )
            addProperty("fixedStart", t.fixedStart); addProperty("fixedEnd", t.fixedEnd)
            addProperty("endAnchor", key(t.endAnchor)); addProperty("endOffset", t.endOffset)
            if (t.style.isNotBlank()) addProperty("style", t.style)
            addProperty("showPercentUsed", t.showPercentUsed)
            addProperty("colorByQuotaGoal", t.colorByQuotaGoal)
            addProperty("hideLabel", t.hideLabel)
            addProperty("ribbonPlacement", key(t.ribbonPlacement))
        }
    }

    private fun decodeBindings(root: JsonObject): List<PortableAccountBinding> {
        return root.get("bindings")?.let { value ->
            require(value.isJsonArray && value.asJsonArray.size() <= 32)
            value.asJsonArray.mapIndexed { index, element ->
                val binding = element.objectValue(); binding.only(setOf("reference", "vendor", "alias"))
                require(binding.string("reference") == "binding_$index") { "Invalid logical binding order" }
                PortableAccountBinding("unresolved_$index", binding.enumValue("vendor"), binding.string("alias").also {
                    require(it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl)) { "Invalid account alias" }
                })
            }
        }.orEmpty()
    }

    private fun decodeGeneral(root: JsonObject): PortableVendorDisplay? {
        return root.get("general")?.objectValue()?.let { obj ->
            obj.only(
                setOf(
                    "labels",
                    "selected",
                    "carousel",
                    "members",
                    "motion",
                    "speed",
                    "horizontalSeconds",
                    "verticalSeconds",
                    "direction"
                )
            )
            val labels = obj.get("labels").objectValue().let { labels ->
                labels.only(UsageVendor.entries.map(::key).toSet())
                UsageVendor.entries.filter { labels.has(key(it)) }.associateWith {
                    labels.string(key(it)).also { label -> VendorConfiguration(label = label).validate() }
                }
            }
            val members = obj.get("members")
                .also { require(it.isJsonArray && it.asJsonArray.size() <= UsageVendor.entries.size) }.asJsonArray.map {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    UsageVendor.entries.firstOrNull { v -> key(v) == it.asString } ?: error("Unknown carousel vendor")
                }.toMutableList()
            PortableVendorDisplay(
                labels, VendorDisplayState(
                    selected = obj.enumValue("selected"),
                    carousel = obj.boolean("carousel", false),
                    initialized = true,
                    members = members,
                    motion = obj.enumValue("motion"),
                    speed = obj.integer("speed"),
                    horizontalSeconds = obj.integer("horizontalSeconds"),
                    verticalSeconds = obj.integer("verticalSeconds"),
                    direction = obj.enumValue("direction")
                ).also { it.validate() })
        }
    }

    private fun decodeTrackers(
        array: JsonElement,
        bindings: List<PortableAccountBinding>
    ): List<TrackerDefinition> {
        return array.asJsonArray.mapIndexed { index, value ->
            try {
                val obj = value.objectValue()
                obj.only(
                    trackerFields + setOf(
                        "style",
                        "showPercentUsed",
                        "colorByQuotaGoal",
                        "hideLabel",
                        "ribbonPlacement",
                        "account",
                        "quotaPool"
                    )
                )
                TrackerDefinition(
                    name = obj.string("name"),
                    quotaPoolId = obj.string("quotaPool", ""),
                    accountId = decodeAccount(obj, bindings),
                    amount = obj.integer("amount"),
                    unit = obj.enumValue("unit"),
                    mode = obj.enumValue("mode"),
                    selection = obj.enumValue("selection", DaySelection.ALL),
                    includeCurrent = obj.boolean("includeCurrent", true),
                    visible = obj.boolean("visible", true),
                    showPeriodStart = obj.boolean("showPeriodStart", false),
                    zoneId = obj.string("zoneId", ""),
                    startTime = obj.string("startTime", "00:00"),
                    endTime = obj.string("endTime", ""),
                    anchor = obj.string("anchor", "2026-01-01T00:00"),
                    fixedStart = obj.string("fixedStart", ""),
                    fixedEnd = obj.string("fixedEnd", ""),
                    endAnchor = obj.enumValue("endAnchor", EndAnchor.NOW),
                    endOffset = obj.integer("endOffset", 0),
                    style = obj.string("style", ""),
                    showPercentUsed = obj.boolean("showPercentUsed", false),
                    colorByQuotaGoal = obj.boolean("colorByQuotaGoal", false),
                    hideLabel = obj.boolean("hideLabel", false),
                    ribbonPlacement = obj.enumValue("ribbonPlacement", RibbonPlacement.AUTOMATIC),
                ).also {
                    validateImportedTracker(it)
                }
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "Tracker ${index + 1}: ${failure.message ?: "invalid configuration"}",
                    failure
                )
            }
        }
    }

    private fun validateImportedTracker(tracker: TrackerDefinition) {
        require(tracker.name.length <= 256) { "Tracker name exceeds 256 characters" }
        UsagePeriodResolver().validate(tracker)
    }

    private fun decodeAccount(obj: JsonObject, bindings: List<PortableAccountBinding>): String {
        return obj.string("account").let { reference ->
            if (reference == "legacy-codex") ProviderProfile.LEGACY_CODEX
            else bindings.getOrNull(reference.removePrefix("binding_").toIntOrNull() ?: -1)?.reference
                ?.also { require(reference.startsWith("binding_")) } ?: error("Unknown account binding")
        }
    }

    private fun decodeDisplay(root: JsonObject): DisplayPreferences? {
        return root.get("display")?.objectValue()?.let {
            it.only(displayFields())
            DisplayPreferences(
                it.string("datePattern"),
                it.enumValue("clockFormat"),
                it.integer("widgetFontSize", 0),
                it.boolean("allTokenPercentUsed", false),
                it.boolean("weeklyPacing", false),
                it.string("palette", DisplayPreferences().palette),
                it.string("aboveQuotaMultiplier", "2"),
                it.string("warningQuotaMultiplier", "3"),
                it.integer("ribbonWidth", 280),
                it.integer("ribbonSpeed", 30),
                it.string("fixedAuxiliary", ""),
                it.enumValue("ribbonScrollMode", RibbonScrollMode.RESTART),
                it.integer("ribbonHorizontalStepIntervalSeconds", 3),
                it.integer("ribbonVerticalStepIntervalSeconds", 3),
                it.enumValue("ribbonVerticalStepDirection", RibbonStepDirection.UP),
                it.boolean("ribbonAutoExpand", false),
                it.boolean("coloringEnabled", true)
            ).also { display ->
                display.validate()
            }
        }
    }

    private fun displayFields(): Set<String> = buildSet {
        addAll(setOf("datePattern", "clockFormat"))
        addAll(setOf("widgetFontSize", "allTokenPercentUsed"))
        addAll(setOf("weeklyPacing", "palette"))
        add("ribbonScrollMode")
        add("ribbonAutoExpand")
        add("coloringEnabled")
        addAll(
            setOf(
                "ribbonHorizontalStepIntervalSeconds",
                "ribbonVerticalStepIntervalSeconds",
                "ribbonVerticalStepDirection"
            )
        )
        addAll(setOf("ribbonWidth", "ribbonSpeed", "fixedAuxiliary"))
        addAll(setOf("aboveQuotaMultiplier", "warningQuotaMultiplier"))
    }

    fun decode(text: String): TrackerConfiguration {
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 1 MiB" }
        val root = JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val value = readValue(reader, 0).objectValue()
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after configuration" }
            value
        }
        root.only(setOf("format", "version", "trackers", "display", "bindings", "general"))
        require(root.string("format") == "codex-usage-trackers") { "Not an AI Usage Trackers configuration" }
        val version = root.integer("version")
        require(version == 11) { "Unsupported configuration version" }
        val bindings = decodeBindings(root)
        val general = decodeGeneral(root)
        val array = root.get("trackers")
        require(array != null && array.isJsonArray && array.asJsonArray.size() <= 32) { "Expected an array of at most 32 trackers" }
        val trackers = decodeTrackers(array, bindings)
        val display = decodeDisplay(root)
        return TrackerConfiguration(trackers, display, bindings, general)
    }

    fun read(path: Path): TrackerConfiguration = Files.newInputStream(path).use {
        val bytes = it.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "Configuration exceeds 1 MiB" }
        decode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
    }

    fun write(path: Path, configuration: TrackerConfiguration) {
        val text = encode(configuration)
        val target = path.toAbsolutePath()
        val temporary = Files.createTempFile(target.parent, ".codex-trackers-", ".tmp")
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readValue(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "Configuration nesting is too deep" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(name.length <= 128 && !has(name)) { "Invalid or duplicate field: ${name.take(128)}" }
                    add(name, readValue(reader, depth + 1))
                }
                reader.endObject()
            }

            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) {
                    require(size() < 32) { "At most 32 entries are supported" }
                    add(readValue(reader, depth + 1))
                }
                reader.endArray()
            }

            JsonToken.STRING -> JsonPrimitive(
                reader.nextString().also { require(it.length <= 4096) { "Configuration string is too long" } })

            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(number.matches(Regex("-?(0|[1-9][0-9]*)"))) { "Expected an integer" }
                JsonPrimitive(number.toIntOrNull() ?: throw IllegalArgumentException("Integer is out of range"))
            }

            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            else -> throw IllegalArgumentException("Expected an object, array, string, integer or boolean")
        }
    }

    private fun JsonElement.objectValue(): JsonObject {
        require(isJsonObject) { "Expected a JSON object" }
        return asJsonObject
    }

    private fun JsonObject.only(allowed: Set<String>) {
        require(keySet().all { it in allowed }) { "Unknown configuration field" }
    }

    private fun JsonObject.string(name: String, default: String? = null): String {
        val value = get(name) ?: return default ?: throw IllegalArgumentException("Missing $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$name must be a string" }
        return value.asString
    }

    private fun JsonObject.integer(name: String, default: Int? = null): Int {
        val value = get(name) ?: return default ?: throw IllegalArgumentException("Missing $name")
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$name must be an integer" }
        return value.asInt
    }

    private fun JsonObject.boolean(name: String, default: Boolean): Boolean {
        val value = get(name) ?: return default
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$name must be a boolean" }
        return value.asBoolean
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(name: String, default: T? = null): T {
        if (!has(name)) return default ?: throw IllegalArgumentException("Missing $name")
        val value = string(name)
        return enumValues<T>().firstOrNull { key(it) == value }
            ?: throw IllegalArgumentException("Unknown $name: $value")
    }

    private fun key(value: Enum<*>): String = value.name.lowercase(Locale.ROOT)
}
