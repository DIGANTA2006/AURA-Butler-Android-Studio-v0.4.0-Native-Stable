package `in`.diganta.aurabutler.runtime

import java.net.URI
import java.util.Locale

/** Strict validation for owner-configured OpenAI-compatible endpoints. */
object SafeEndpointValidator {
    const val DEFAULT_LOCAL_ENDPOINT =
        "https://diganta2006--aura-dual-model-server-qwentext-web.modal.run/v1/chat/completions"

    fun normalizeOpenAiEndpoint(raw: String): String {
        val candidate = raw.trim().ifBlank { DEFAULT_LOCAL_ENDPOINT }
        val uri = runCatching { URI(candidate) }.getOrElse {
            throw IllegalArgumentException("The AI endpoint is not a valid URL.")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()

        require(scheme == "https" || scheme == "http") {
            "The AI endpoint must use HTTPS, or HTTP only through localhost."
        }
        require(host.isNotBlank()) { "The AI endpoint must include a host." }
        require(uri.userInfo.isNullOrBlank()) {
            "Credentials must not be embedded in the AI endpoint URL."
        }
        require(uri.query.isNullOrBlank() && uri.fragment.isNullOrBlank()) {
            "The AI endpoint must not include a query string or fragment."
        }
        if (scheme == "http") {
            require(isLoopback(host)) {
                "Unencrypted HTTP is allowed only for localhost or 127.0.0.1."
            }
        }

        val rawPath = uri.path.orEmpty().trimEnd('/')
        val path = when {
            rawPath.isBlank() -> "/v1/chat/completions"
            rawPath.endsWith("/v1", ignoreCase = true) ->
                "$rawPath/chat/completions"
            rawPath.endsWith("/v1/chat/completions", ignoreCase = true) ->
                rawPath
            else -> rawPath
        }
        return URI(
            scheme,
            null,
            host,
            uri.port,
            path,
            null,
            null
        ).toString()
    }

    fun normalizeOrDefault(
        raw: String,
        defaultEndpoint: String = DEFAULT_LOCAL_ENDPOINT
    ): String = runCatching {
        normalizeOpenAiEndpoint(raw.ifBlank { defaultEndpoint })
    }.getOrElse {
        normalizeOpenAiEndpoint(defaultEndpoint)
    }

    fun isLoopback(host: String): Boolean = when (
        host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    ) {
        "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
        else -> false
    }
}
