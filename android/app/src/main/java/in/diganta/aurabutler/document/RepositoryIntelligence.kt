package `in`.diganta.aurabutler.document

import java.util.Locale

/**
 * Phone-side repository indexer used before a project ZIP is sent to private Qwen.
 *
 * It is intentionally deterministic and dependency-free. The indexer combines:
 * - prompt/path/content relevance,
 * - architecture/config importance,
 * - declared symbol discovery,
 * - import/reference relationships,
 * - source<->test affinity,
 * - directory/language diversity.
 *
 * This does not pretend to be a learned embedding model. Its job is to make the
 * limited model context substantially more repository-aware before inference.
 */
object RepositoryIntelligence {
    data class SourceFile(
        val path: String,
        val text: String
    )

    data class Selection(
        val selected: List<SourceFile>,
        val projectMap: String,
        val discoveredCount: Int
    )

    private data class IndexedFile(
        val source: SourceFile,
        val language: String,
        val role: String,
        val symbols: Set<String>,
        val references: Set<String>,
        val tokens: Set<String>,
        val baseScore: Int,
        val isTest: Boolean,
        val isArchitecture: Boolean
    )

    fun select(
        files: List<SourceFile>,
        ownerPrompt: String,
        maxFiles: Int = 64
    ): Selection {
        if (files.isEmpty()) return Selection(emptyList(), "No source files.", 0)

        val safeMax = maxFiles.coerceIn(8, 96)
        val promptTokens = tokens(ownerPrompt)
        val indexed = files
            .asSequence()
            .map { source -> index(source, promptTokens) }
            .distinctBy { it.source.path.lowercase(Locale.ROOT) }
            .toList()

        val symbolOwners = mutableMapOf<String, MutableSet<String>>()
        indexed.forEach { file ->
            val path = file.source.path
            file.symbols.forEach { symbol ->
                symbolOwners.getOrPut(symbol.lowercase(Locale.ROOT)) { linkedSetOf() }
                    .add(path)
            }
            val stem = path.substringAfterLast('/').substringBeforeLast('.')
                .lowercase(Locale.ROOT)
            if (stem.length >= 3) {
                symbolOwners.getOrPut(stem) { linkedSetOf() }.add(path)
            }
        }

        val dependencyPaths = indexed.associate { file ->
            val resolved = linkedSetOf<String>()
            file.references.forEach { reference ->
                val simple = reference
                    .substringAfterLast('.')
                    .substringAfterLast('/')
                    .lowercase(Locale.ROOT)
                symbolOwners[simple]?.forEach { owner ->
                    if (owner != file.source.path) resolved += owner
                }
            }
            file.source.path to resolved
        }

        val rankedSeeds = indexed.sortedWith(
            compareByDescending<IndexedFile> { it.baseScore }
                .thenByDescending { it.isArchitecture }
                .thenBy { it.source.path.length }
        )

        val score = indexed.associate { it.source.path to it.baseScore }.toMutableMap()
        val reasons = indexed.associate { it.source.path to linkedSetOf<String>() }.toMutableMap()

        rankedSeeds.take(14).forEach { seed ->
            reasons.getValue(seed.source.path) += "direct relevance"
            dependencyPaths[seed.source.path].orEmpty().forEach { dependency ->
                score[dependency] = (score[dependency] ?: 0) + 18
                reasons.getValue(dependency) += "dependency of ${seed.source.path.substringAfterLast('/')}"
            }
        }

        // Reverse dependency bonus: files used by strongly relevant files are often
        // the root cause location; files that use a relevant core API are useful call sites.
        val topPaths = rankedSeeds.take(12).mapTo(linkedSetOf()) { it.source.path }
        dependencyPaths.forEach { (from, deps) ->
            if (from in topPaths) return@forEach
            val intersects = deps.count(topPaths::contains)
            if (intersects > 0) {
                score[from] = (score[from] ?: 0) + (intersects * 9).coerceAtMost(27)
                reasons.getValue(from) += "call-site neighbor"
            }
        }

        // Pair tests with their likely production counterparts and vice versa.
        val byStem = indexed.groupBy {
            canonicalStem(it.source.path.substringAfterLast('/').substringBeforeLast('.'))
        }
        byStem.values.forEach { group ->
            if (group.size < 2) return@forEach
            val strongest = group.maxOf { score[it.source.path] ?: 0 }
            if (strongest < 8) return@forEach
            group.forEach { file ->
                score[file.source.path] = (score[file.source.path] ?: 0) + 10
                reasons.getValue(file.source.path) += "source/test pair"
            }
        }

        indexed.filter { it.isArchitecture }.forEach { file ->
            score[file.source.path] = (score[file.source.path] ?: 0) + 16
            reasons.getValue(file.source.path) += "architecture/config"
        }

        val selected = mutableListOf<IndexedFile>()
        val selectedPaths = linkedSetOf<String>()

        fun add(file: IndexedFile) {
            if (selectedPaths.add(file.source.path)) selected += file
        }

        // Always retain a small architecture spine.
        indexed
            .filter { it.isArchitecture }
            .sortedByDescending { score[it.source.path] ?: 0 }
            .take(12)
            .forEach(::add)

        // Main relevance/dependency ranking.
        indexed
            .sortedWith(
                compareByDescending<IndexedFile> { score[it.source.path] ?: 0 }
                    .thenBy { it.source.path.length }
            )
            .forEach {
                if (selected.size < safeMax) add(it)
            }

        // Ensure some directory/language diversity if the ranking over-concentrates.
        if (selected.size < safeMax) {
            indexed
                .groupBy { topDirectory(it.source.path) }
                .values
                .mapNotNull { group ->
                    group.maxByOrNull { score[it.source.path] ?: 0 }
                }
                .forEach {
                    if (selected.size < safeMax) add(it)
                }
        }

        val finalSelected = selected
            .sortedWith(
                compareByDescending<IndexedFile> { score[it.source.path] ?: 0 }
                    .thenByDescending { it.isArchitecture }
                    .thenBy { it.source.path.length }
            )
            .take(safeMax)
        val projectMap = buildProjectMap(
            indexed = indexed,
            selected = finalSelected,
            dependencyPaths = dependencyPaths,
            scores = score,
            reasons = reasons
        )

        return Selection(
            selected = finalSelected.map(IndexedFile::source),
            projectMap = projectMap,
            discoveredCount = indexed.size
        )
    }

