package `in`.diganta.aurabutler.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun programmingAboutApiKeysIsNotMistakenForARealSecret() {
        val code = "val apiKey = System.getenv(\"AURA_API_KEY\")"
        assertFalse(SecretRedactor.containsLikelySecret(code))
        assertTrue(SecretRedactor.redact(code).contains("System.getenv"))
    }

    @Test
    fun recognizesAndRedactsConcreteCredentialPatterns() {
        val syntheticGroqToken = "gsk" + "_TESTNOTAREALKEY1234567890ABCD"
        val text = "token = $syntheticGroqToken"
        assertTrue(SecretRedactor.containsLikelySecret(text))
        assertTrue(SecretRedactor.redact(text).contains("[REDACTED SECRET]"))
    }
}
