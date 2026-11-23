package local.aiusagetrackers

import kotlinx.coroutines.CancellationException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

class ProviderUsageCollector(private val database: UsageDatabase) {
    fun collect(profile: ProviderProfile, now: Instant, checkCancelled: () -> Unit = {}, completionTime: () -> Instant = { now }) {
        database.bindAccount(profile)
        val issues = mutableSetOf<String>()
        val files = linkedSetOf<Path>()
        val roots = profile.paths() + listOfNotNull(profile.captureDirectory.takeIf { it.isNotBlank() }?.let(Path::of))
        for (root in roots) {
            checkCancelled()
            if (!Files.exists(root)) {
                issues += "A configured source location is missing"; continue
            }
            if (Files.isRegularFile(root)) {
                files.add(root.toRealPath()); continue
            }
            walkSource(root, files, issues, checkCancelled)
        }
        files.forEach { importFile(it, profile, issues, checkCancelled) }
        database.recordScan(roots.size, files.size, issues, completionTime())
    }

    private fun walkSource(
        root: Path,
        files: MutableSet<Path>,
        issues: MutableSet<String>,
        checkCancelled: () -> Unit,
    ) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled(); return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (file.fileName.toString().endsWith(".jsonl") && attrs.isRegularFile) files.add(file.toRealPath())
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                issues += "Source discovery incomplete"; return FileVisitResult.CONTINUE
            }
        })
    }

    private fun importFile(
        file: Path,
        profile: ProviderProfile,
        issues: MutableSet<String>,
        checkCancelled: () -> Unit
    ) {
        try {
            checkCancelled()
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!database.providerUnchanged(file, attrs)) database.importProvider(file, attrs, profile, checkCancelled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issues += "A vendor report could not be imported; previous history retained; check its supported format and retry"
        }
    }
}
