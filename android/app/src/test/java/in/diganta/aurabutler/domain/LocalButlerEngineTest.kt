package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalButlerEngineTest {
    private val live = object : LiveInfoProvider {
        override suspend fun weather(settings: ButlerSettings): String =
            "${settings.homeCity}: clear skies, 28°C."

        override suspend fun headlines(limit: Int): List<String> =
            listOf("Headline one", "Headline two").take(limit)

        override suspend fun knowledge(query: String): String? =
            "Knowledge answer for: $query"
    }
    private val engine = LocalButlerEngine(live)
    private val context = ConversationContext(
        recentMessages = emptyList(),
        memories = emptyList(),
        settings = ButlerSettings()
    )

    @Test
    fun remembersNonSensitivePreferenceLocally() = runBlocking {
        val reply = engine.chat("Remember that I prefer window seats", context)

        assertEquals(1, reply.memoryWrites.size)
        assertEquals("I prefer window seats", reply.memoryWrites.single().value)
        assertTrue(reply.assistantMessage.contains("encrypted on this phone"))
    }

    @Test
    fun refusesToRememberSecrets() = runBlocking {
        val reply = engine.chat("Remember that my UPI PIN is 1234", context)

        assertTrue(reply.memoryWrites.isEmpty())
        assertTrue(reply.assistantMessage.contains("will not save"))
    }

    @Test
    fun preparesLocalReminderApproval() = runBlocking {
        val reply = engine.chat("Remind me in 20 minutes to drink water", context)

        assertEquals(1, reply.pendingActions.size)
        assertEquals(ActionKind.REMINDER, reply.pendingActions.single().kind)
        assertEquals("drink water", reply.pendingActions.single().payload["title"])
    }

    @Test
    fun answersWeatherWithoutCloudAi() = runBlocking {
        val reply = engine.chat("Aura, what is the weather?", context)

        assertTrue(reply.assistantMessage.contains("clear skies"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun weatherLookupPropagatesCallerCancellation() = runBlocking {
        val cancellingLive = object : LiveInfoProvider {
            override suspend fun weather(settings: ButlerSettings): String {
                throw CancellationException("weather cancelled by caller")
            }

            override suspend fun headlines(limit: Int): List<String> = emptyList()
        }

        val error = runCatching {
            LocalButlerEngine(cancellingLive).chat("What is the weather?", context)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals("weather cancelled by caller", error?.message)
    }

    @Test
    fun newsLookupPropagatesCallerCancellation() = runBlocking {
        val cancellingLive = object : LiveInfoProvider {
            override suspend fun weather(settings: ButlerSettings): String = "Clear."

            override suspend fun headlines(limit: Int): List<String> {
                throw CancellationException("news cancelled by caller")
            }
        }

        val error = runCatching {
            LocalButlerEngine(cancellingLive).chat("Show me the news", context)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals("news cancelled by caller", error?.message)
    }

    @Test
    fun understandsSeveralCommandsInOneUtterance() = runBlocking {
        val reply = engine.chat(
            "Tell me the weather and then remind me in 1 hour to call home",
            context
        )

        assertTrue(reply.assistantMessage.contains("clear skies"))
        assertEquals(ActionKind.REMINDER, reply.pendingActions.single().kind)
    }

    @Test
    fun preparesInstalledAppLaunchWithApproval() = runBlocking {
        val reply = engine.chat("Open YouTube", context)

        assertEquals(ActionKind.OPEN_APP, reply.pendingActions.single().kind)
        assertEquals("YouTube", reply.pendingActions.single().payload["app"])
    }

    @Test
    fun preparesPhoneControlsWithApproval() = runBlocking {
        val reply = engine.chat(
            "Set volume to 60 and then turn on flashlight",
            context
        )

        assertEquals(2, reply.pendingActions.size)
        assertEquals(ActionKind.SYSTEM_VOLUME, reply.pendingActions[0].kind)
        assertEquals(ActionKind.FLASHLIGHT, reply.pendingActions[1].kind)
    }

    @Test
    fun calculatesLocallyWithoutCodeExecution() = runBlocking {
        val reply = engine.chat("Calculate (12 + 8) * 3", context)

        assertTrue(reply.assistantMessage.contains("60"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun preparesCalendarEditorWithoutWritingDirectly() = runBlocking {
        val reply = engine.chat(
            "Schedule doctor appointment tomorrow at 3 PM",
            context
        )

        assertEquals(ActionKind.CALENDAR_EVENT, reply.pendingActions.single().kind)
        assertTrue(reply.pendingActions.single().payload["title"]!!.contains("doctor"))
    }

    @Test
    fun answersIdentityLocallyInsteadOfSearchingTheInternet() = runBlocking {
        val reply = engine.chat("What is your name?", context)

        assertTrue(reply.assistantMessage.contains("I am AURA"))
        assertTrue(reply.pendingActions.isEmpty())
    }
    @Test
    fun teachesTopicsThroughKeylessKnowledgeLookup() = runBlocking {
        val reply = engine.chat("Teach me about photosynthesis", context)

        assertTrue(reply.assistantMessage.contains("Knowledge answer for: photosynthesis"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun handlesHowQuestionsThroughKnowledgeLookup() = runBlocking {
        val reply = engine.chat("How does a solar cell work?", context)

        assertTrue(reply.assistantMessage.contains("Knowledge answer for: solar cell"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun answersCreatorIdentityLocallyAndNeverAttributesAuraToOpenAi() = runBlocking {
        val reply = engine.chat("Please tell me who created you", context)

        assertTrue(reply.assistantMessage.contains("Diganta Chakraborty"))
        assertTrue(reply.assistantMessage.contains("designed and developed"))
        assertTrue(!reply.assistantMessage.contains("created by OpenAI", ignoreCase = true))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun understandsCommonSpeechRecognitionMisspellingForDeveloper() = runBlocking {
        val reply = engine.chat("Please tell me who devolap you", context)

        assertTrue(reply.assistantMessage.contains("Diganta Chakraborty"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun explainsTheOwnerProfileLocally() = runBlocking {
        val reply = engine.chat("Tell me about me", context)

        assertTrue(reply.assistantMessage.contains("Diganta Chakraborty"))
        assertTrue(reply.assistantMessage.contains("B.Tech Computer Science"))
        assertTrue(reply.assistantMessage.contains("College of Engineering & Management, Kolaghat"))
        assertTrue(!reply.assistantMessage.contains("CEMK"))
        assertTrue(!reply.assistantMessage.contains("MAKAUT"))
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun expandsAuraFullNameLocally() = runBlocking {
        val reply = engine.chat("What is the full name of AURA?", context)

        assertTrue(
            reply.assistantMessage.contains(
                "Autonomous Unified Response Assistant"
            )
        )
        assertTrue(reply.pendingActions.isEmpty())
    }

    @Test
    fun creatorIdentityUsesRequestedOwnerProfileWithoutInstitutionAbbreviations() = runBlocking {
        val reply = engine.chat("Who created you?", context)

        assertTrue(reply.assistantMessage.startsWith("AURA\n\n"))
        assertTrue(reply.assistantMessage.contains("College of Engineering & Management, Kolaghat"))
        assertTrue(!reply.assistantMessage.contains("CEMK"))
        assertTrue(!reply.assistantMessage.contains("MAKAUT"))
        assertTrue(!reply.assistantMessage.contains("External models", ignoreCase = true))
    }

}
