package `in`.diganta.aurabutler.chat

import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    @Test
    fun titleCollapsesWhitespaceFromFirstUserMessage() {
        val messages = listOf(ChatMessage(sender = Sender.USER, text = "  hello   there "))
        assertEquals("hello there", ChatText.deriveTitle(messages))
    }

    @Test
    fun longTitleIsTruncatedWithEllipsis() {
        val title = ChatText.deriveTitle(listOf(ChatMessage(sender = Sender.USER, text = "a".repeat(80))))
        assertEquals(48, title?.length)
        assertTrue(title?.endsWith("...") == true)
    }

    @Test
    fun noTitleWithoutUserText() {
        val messages = listOf(ChatMessage(sender = Sender.AURA, text = "Hello"))
        assertNull(ChatText.deriveTitle(messages))
    }

    @Test
    fun matchingIgnoresCase() {
        assertTrue(ChatText.matches("Hello World", "world"))
    }

    @Test
    fun snippetAroundMatchKeepsTheMatch() {
        val snippet = ChatText.snippet("Please remind me about the CEMK lab report tomorrow morning", "lab report")
        assertTrue(snippet.contains("lab report"))
    }
}