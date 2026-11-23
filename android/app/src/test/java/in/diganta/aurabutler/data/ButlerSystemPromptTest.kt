package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButlerSystemPromptTest {
    @Test
    fun privateMemoryIsOffByDefaultButPrivateChatHistoryIsAllowed() {
        val context = ConversationContext(emptyList(), emptyList(), ButlerSettings())
        val prompt = ButlerSystemPrompt.build(context, localProvider = false)
        assertTrue(prompt.contains("Private memory sharing is disabled"))
        assertTrue(ButlerSystemPrompt.historyAllowed(context, localProvider = true))
        assertFalse(ButlerSystemPrompt.historyAllowed(context, localProvider = false))
    }

    @Test
    fun externalHistoryRequiresOwnerOptIn() {
        val context = ConversationContext(
            emptyList(),
            emptyList(),
            ButlerSettings(allowAiPrivateContext = true)
        )
        assertTrue(ButlerSystemPrompt.historyAllowed(context, localProvider = false))
    }

    @Test
    fun canonicalIdentityNamesDigantaAsCreatorWithoutVendorOwnershipText() {
        val context = ConversationContext(emptyList(), emptyList(), ButlerSettings())
        val prompt = ButlerSystemPrompt.build(context, localProvider = false)

        assertTrue(prompt.contains("Autonomous Unified Response Assistant"))
        assertTrue(prompt.contains("Diganta Chakraborty"))
        assertFalse(prompt.contains("OpenAI owns"))
        assertFalse(prompt.contains("Never claim that OpenAI"))
    }

    @Test
    fun softwareEngineeringRulesRequireExactCodeAndEvidence() {
        val context = ConversationContext(emptyList(), emptyList(), ButlerSettings())
        val prompt = ButlerSystemPrompt.build(context, localProvider = true)
        assertTrue(prompt.contains("complete, runnable or buildable code"))
        assertTrue(prompt.contains("Do not silently rewrite identifiers"))
        assertTrue(prompt.contains("actual evidence"))
    }
    @Test
    fun privateQwenReceivesRetrievedEncryptedMemoryWithoutExternalOptIn() {
        val context = ConversationContext(
            emptyList(),
            listOf(MemoryItem(label = "[DOC] project.kt", value = "class ProjectSentinel")),
            ButlerSettings(allowAiPrivateContext = false)
        )
        val localPrompt = ButlerSystemPrompt.build(context, localProvider = true)
        val externalPrompt = ButlerSystemPrompt.build(context, localProvider = false)

        assertTrue(localPrompt.contains("ProjectSentinel"))
        assertFalse(externalPrompt.contains("ProjectSentinel"))
    }

}