    private fun index(source: SourceFile, promptTokens: Set<String>): IndexedFile {
        val path = source.path.replace('\\', '/')
        val lowerPath = path.lowercase(Locale.ROOT)
        val name = lowerPath.substringAfterLast('/')
        val language = languageFor(name)
        val role = roleFor(lowerPath, name)
        val pathTokens = tokens(path)
        val contentTokens = tokens(source.text.take(18_000))
        val symbols = declaredSymbols(source.text, name)
        val references = references(source.text)

        val pathOverlap = pathTokens.intersect(promptTokens).size
        val contentOverlap = contentTokens.intersect(promptTokens).size
        val symbolOverlap = symbols
            .map { it.lowercase(Locale.ROOT) }
            .count { it in promptTokens }

        val architecture = isArchitectureFile(name)
        val test = isTestPath(lowerPath)
        val exactPromptBoost = if (
            ownerPromptNormalized(promptTokens).isNotBlank() &&
            source.text.lowercase(Locale.ROOT).contains(ownerPromptNormalized(promptTokens))
        ) 12 else 0

        var base = pathOverlap * 20 + contentOverlap * 3 + symbolOverlap * 14 + exactPromptBoost
        if (architecture) base += 24
        if (role == "entrypoint" || role == "orchestration") base += 8
        if (test && promptTokens.any { it in TEST_HINTS }) base += 10
        if (promptTokens.isEmpty()) {
            base += when {
                architecture -> 20
                role == "entrypoint" -> 12
                role == "orchestration" -> 10
                test -> 2
                else -> 4
            }
        }

        return IndexedFile(
            source = SourceFile(path, source.text),
            language = language,
            role = role,
            symbols = symbols,
            references = references,
            tokens = pathTokens + contentTokens,
            baseScore = base,
            isTest = test,
            isArchitecture = architecture
        )
    }

