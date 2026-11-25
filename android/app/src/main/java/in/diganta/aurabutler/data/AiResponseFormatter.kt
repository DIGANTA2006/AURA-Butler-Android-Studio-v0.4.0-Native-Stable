package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.runtime.OperatingMode

/**
 * Converts model output into text suitable for AURA's chat surface.
 *
 * Important: source code is data, not decoration. Earlier AURA builds removed
 * every '*', '_' and backtick from model output, which could silently corrupt
 * C pointers, multiplication, Python identifiers, shell wildcards and fenced
 * code. v0.15+ keeps code characters intact and only normalizes prose-level
 * presentation markers outside fenced code blocks.
 *
 * Token-aware streaming: no fixed character truncation. Chunked continuation
 * is handled by the caller (ButlerViewModel, VoiceConversationEngine).
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

    /**
     * Streaming delta formatter — preserves code fences across chunk boundaries.
     * Caller maintains `inFence` state across deltas.
     */
    fun formatStreamingDelta(delta: String, inFence: Boolean): Pair<String, Boolean> {
        var currentInFence = inFence
        val output = StringBuilder(delta.length)

        delta
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEachIndexed { index, originalLine ->
                val trimmed = originalLine.trimStart()
                if (trimmed.startsWith("```")) {
                    currentInFence = !currentInFence
                    appendLine(output, originalLine.trimEnd(), index)
                    return@forEachIndexed
                }

                val line = if (currentInFence) {
                    originalLine
                } else {
                    normalizeProseLine(originalLine)
                }
                appendLine(output, line, index)
            }

        return output.toString() to currentInFence
    }

    /**
     * Streaming variant of hidden thinking removal.
     * Handles partial/streaming chunks where thinking tags may be split across deltas.
     */
    fun stripHiddenThinkingForStreaming(raw: String): String {
        // For streaming, we can't reliably detect partial tags across chunks
        // without maintaining state. For now, do best-effort removal of complete tags.
        // The caller should maintain thinking state across deltas for production use.
        return raw.replace(
            Regex("""(?is)<think\b[^>]*>.*?</think\s*>"""),
            ""
        )
    }

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
