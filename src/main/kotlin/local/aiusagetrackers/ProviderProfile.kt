package local.aiusagetrackers

import java.nio.file.Path
import java.util.UUID

enum class UsageVendor(private val label: String) {
    CODEX("Codex"), CLAUDE("Claude"), ANTIGRAVITY("Antigravity");
    override fun toString(): String = label
}
data class AccountChoice(val id: String, val label: String) { override fun toString() = label }

/** Local account mapping, never an authentication credential or inferred billing identity. */
data class ProviderProfile(
    var id: String = UUID.randomUUID().toString(),
    var vendor: UsageVendor = UsageVendor.CODEX,
    var alias: String = "Default account",
    var locations: String = "",
    var captureDirectory: String = "",
    var verified: Boolean = false,
    var analytics: AnalyticsDisplayOptions = AnalyticsDisplayOptions(planProfile = QuotaPlanProfile.UNKNOWN),
) {
    fun isolatedCopy() = copy(analytics = analytics.copy())
    fun configured(): Boolean = verified && (vendor == UsageVendor.CODEX || locations.isNotBlank() || captureDirectory.isNotBlank())
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid local account reference" }
        require(alias.isNotBlank() && alias.length <= 80 && alias.none(Char::isISOControl)) { "Enter an account alias of 1–80 printable characters" }
        paths().forEach { require(it.isAbsolute) { "Source locations must be absolute paths" } }
        if (captureDirectory.isNotBlank()) require(Path.of(captureDirectory).isAbsolute) { "Capture directory must be absolute" }
        analytics.validate()
        require(analytics.planProfile in QuotaPlanProfile.forVendor(vendor)) { "The quota scheme belongs to another vendor" }
    }
    fun paths(): List<Path> = locations.lineSequence().map(String::trim).filter(String::isNotEmpty).map(Path::of).distinct().toList()
    companion object {
        const val LEGACY_CODEX = "legacy-codex"
        fun defaults() = UsageVendor.entries.map { vendor ->
            ProviderProfile(id = if (vendor == UsageVendor.CODEX) LEGACY_CODEX else vendor.name.lowercase(), vendor = vendor,
                alias = "$vendor account", verified = vendor == UsageVendor.CODEX,
                analytics = AnalyticsDisplayOptions(planProfile = if (vendor == UsageVendor.CODEX) QuotaPlanProfile.CODEX_WEEKLY else QuotaPlanProfile.UNKNOWN))
        }
    }
}

data class VendorConfiguration(
    var vendor: UsageVendor = UsageVendor.CODEX,
    var tracking: Boolean = false,
    var label: String = "",
    var accountId: String = "",
) {
    fun displayLabel(): String = label.trim().ifEmpty { vendor.toString() }
    fun validate() { require(label.length <= 80 && label.none(Char::isISOControl)) { "Plan labels must be at most 80 printable characters" } }
}

/** Separate collection, single selection and carousel membership. No UI or filesystem IO. */
data class VendorDisplayState(
    var selected: UsageVendor = UsageVendor.CODEX,
    var carousel: Boolean = false,
    var initialized: Boolean = false,
    var members: MutableList<UsageVendor> = mutableListOf(),
    var motion: RibbonScrollMode = RibbonScrollMode.VERTICAL_STEP,
    var speed: Int = 30,
    var horizontalSeconds: Int = 8,
    var verticalSeconds: Int = 8,
    var direction: RibbonStepDirection = RibbonStepDirection.UP,
) {
    fun isolatedCopy() = copy(members = members.toMutableList())
    fun validate() {
        require(speed in 0..200 && horizontalSeconds in 0..300 && verticalSeconds in 0..300) { "Invalid carousel speed or interval" }
        require(members.distinct().size == members.size) { "Duplicate carousel member" }
    }
    fun visible(eligible: List<UsageVendor>): List<UsageVendor> = if (carousel) eligible.filter { it in members } else
        listOfNotNull(selected.takeIf { it in eligible } ?: eligible.firstOrNull())
    fun toggle(vendor: UsageVendor, eligible: List<UsageVendor>): VendorDisplayState {
        require(vendor in eligible)
        if (vendor in members) require(visible(eligible).size > 1) { "Keep one vendor selected or turn the carousel off" }
        return isolatedCopy().also { if (!it.members.remove(vendor)) it.members.add(vendor) }
    }
    fun enable(value: Boolean, eligible: List<UsageVendor>) = isolatedCopy().also {
        it.carousel = value
        if (value && !it.initialized) { it.members = eligible.toMutableList(); it.initialized = true }
    }
}

data class ProviderConfiguration(
    var profiles: MutableList<ProviderProfile> = ProviderProfile.defaults().toMutableList(),
    var vendors: MutableList<VendorConfiguration> = UsageVendor.entries.map { vendor ->
        VendorConfiguration(vendor, vendor == UsageVendor.CODEX, accountId = if (vendor == UsageVendor.CODEX) ProviderProfile.LEGACY_CODEX else vendor.name.lowercase())
    }.toMutableList(),
    var display: VendorDisplayState = VendorDisplayState(),
) {
    fun isolatedCopy() = copy(profiles = profiles.map { it.isolatedCopy() }.toMutableList(), vendors = vendors.map { it.copy() }.toMutableList(), display = display.isolatedCopy())
    fun account(vendor: UsageVendor): ProviderProfile? = profiles.firstOrNull { it.vendor == vendor && it.id == vendors.firstOrNull { v -> v.vendor == vendor }?.accountId }
    fun collecting(): List<ProviderProfile> = profiles.filter { p -> p.configured() && vendors.any { it.vendor == p.vendor && it.tracking } }
    fun eligible(): List<UsageVendor> = UsageVendor.entries.filter { v -> vendors.any { it.vendor == v && it.tracking } && account(v)?.configured() == true }
    fun label(vendor: UsageVendor): String = vendors.firstOrNull { it.vendor == vendor }?.displayLabel() ?: vendor.toString()
    fun menuLabel(vendor: UsageVendor): String = label(vendor).let { label -> if (vendors.count { it.displayLabel() == label } > 1) "$label ($vendor)" else label }
    fun validate() {
        require(profiles.size <= 32 && profiles.map { it.id }.distinct().size == profiles.size) { "At most 32 distinct accounts are supported" }
        require(vendors.map { it.vendor }.toSet() == UsageVendor.entries.toSet() && vendors.size == UsageVendor.entries.size) { "Expected one configuration per vendor" }
        profiles.forEach { it.validate() }; vendors.forEach { it.validate() }; display.validate()
        vendors.forEach { v -> require(profiles.any { it.id == v.accountId && it.vendor == v.vendor }) { "Select an account for $v" } }
    }
    fun reconciled() = isolatedCopy().also { result ->
        val eligible = result.eligible()
        if (result.display.selected !in eligible && eligible.isNotEmpty()) result.display.selected = eligible.first()
    }
}
