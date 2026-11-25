package `in`.diganta.aurabutler.chat

import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.Sender

/** Pure helpers for chat titles and search snippets. No Android APIs, so they are easy to unit test. */
object ChatText {
    const val DEFAULT_TITLE = "New chat"
    private const val MAX_TITLE_CHARS = 48
    private const val SNIPPET_CHARS = 90

    private val whitespace = Regex("\\s+")

    /** Title from the first user message, or null when there is no usable text yet. */
    fun deriveTitle(messages: List<ChatMessage>): String? {
        val first = messages.firstOrNull { it.sender == Sender.USER }?.text ?: return null
        val clean = first.replace(whitespace, " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX_TITLE_CHARS) {
            clean
        } else {
            clean.take(MAX_TITLE_CHARS - 3).trimEnd() + "..."
        }
    }

    fun matches(text: String, query: String): Boolean =
        text.contains(query.trim(), ignoreCase = true)

    /** A short excerpt around the first match, or the start of the text when nothing matches. */
    fun snippet(text: String, query: String): String {
        val clean = text.replace(whitespace, " ").trim()
        val needle = query.trim()
        val at = clean.indexOf(needle, ignoreCase = true)
        if (at < 0) return clean.take(SNIPPET_CHARS)
        val start = (at - 30).coerceAtLeast(0)
        val end = (at + needle.length + 50).coerceAtMost(clean.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < clean.length) "..." else ""
        return prefix + clean.substring(start, end) + suffix
    }
}