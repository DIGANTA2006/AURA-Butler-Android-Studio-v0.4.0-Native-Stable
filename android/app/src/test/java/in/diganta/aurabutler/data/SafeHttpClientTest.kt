package `in`.diganta.aurabutler.data

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHttpClientTest {
    @Test
    fun readUtf8RejectsBodiesLargerThanTheConfiguredLimit() {
        val stream = ByteArrayInputStream("123456".toByteArray())
        val error = runCatching { SafeHttpClient.readUtf8(stream, maxBytes = 5) }
            .exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun readUtf8RejectsAnInvalidLimit() {
        val error = runCatching {
            SafeHttpClient.readUtf8(ByteArrayInputStream(byteArrayOf(1)), maxBytes = 0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun postRejectsNonPositiveTimeoutBeforeOpeningConnection() {
        val error = runCatching {
            SafeHttpClient.postJson(
                endpoint = "https://example.com/v1",
                body = "{}",
                userAgent = "AURA-test",
                connectTimeoutMs = 0,
                readTimeoutMs = 1_000
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun postRejectsCredentialsEmbeddedInEndpointUrl() {
        val error = runCatching {
            SafeHttpClient.postJson(
                endpoint = "https://user:password@example.com/v1",
                body = "{}",
                userAgent = "AURA-test",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 1_000
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun postRejectsRequestsOverTheBodyLimitBeforeOpeningConnection() {
        val oversizedBody = "x".repeat(SafeHttpClient.MAX_ALLOWED_REQUEST_BYTES + 1)
        val error = runCatching {
            SafeHttpClient.postJson(
                endpoint = "https://example.com/v1",
                body = oversizedBody,
                userAgent = "AURA-test",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 1_000
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun requestBodyLimitIsEightMiB() {
        assertEquals(8 * 1024 * 1024, SafeHttpClient.MAX_ALLOWED_REQUEST_BYTES)
    }
}
