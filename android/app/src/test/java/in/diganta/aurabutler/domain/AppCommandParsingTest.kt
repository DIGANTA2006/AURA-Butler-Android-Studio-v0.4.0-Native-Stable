package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppCommandParsingTest {
    private val live = object : LiveInfoProvider {
        override suspend fun weather(settings: ButlerSettings): String = "Clear."
        override suspend fun headlines(limit: Int): List<String> = emptyList()
    }
    private val engine = LocalButlerEngine(live)
    private val context = ConversationContext(
        recentMessages = emptyList(),
        memories = emptyList(),
        settings = ButlerSettings()
    )

    @Test
    fun acceptsPoliteYouTubeCommand() = runBlocking {
        val reply = engine.chat("Please open the You Tube app", context)

        assertEquals(ActionKind.OPEN_APP, reply.pendingActions.single().kind)
        assertEquals("You Tube", reply.pendingActions.single().payload["app"])
    }

    @Test
    fun acceptsCanYouOpenCommand() = runBlocking {
        val reply = engine.chat("Could you launch Chrome please", context)

        assertEquals(ActionKind.OPEN_APP, reply.pendingActions.single().kind)
        assertEquals("Chrome", reply.pendingActions.single().payload["app"])
    }
}
