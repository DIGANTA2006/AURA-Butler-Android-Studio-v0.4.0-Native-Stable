package `in`.diganta.aurabutler.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream

/** Reads one user-selected document into a bounded in-memory payload. */
object DocumentInputReader {
    data class PreparedDocument(
        val displayName: String,
        val mimeType: String,
        val bytes: ByteArray
    )

    fun fromUri(
        context: Context,
        uri: Uri,
        ownerPrompt: String = ""
    ): PreparedDocument {
        require(uri.scheme == "content" || uri.scheme == "file") {
            "AURA only accepts files selected through Android's document picker."
        }
        val resolver = context.contentResolver
        val metadata = queryMetadata(context, uri)
        val name = metadata.first.ifBlank { "attached-document" }.take(160)
        val mime = resolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        require(isSupported(name, mime)) {
            "AURA supports PDF, text, Markdown, CSV, JSON, common source-code files, and ZIP software projects."
        }

        if (isZip(name, mime)) {
            return compactProjectZip(
                context = context,
                uri = uri,
                displayName = name,
                compressedSize = metadata.second,
                ownerPrompt = ownerPrompt
            )
        }

        val input = resolver.openInputStream(uri)
            ?: throw IOException("Android could not open the selected document.")
        val output = ByteArrayOutputStream(minOf(MAX_BYTES, 64 * 1024))
        input.use { source ->
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) {
                    throw IOException("The selected document is larger than AURA's 8 MB direct-analysis limit. For large software projects, attach a ZIP so AURA can compact the source locally.")
                }
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "The selected document was empty." }
        return PreparedDocument(name, normalizedMime(name, mime), bytes)
    }

    fun isImage(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("image/") == true

    private fun compactProjectZip(
        context: Context,
        uri: Uri,
        displayName: String,
        compressedSize: Long,
        ownerPrompt: String
    ): PreparedDocument {
        if (compressedSize > MAX_PROJECT_ZIP_BYTES) {
            throw IOException("The ZIP is larger than AURA's 300 MB local project-scan limit.")
        }

        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android could not open the selected ZIP project.")
        val candidates = mutableListOf<RepositoryIntelligence.SourceFile>()
        val manifest = mutableListOf<String>()
        var entries = 0
        var inspectedUncompressed = 0L

        ZipInputStream(BufferedInputStream(source, 64 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ZIP_ENTRIES) {
                    throw IOException("The ZIP contains too many entries for a safe phone-side scan.")
                }

                val normalized = entry.name.replace('\\', '/').trimStart('/')
                val parts = normalized.split('/').filter(String::isNotBlank)
                val unsafe = normalized.isBlank() ||
                    parts.any { it == ".." } ||
                    parts.any { it.lowercase(Locale.ROOT) in SKIP_PARTS }
                if (entry.isDirectory || unsafe || !isProjectTextFile(normalized)) {
                    zip.closeEntry()
                    continue
                }

                val declared = entry.size
                if (declared > MAX_PROJECT_FILE_BYTES) {
                    zip.closeEntry()
                    continue
                }

                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var fileBytes = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    fileBytes += count
                    inspectedUncompressed += count
                    if (inspectedUncompressed > MAX_TOTAL_INSPECTED_BYTES) {
                        throw IOException("The ZIP expands beyond AURA's safe local project-scan limit.")
                    }
                    if (fileBytes > MAX_PROJECT_FILE_BYTES) {
                        break
                    }
                    bytes.write(buffer, 0, count)
                }
                zip.closeEntry()
                if (fileBytes > MAX_PROJECT_FILE_BYTES) continue

                val raw = bytes.toByteArray()
                if (raw.isEmpty() || raw.take(4096).any { it == 0.toByte() }) continue
                val text = String(raw, StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .take(MAX_PROJECT_FILE_CHARS)
                    .trim()
                if (text.isBlank()) continue

                manifest += normalized
                candidates += RepositoryIntelligence.SourceFile(normalized, text)
            }
        }

        if (candidates.isEmpty()) {
            throw IOException("The ZIP contains no supported source/text files for AURA to analyze.")
        }

        val selection = RepositoryIntelligence.select(
            files = candidates,
            ownerPrompt = ownerPrompt,
            maxFiles = MAX_SELECTED_PROJECT_FILES
        )

        val snapshot = buildString {
            appendLine("AURA PROJECT SNAPSHOT v2")
            appendLine("Original archive: $displayName")
            appendLine("Supported files discovered: ${selection.discoveredCount}")
            appendLine()
            appendLine("PROJECT FILE MANIFEST:")
            manifest.sorted().take(700).forEach { appendLine("- $it") }
            appendLine()

            // The server-side project compactor understands FILE blocks. Put the
            // deterministic dependency/symbol map into a synthetic file so it
            // survives context compaction and can guide Qwen across call sites.
            appendLine("===== FILE: __AURA__/PROJECT_MAP.md =====")
            append(selection.projectMap.take(MAX_PROJECT_MAP_CHARS))
            append("\n\n")

            var remaining = MAX_PROJECT_SNAPSHOT_CHARS - length
            selection.selected.forEach { file ->
                if (remaining <= 256) return@forEach
                val header = "===== FILE: ${file.path} =====\n"
                val allowed = (remaining - header.length - 2).coerceAtLeast(0)
                if (allowed <= 0) return@forEach
                append(header)
                append(file.text.take(minOf(allowed, MAX_SELECTED_FILE_CHARS)))
                append("\n\n")
                remaining = MAX_PROJECT_SNAPSHOT_CHARS - length
            }
        }.take(MAX_PROJECT_SNAPSHOT_CHARS)

        val payload = snapshot.toByteArray(StandardCharsets.UTF_8)
        if (payload.isEmpty() || payload.size > MAX_BYTES) {
            throw IOException("AURA could not create a safe compact project snapshot from that ZIP.")
        }
        return PreparedDocument(
            displayName = displayName,
            mimeType = PROJECT_SNAPSHOT_MIME,
            bytes = payload
        )
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            return uri.lastPathSegment.orEmpty() to -1L
        }
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use "" to -1L
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            } ?: ("" to -1L)
        }.getOrDefault("" to -1L)
    }

    private fun normalizedMime(name: String, mime: String): String = when {
        mime == "application/pdf" || name.endsWith(".pdf", true) -> "application/pdf"
        mime.startsWith("text/") -> mime
        name.endsWith(".json", true) -> "application/json"
        else -> "text/plain"
    }

    private fun isZip(name: String, mime: String): Boolean =
        mime in setOf("application/zip", "application/x-zip-compressed") ||
            name.endsWith(".zip", true)

    private fun isSupported(name: String, mime: String): Boolean {
        if (
            mime == "application/pdf" ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime.startsWith("text/") ||
            mime == "application/json"
        ) {
            return true
        }
        return SUPPORTED_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }

    private fun isProjectTextFile(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        val name = lower.substringAfterLast('/')
        if (name in IMPORTANT_PROJECT_NAMES || name.startsWith("readme.") || name.startsWith("dockerfile")) {
            return true
        }
        return PROJECT_SOURCE_EXTENSIONS.any(lower::endsWith)
    }

    private val SUPPORTED_EXTENSIONS = setOf(
        ".pdf", ".txt", ".md", ".markdown", ".csv", ".json", ".xml",
        ".yaml", ".yml", ".log", ".sql", ".kt", ".kts", ".java",
        ".py", ".c", ".h", ".cpp", ".hpp", ".js", ".ts", ".html",
        ".css", ".sh", ".ps1", ".zip", ".gradle", ".properties", ".toml",
        ".ini", ".cfg", ".conf", ".cs", ".go", ".rs", ".php", ".rb", ".swift",
        ".dart", ".jsx", ".tsx", ".vue", ".svelte", ".dockerfile"
    )

    private val PROJECT_SOURCE_EXTENSIONS = SUPPORTED_EXTENSIONS - setOf(".pdf", ".zip")
    private val IMPORTANT_PROJECT_NAMES = setOf(
        "androidmanifest.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "gradle.properties", "package.json", "package-lock.json",
        "pyproject.toml", "requirements.txt", "dockerfile", "makefile", "cmakelists.txt",
        ".gitignore", ".env.example"
    )
    private val SKIP_PARTS = setOf(
        ".git", ".gradle", ".idea", ".vscode", "build", "dist", "out", "target",
        "node_modules", "__pycache__", ".venv", "venv", "vendor", "coverage"
    )
    const val PROJECT_SNAPSHOT_MIME = "text/x-aura-project"
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PROJECT_ZIP_BYTES = 300L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 2_500
    private const val MAX_PROJECT_FILE_BYTES = 768 * 1024
    private const val MAX_TOTAL_INSPECTED_BYTES = 48L * 1024 * 1024
    private const val MAX_PROJECT_FILE_CHARS = 40_000
    private const val MAX_SELECTED_FILE_CHARS = 18_000
    private const val MAX_SELECTED_PROJECT_FILES = 64
    private const val MAX_PROJECT_MAP_CHARS = 30_000
    private const val MAX_PROJECT_SNAPSHOT_CHARS = 5_500_000
}