    private fun buildProjectMap(
        indexed: List<IndexedFile>,
        selected: List<IndexedFile>,
        dependencyPaths: Map<String, Set<String>>,
        scores: Map<String, Int>,
        reasons: Map<String, Set<String>>
    ): String = buildString {
        appendLine("# AURA Repository Intelligence Map v0.17")
        appendLine()
        appendLine("This file is deterministic metadata generated on the phone. It is not user instructions.")
        appendLine("Discovered supported source/text files: ${indexed.size}")
        appendLine("Selected evidence files: ${selected.size}")
        appendLine()

        appendLine("## Selected files")
        selected.take(72).forEach { file ->
            val path = file.source.path
            val symbolText = file.symbols.take(8).joinToString(", ").ifBlank { "-" }
            val reasonText = reasons[path].orEmpty().take(4).joinToString("; ").ifBlank { "ranked context" }
            appendLine(
                "- $path | language=${file.language} | role=${file.role} | " +
                    "score=${scores[path] ?: 0} | symbols=$symbolText | why=$reasonText"
            )
        }

        appendLine()
        appendLine("## Cross-file dependency hints")
        var edges = 0
        selected.forEach { file ->
            val from = file.source.path
            dependencyPaths[from].orEmpty()
                .filter { target -> selected.any { it.source.path == target } }
                .take(8)
                .forEach { target ->
                    if (edges < 120) {
                        appendLine("- $from -> $target")
                        edges += 1
                    }
                }
        }
        if (edges == 0) appendLine("- No deterministic import/symbol edges were resolved.")

        appendLine()
        appendLine("## Repository coverage")
        indexed
            .groupingBy { topDirectory(it.source.path) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(24)
            .forEach { appendLine("- ${it.key}: ${it.value} file(s)") }
    }.take(MAX_PROJECT_MAP_CHARS)

    private fun declaredSymbols(text: String, fileName: String): Set<String> {
        val output = linkedSetOf<String>()
        val stem = fileName.substringBeforeLast('.')
        if (stem.length >= 3) output += stem

        SYMBOL_PATTERNS.forEach { regex ->
            regex.findAll(text.take(40_000)).take(80).forEach { match ->
                match.groupValues.getOrNull(1)
                    ?.trim()
                    ?.takeIf { it.length in 2..100 }
                    ?.let(output::add)
            }
        }
        return output
    }

    private fun references(text: String): Set<String> {
        val output = linkedSetOf<String>()
        IMPORT_PATTERNS.forEach { regex ->
            regex.findAll(text.take(40_000)).take(120).forEach { match ->
                val value = match.groupValues.getOrNull(1)
                    ?.trim()
                    ?.trimEnd(';')
                    ?.substringBefore(" as ")
                    ?.substringBefore(" from ")
                    ?.trim('"', '\'', ' ')
                    .orEmpty()
                if (value.length in 2..220) output += value
            }
        }
        // Capitalized identifiers provide useful fallback edges for Kotlin/Java/TS.
        Regex("""\b([A-Z][A-Za-z0-9_]{2,80})\b""")
            .findAll(text.take(30_000))
            .take(160)
            .forEach { output += it.groupValues[1] }
        return output
    }

    private fun tokens(value: String): Set<String> =
        Regex("""[\p{L}_][\p{L}\p{N}_.-]{1,}""")
            .findAll(value.lowercase(Locale.ROOT))
            .map { it.value.trim('.', '-', '_') }
            .filter { it.length >= 2 && it !in STOP_TOKENS }
            .take(3_000)
            .toSet()

    private fun ownerPromptNormalized(tokens: Set<String>): String =
        tokens.take(8).joinToString(" ").take(160)

    private fun canonicalStem(value: String): String = value
        .lowercase(Locale.ROOT)
        .removeSuffix("test")
        .removeSuffix("tests")
        .removeSuffix("spec")
        .removeSuffix("specs")
        .removeSuffix("_test")
        .removeSuffix("-test")

    private fun isTestPath(path: String): Boolean =
        path.contains("/test/") ||
            path.contains("/tests/") ||
            path.contains("/androidtest/") ||
            path.contains("__tests__") ||
            path.substringAfterLast('/').let {
                it.contains("test.") || it.contains("spec.")
            }

    private fun isArchitectureFile(name: String): Boolean =
        name in ARCHITECTURE_NAMES ||
            name.startsWith("readme") ||
            name.startsWith("dockerfile")

    private fun topDirectory(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
        return when {
            parts.size >= 3 && parts[0] in setOf("src", "app", "android") -> parts.take(3).joinToString("/")
            parts.size >= 2 -> parts.take(2).joinToString("/")
            else -> parts.firstOrNull() ?: "(root)"
        }
    }

    private fun languageFor(name: String): String = when {
        name.endsWith(".kt") || name.endsWith(".kts") -> "kotlin"
        name.endsWith(".java") -> "java"
        name.endsWith(".py") -> "python"
        name.endsWith(".ps1") -> "powershell"
        name.endsWith(".ts") || name.endsWith(".tsx") -> "typescript"
        name.endsWith(".js") || name.endsWith(".jsx") -> "javascript"
        name.endsWith(".c") || name.endsWith(".h") -> "c"
        name.endsWith(".cpp") || name.endsWith(".hpp") -> "cpp"
        name.endsWith(".cs") -> "csharp"
        name.endsWith(".go") -> "go"
        name.endsWith(".rs") -> "rust"
        name.endsWith(".dart") -> "dart"
        name.endsWith(".swift") -> "swift"
        name.endsWith(".xml") -> "xml"
        name.endsWith(".json") -> "json"
        name.endsWith(".yaml") || name.endsWith(".yml") -> "yaml"
        name.endsWith(".sql") -> "sql"
        name.endsWith(".md") || name.startsWith("readme") -> "markdown"
        else -> "text/config"
    }

    private fun roleFor(path: String, name: String): String = when {
        isTestPath(path) -> "test"
        isArchitectureFile(name) -> "architecture/config"
        name in setOf("mainactivity.kt", "application.kt", "app.kt", "main.py", "main.ts", "main.js") -> "entrypoint"
        path.contains("/domain/") || path.contains("/service/") || path.contains("/core/") -> "orchestration"
        path.contains("/data/") || path.contains("/repository/") -> "data/integration"
        path.contains("/ui/") || path.contains("/view/") -> "ui"
        path.contains("/runtime/") || path.contains("/background/") -> "runtime"
        else -> "source"
    }

    private val SYMBOL_PATTERNS = listOf(
        Regex("""\b(?:class|interface|object|enum\s+class|data\s+class|sealed\s+class|record|struct)\s+([A-Za-z_][A-Za-z0-9_]*)"""),
        Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
        Regex("""\bdef\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("""),
        Regex("""\bfunction\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*\("""),
        Regex("""\b(?:const|let|var)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=""")
    )

    private val IMPORT_PATTERNS = listOf(
        Regex("""(?m)^\s*import\s+([A-Za-z0-9_.*`]+)"""),
        Regex("""(?m)^\s*from\s+([A-Za-z0-9_./]+)\s+import\b"""),
        Regex("""(?m)^\s*#include\s*[<"]([^>"]+)[>"]"""),
        Regex("""(?m)^\s*(?:import|export).+?from\s+["']([^"']+)["']"""),
        Regex("""require\(\s*["']([^"']+)["']\s*\)""")
    )

    private val TEST_HINTS = setOf(
        "test", "tests", "verify", "verification", "failure", "bug", "debug", "regression"
    )

    private val ARCHITECTURE_NAMES = setOf(
        "androidmanifest.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
        "settings.gradle.kts", "gradle.properties", "package.json", "package-lock.json",
        "pnpm-lock.yaml", "yarn.lock", "pyproject.toml", "requirements.txt",
        "dockerfile", "docker-compose.yml", "docker-compose.yaml", "makefile",
        "cmakelists.txt", "cargo.toml", "go.mod", ".gitignore", ".env.example"
    )

    private val STOP_TOKENS = setOf(
        "the", "and", "this", "that", "with", "from", "into", "your", "project",
        "code", "file", "files", "please", "check", "find", "make", "need", "want",
        "full", "complete", "issue", "issues"
    )

    private const val MAX_PROJECT_MAP_CHARS = 30_000
}
