package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.runtime.ResponseQualityGate
import `in`.diganta.aurabutler.runtime.SafeEndpointValidator
import `in`.diganta.aurabutler.runtime.SecretRedactor
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Private Qwen/Modal or owner-configured OpenAI-compatible reasoning bridge. */
class OpenAiCompatibleProvider : AiProvider {
    override suspend fun answer(
        message: String,
        context: ConversationContext
    ): String? = withContext(Dispatchers.IO) {
        val settings = context.settings
        if (!settings.aiEnabled || !settings.localAiEnabled) return@withContext null
        val endpoint = SafeEndpointValidator.normalizeOpenAiEndpoint(settings.localAiEndpoint)
        val model = settings.localAiModel.trim().ifBlank { DEFAULT_MODEL }
        try {
            execute(endpoint, model, message, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IllegalArgumentException) {
            throw IOException(error.message ?: "The private AI endpoint is unsafe.")
        }
    }

    override suspend fun answerStreaming(
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val settings = context.settings
        if (!settings.aiEnabled || !settings.localAiEnabled) return@withContext null
        val endpoint = SafeEndpointValidator.normalizeOpenAiEndpoint(settings.localAiEndpoint)
        val model = settings.localAiModel.trim().ifBlank { DEFAULT_MODEL }
        val detailed = ButlerSystemPrompt.normalizeStyle(settings.responseStyle) == "detailed"
        // Complex engineering turns use Qwen's hidden thinking mode and a larger
        // completion budget. Simple chat keeps low-latency SSE streaming.
        if (
            detailed ||
            isComplexEngineeringRequest(message)
        ) {
            return@withContext execute(endpoint, model, message, context)
        }

        var emittedVisibleText = false
        try {
            executeStreaming(endpoint, model, message, context) { delta ->
                if (delta.isNotBlank()) emittedVisibleText = true
                onDelta(delta)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A fallback retry is safe only before any visible SSE text has
            // reached the user. Retrying after partial output can duplicate
            // compute, budget and assistant text.
            if (emittedVisibleText) {
                throw IOException(
                    "The private AI stream was interrupted after partial output. " +
                        "Please retry this turn instead of duplicating it.",
                    error
                )
            }
            execute(endpoint, model, message, context)
        }
    }

    private fun execute(
        endpoint: String,
        model: String,
        message: String,
        context: ConversationContext
    ): String {
        val body = requestBody(model, message, context, stream = false).toString()
        val response = SafeHttpClient.postJsonFollowingTrustedRedirects(
            endpoint = endpoint,
            body = body,
            bearerToken = context.settings.localAiApiKey,
            userAgent = USER_AGENT,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            maxResponseBytes = MAX_RESPONSE_BYTES
        )
        val status = response.status
        val responseText = response.body
        if (status !in 200..299) throw statusError(status, responseText)
        val content = runCatching {
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse {
            throw IOException("The private AI server returned an unreadable response.")
        }
        return ResponseQualityGate.forDisplay(content, context.settings.responseStyle)
            .takeIf(String::isNotBlank)
            ?: throw IOException("The private AI server returned an empty answer.")
    }

    private fun executeStreaming(
        endpoint: String,
        model: String,
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String {
        val body = requestBody(model, message, context, stream = true).toString()
        val connection = SafeHttpClient.open(endpoint).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer ${context.settings.localAiApiKey.trim()}")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            // A cold Modal container can return its long-running result redirect
            // before SSE begins. The normal hardened path knows how to follow it.
            if (status == HttpURLConnection.HTTP_SEE_OTHER) {
                return execute(endpoint, model, message, context)
            }
            if (status !in 200..299) {
                val errorText = SafeHttpClient.readUtf8(connection.errorStream, MAX_RESPONSE_BYTES)
                throw statusError(status, errorText)
            }

            val answer = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")
                    if (delta.isBlank()) continue
                    if (answer.length + delta.length > MAX_STREAM_CHARS) {
                        throw IOException("The private AI stream exceeded AURA's safety limit.")
                    }
                    answer.append(delta)
                    onDelta(delta)
                }
            }
            return ResponseQualityGate.forDisplay(answer.toString(), context.settings.responseStyle)
                .takeIf(String::isNotBlank)
                ?: throw IOException("The private AI server returned an empty streamed answer.")
        } finally {
            connection.disconnect()
        }
    }

    private fun statusError(status: Int, responseText: String): IOException {
        val detail = runCatching {
            JSONObject(responseText).optJSONObject("error")
                ?.optString("message")
                ?.ifBlank { JSONObject(responseText).optString("detail") }
        }.getOrNull().orEmpty()
        return IOException(
            when (status) {
                401, 403 -> "The private AI server rejected its access token."
                404 -> "The configured private AI model or endpoint was not found."
                408, 504 -> "The private AI server is still warming up or timed out. Try again shortly."
                429 -> "The private AI server is busy or today's AI budget is exhausted."
                502, 503 -> "The private AI server is temporarily starting or unavailable."
                else -> detail.ifBlank { "The private AI server returned HTTP $status." }
                    .let { SecretRedactor.redact(it, 320) }
            }
        )
    }

    private fun requestBody(
        model: String,
        message: String,
        context: ConversationContext,
        stream: Boolean
    ): JSONObject {
        val engineering = isEngineeringRequest(message)
        val complexEngineering = isComplexEngineeringRequest(message)
        val messages = JSONArray().put(
            JSONObject()
                .put("role", "system")
                .put("content", ButlerSystemPrompt.build(context, localProvider = true))
        )

        if (ButlerSystemPrompt.historyAllowed(context, localProvider = true)) {
            val historyLimit = if (engineering) 10 else 6
            context.recentMessages.takeLast(historyLimit).forEach { item ->
                messages.put(
                    JSONObject()
                        .put("role", if (item.sender == Sender.USER) "user" else "assistant")
                        .put("content", SecretRedactor.redact(item.text, 50_000))
                )
            }
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", SecretRedactor.redact(message, 100_000))
        )

        val style = ButlerSystemPrompt.normalizeStyle(context.settings.responseStyle)
        val maxTokens = when {
            complexEngineering -> 32_768
            engineering -> 16_384
            style == "detailed" -> 16_384
            style == "concise" -> 4_096
            else -> 8_192
        }

        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", if (engineering) 0.12 else 0.20)
            .put("stream", stream)
            .put(
                "enable_thinking",
                !stream && (complexEngineering || style == "detailed")
            )
            .put("max_tokens", maxTokens)
            .put(
                "aura_mode",
                when {
                    complexEngineering -> "engineering-review"
                    engineering -> "engineering"
                    else -> "chat"
                }
            )
    }

    private fun isEngineeringRequest(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return ENGINEERING_TERMS.any(value::contains) ||
            message.contains("```") ||
            Regex("""(?m)^\s*(?:package|import|class|interface|fun|def|function|#include|using)\b""")
                .containsMatchIn(message)
    }

    private fun isComplexEngineeringRequest(message: String): Boolean {
        if (!isEngineeringRequest(message)) return false
        val value = message.lowercase(Locale.ROOT)
        return message.length >= 320 ||
            COMPLEX_ENGINEERING_TERMS.any(value::contains) ||
            message.count { it == '\n' } >= 4
    }

    companion object {
        const val DEFAULT_MODEL = "Qwen/Qwen3-8B"
        private const val USER_AGENT = "AURA-Butler-Android/0.17.2"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_STREAM_CHARS = 500_000

        private val ENGINEERING_TERMS = setOf(
            "code", "program", "programming", "debug", "compile", "build error",
            "stack trace", "exception", "kotlin", "java", "python", "powershell",
            "javascript", "typescript", "android", "gradle", "docker", "sql",
            "html", "css", "api", "function", "class", "algorithm", "data structure",
            "repository", "project", "source file", "script"
        )

        private val COMPLEX_ENGINEERING_TERMS = setOf(
            "architecture", "production", "full project", "complete code",
            "audit", "loophole", "root cause", "refactor", "multi file",
            "multiple files", "entire project", "fix this project", "analyze this project",
            "implement", "migration", "security review", "performance"
        )
    }
}
