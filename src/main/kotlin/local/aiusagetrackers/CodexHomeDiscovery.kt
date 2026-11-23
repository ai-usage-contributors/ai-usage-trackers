package local.aiusagetrackers

import java.nio.file.Files
import java.nio.file.Path
import java.util.*

data class Discovery(val homes: Set<Path>, val issues: Set<String>)

class CodexHomeDiscovery(
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val env: Map<String, String> = System.getenv(),
    private val os: String = System.getProperty("os.name"),
) {
    fun discover(extras: List<String>): Discovery {
        val candidates = mutableSetOf(home.resolve(".codex"))
        val issues = mutableSetOf<String>()
        fun add(text: String) {
            try {
                candidates.add(Path.of(text))
            } catch (_: RuntimeException) {
                issues += "Invalid configured directory"
            }
        }
        env["CODEX_HOME"]?.takeIf { it.isNotBlank() }?.let(::add)
        extras.filter { it.isNotBlank() }.forEach(::add)
        val roots = ideRoots()
        // Unverified Linux IDE cache layouts are deliberately left to extra homes.
        for (root in roots) {
            if (Files.notExists(root)) continue
            try {
                Files.newDirectoryStream(root).use { products ->
                    products.filter { Files.isDirectory(it) }.forEach { candidates.add(it.resolve("aia/codex")) }
                }
            } catch (_: Exception) {
                issues += "An IDE discovery directory could not be read"
            }
        }
        val homes = accessibleHomes(candidates, extras, issues)
        return Discovery(homes, issues)
    }

    private fun ideRoots(): Set<Path> {
        val roots = mutableSetOf<Path>()
        val platform = os.lowercase(Locale.ROOT)
        if (platform.contains("win")) {
            listOf("LOCALAPPDATA", "APPDATA").mapNotNull { env[it] }
                .forEach { roots.add(Path.of(it).resolve("JetBrains")) }
            roots.add(home.resolve("AppData/Local/JetBrains"))
            roots.add(home.resolve("AppData/Roaming/JetBrains"))
        } else if (platform.contains("mac")) {
            roots.add(home.resolve("Library/Caches/JetBrains"))
        }
        return roots
    }

    private fun accessibleHomes(candidates: Set<Path>, extras: List<String>, issues: MutableSet<String>): Set<Path> {
        val homes = mutableSetOf<Path>()
        for (candidate in candidates) {
            if (Files.notExists(candidate)) {
                if (extras.any { runCatching { Path.of(it) == candidate }.getOrDefault(false) }) issues += "A configured directory is missing"
                continue
            }
            try {
                if (!Files.isDirectory(candidate)) {
                    issues += "A source is not an accessible directory"; continue
                }
                homes.add(candidate.toRealPath())
            } catch (_: Exception) {
                issues += "A source directory could not be read"
            }
        }
        return homes
    }
}
