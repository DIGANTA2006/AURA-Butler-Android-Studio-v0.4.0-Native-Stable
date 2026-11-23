package `in`.diganta.aurabutler.data

/**
 * Converts model output into text suitable for AURA's chat surface.
 *
 * Important: source code is data, not decoration. Earlier AURA builds removed
 * every '*', '_' and backtick from model output, which could silently corrupt
 * C pointers, multiplication, Python identifiers, shell wildcards and fenced
 * code. v0.15 keeps code characters intact and only normalizes prose-level
 * presentation markers outside fenced code blocks.
 */
object AiResponseFormatter {
    fun plainText(raw: String): String {
        val withoutThinking = stripHiddenThinking(raw)
        if (withoutThinking.isBlank()) return ""

        val output = StringBuilder(withoutThinking.length)
        var inFence = false

        withoutThinking
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEachIndexed { index, originalLine ->
                val trimmed = originalLine.trimStart()
                if (trimmed.startsWith("```")) {
                    inFence = !inFence
                    // Keep fences so the UI can render code with a monospace surface.
                    appendLine(output, originalLine.trimEnd(), index)
                    return@forEachIndexed
                }

                val line = if (inFence) {
                    originalLine
                } else {
                    normalizeProseLine(originalLine)
                }
                appendLine(output, line, index)
            }

        return output.toString()
            .replace(Regex("""\n{4,}"""), "\n\n\n")
            .trim()
    }

    fun containsFencedCode(raw: String): Boolean =
        raw.contains("```")

    private fun normalizeProseLine(line: String): String =
        line
            .replace(
                Regex("""\[([^\]]+)]\((?:https?://|mailto:)[^)]+\)"""),
                "$1"
            )
            .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""^\s*>\s?"""), "")
            .replace(Regex("""^\s*[-+*]\s+"""), "• ")
            .replace(Regex("""\*\*([^*\n]+)\*\*"""), "$1")
            .replace(Regex("""__([^_\n]+)__"""), "$1")
            .replace(Regex("""~~([^~\n]+)~~"""), "$1")
            .replace(Regex("""`([^`\n]+)`"""), "$1")
            .trimEnd()

    private fun appendLine(
        target: StringBuilder,
        line: String,
        index: Int
    ) {
        if (index > 0) target.append('\n')
        target.append(line)
    }

    private fun stripHiddenThinking(raw: String): String {
        val complete = raw.replace(
            Regex("""(?is)<think\b[^>]*>.*?</think\s*>"""),
            ""
        )
        if (!complete.contains("<think", ignoreCase = true)) return complete
        // Fail closed if a provider ended before closing its hidden reasoning block.
        val start = complete.indexOf("<think", ignoreCase = true)
        return if (start >= 0) complete.substring(0, start) else complete
    }
}
