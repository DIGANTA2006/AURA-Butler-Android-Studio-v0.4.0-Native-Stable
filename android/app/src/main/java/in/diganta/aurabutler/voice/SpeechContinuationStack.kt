package `in`.diganta.aurabutler.voice

import java.util.ArrayDeque

/**
 * A small LIFO continuation stack for nested speech interruptions.
 *
 * The most recently interrupted response is resumed first. The fixed capacity
 * prevents an extended interruption chain from growing memory without bound.
 */
class SpeechContinuationStack(
    private val capacity: Int = DEFAULT_CAPACITY
) {
    private val pending = ArrayDeque<String>()

    init {
        require(capacity > 0) { "Speech continuation capacity must be positive." }
    }

    @Synchronized
    fun push(remainingText: String): Boolean {
        val text = remainingText.trim()
        if (text.isBlank()) return false
        if (pending.peekFirst() == text) return false

        pending.addFirst(text)
        while (pending.size > capacity) {
            pending.removeLast()
        }
        return true
    }

    @Synchronized
    fun pop(): String? = pending.pollFirst()

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun size(): Int = pending.size

    companion object {
        const val DEFAULT_CAPACITY = 6
    }
}
