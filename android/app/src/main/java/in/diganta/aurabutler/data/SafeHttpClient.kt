package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.runtime.SafeEndpointValidator
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

/** Small hardened HTTP boundary with bounded response bodies and Modal 303 polling support. */
object SafeHttpClient {
    data class TextResponse(
        val status: Int,
        val body: String,
        val finalUrl: String
    )

    fun open(
        endpoint: String,
        allowLoopbackHttp: Boolean = false
    ): HttpURLConnection {
        val url = URL(endpoint)
        val protocol = url.protocol.lowercase()
        require(protocol == "https" || (
            allowLoopbackHttp &&
                protocol == "http" &&
                SafeEndpointValidator.isLoopback(url.host)
            )) {
            "AURA blocked an unsafe network endpoint."
        }
        return (url.openConnection() as HttpURLConnection).apply {
            useCaches = false
            instanceFollowRedirects = false
        }
    }

    /**
     * Sends JSON and follows Modal's long-running Web Function result redirects.
     *
     * Modal may answer a request that crosses its HTTP execution window with a
     * 303 result URL. We follow only HTTPS redirects on the original host, or
     * another *.modal.run host when the original endpoint is itself on Modal.
     * This keeps long cold starts/research jobs functional without opening an
     * arbitrary redirect/SSRF boundary.
     */
    fun postJsonFollowingTrustedRedirects(
        endpoint: String,
        body: String,
        bearerToken: String = "",
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
        maxRedirects: Int = 10
    ): TextResponse {
        require(maxRedirects in 0..20)
        val original = URI(endpoint)
        val originalHost = original.host?.lowercase().orEmpty()
        require(originalHost.isNotBlank()) { "The endpoint does not include a valid host." }
        val originalIsModal = originalHost.endsWith(".modal.run")

        var current = endpoint
        var method = "POST"
        var redirects = 0

        while (true) {
            val connection = open(current).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = method == "POST"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", userAgent)
                bearerToken.trim().takeIf(String::isNotBlank)?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
                if (method == "POST") {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }

            try {
                if (method == "POST") {
                    connection.outputStream.use { output ->
                        output.write(body.toByteArray(StandardCharsets.UTF_8))
                    }
                }

                val status = connection.responseCode
                if (status == HttpURLConnection.HTTP_SEE_OTHER) {
                    if (redirects >= maxRedirects) {
                        throw IOException("The cloud result redirected too many times.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?: throw IOException("The cloud result redirect had no location.")
                    val next = URI(current).resolve(location)
                    validateTrustedRedirect(
                        originalHost = originalHost,
                        originalIsModal = originalIsModal,
                        target = next
                    )
                    current = next.toString()
                    method = "GET"
                    redirects += 1
                    continue
                }

                val responseBody = readUtf8(
                    if (status in 200..299) connection.inputStream else connection.errorStream,
                    maxResponseBytes
                )
                return TextResponse(status, responseBody, current)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validateTrustedRedirect(
        originalHost: String,
        originalIsModal: Boolean,
        target: URI
    ) {
        val scheme = target.scheme?.lowercase().orEmpty()
        val host = target.host?.lowercase().orEmpty()
        require(scheme == "https") { "AURA blocked an unencrypted cloud redirect." }
        require(host.isNotBlank()) { "AURA blocked a redirect without a host." }
        require(target.userInfo.isNullOrBlank()) { "AURA blocked credentials in a redirect URL." }
        require(
            host == originalHost ||
                (originalIsModal && host.endsWith(".modal.run"))
        ) {
            "AURA blocked an unexpected cross-domain cloud redirect."
        }
    }

    fun readUtf8(
        stream: InputStream?,
        maxBytes: Int = DEFAULT_MAX_RESPONSE_BYTES
    ): String {
        if (stream == null) return ""
        require(maxBytes in 1..MAX_ALLOWED_RESPONSE_BYTES)
        stream.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw IOException("The remote response exceeded AURA's safety limit.")
                }
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }

    const val DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_ALLOWED_RESPONSE_BYTES = 8 * 1024 * 1024
}
