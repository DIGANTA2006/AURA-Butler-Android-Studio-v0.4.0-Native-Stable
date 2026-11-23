package `in`.diganta.aurabutler.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeEndpointValidatorTest {
    @Test fun allowsLoopbackOllama() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            SafeEndpointValidator.normalizeOpenAiEndpoint("http://127.0.0.1:11434/v1")
        )
    }
    @Test fun rejectsPlainHttpRemoteHost() {
        val error = runCatching {
            SafeEndpointValidator.normalizeOpenAiEndpoint("http://192.168.1.5:11434/v1")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
    @Test fun rejectsEmbeddedCredentials() {
        val error = runCatching {
            SafeEndpointValidator.normalizeOpenAiEndpoint("https://user:pass@example.com/v1")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
