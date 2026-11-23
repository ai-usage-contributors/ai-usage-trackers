package local.aiusagetrackers

import kotlinx.coroutines.CancellationException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

class CodexUsageCollector(private val database: UsageDatabase) {
    // The application service serializes scans. Removed sources never delete imported history.
    fun collect(
        discovery: Discovery,
        now: Instant,
        completionTime: () -> Instant = { now },
        checkCancelled: () -> Unit = {}
    ) {
        val issues = discovery.issues.toMutableSet()
        val integrityIssues = mutableSetOf<String>()
        val files = mutableSetOf<Path>()
        for (home in discovery.homes) for (name in listOf("sessions", "archived_sessions")) {
            val directory = home.resolve(name)
            if (Files.notExists(directory)) continue
            discoverFiles(directory, files, issues, checkCancelled)
        }
        files.forEach { importChangedFile(it, integrityIssues, checkCancelled) }
        checkCancelled()
        database.recordScan(discovery.homes.size, files.size, issues, completionTime(), integrityIssues)
    }

    private fun importChangedFile(
        file: Path,
        issues: MutableSet<String>,
        checkCancelled: () -> Unit,
    ) {
        checkCancelled()
        try {
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!database.unchanged(file, attrs)) database.importFile(file, attrs, checkCancelled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issues += "A log import failed or changed during reading; previous history retained; retry pending"
        }
    }

    private fun discoverFiles(
        directory: Path,
        files: MutableSet<Path>,
        issues: MutableSet<String>,
        checkCancelled: () -> Unit
    ) {
        try {
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancelled()
                    if (file.fileName.toString()
                            .endsWith(".jsonl") && Files.isRegularFile(file)
                    ) files.add(file.toRealPath())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                    issues += "A source directory could not be fully read; imported history retained"
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            issues += "Source discovery incomplete; imported history retained"
        }
    }
}
