package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.runtime.AiWorkload
import `in`.diganta.aurabutler.runtime.ResponseQualityGate
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.io.IOException
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Optional Gemini provider with grounded research fallback. */
class GeminiAiProvider : AiProvider {
    override suspend fun answer(
        message: String,
        context: ConversationContext
    ): String? = withContext(Dispatchers.IO) {
        val settings = context.settings
        if (!settings.aiEnabled) return@withContext null
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) return@withContext null

        val model = settings.geminiModel.trim().ifBlank { MODEL_FAST }
        val endpoint = "$ENDPOINT_PREFIX$model:generateContent?key=$key"

        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.TEXT, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)

        try {
            val result = execute(endpoint, model, message, context)
            StudentBudgetGuard.commit(budgetLease)
            return@withContext result
        } catch (cancelled: CancellationException) {
            StudentBudgetGuard.release(budgetLease)
            throw cancelled
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            throw error
        }
    }

    override suspend fun answerStreaming(
        message: String,
        context: ConversationContext,
        onDelta: (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val settings = context.settings
        if (!settings.aiEnabled) return@withContext null
        val key = settings.geminiApiKey.trim()
        if (key.isBlank()) return@withContext null

        val model = settings.geminiModel.trim().ifBlank { MODEL_FAST }
        val endpoint = "$ENDPOINT_PREFIX$model:streamGenerateContent?key=$key&alt=sse"

        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.TEXT, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)

        var emittedVisibleText = false
        try {
            val result = executeStreaming(endpoint, model, message, context) { delta ->
                if (delta.isNotBlank()) emittedVisibleText = true
                onDelta(delta)
            }
            StudentBudgetGuard.commit(budgetLease)
            return@withContext result
        } catch (cancelled: CancellationException) {
            StudentBudgetGuard.release(budgetLease)
            throw cancelled
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            if (emittedVisibleText) {
                throw IOException(
                    "The Gemini stream was interrupted after partial output. " +
                        "Please retry this turn instead of duplicating it.",
                    error
                )
            }
            throw error
        }
    }

    private fun execute(
        endpoint: String,
        model: String,
        message: String,
        context: ConversationContext
    ): String {
        val body = requestBody(model, message, context, stream = false).toString()
        val response = SafeHttpClient.postJson(
            endpoint = endpoint,
            body = body,
            userAgent = USER_AGENT,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            maxResponseBytes = MAX_RESPONSE_BYTES
        )
        val status = response.status
        val responseText = response.body
        if (status !in 200..299) throw statusError(status, responseText, model)
        val content = runCatching {
            JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }.getOrElse {
            throw IOException("Gemini returned an unreadable response.")
        }
        return ResponseQualityGate.forDisplay(content, context.settings.responseStyle)
            .takeIf(String::isNotBlank)
            ?: throw IOException("Gemini returned an empty answer.")
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
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val errorText = SafeHttpClient.readUtf8(connection.errorStream, MAX_RESPONSE_BYTES)
                throw statusError(status, errorText, model)
            }

            val answer = StringBuilder()
            java.io.BufferedReader(
                java.io.InputStreamReader(connection.inputStream, java.nio.charset.StandardCharsets.UTF_8)
            ).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload)
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.getJSONObject(0)
                            ?.optString("text")
                            .orEmpty()
                    }.getOrDefault("")
                    if (delta.isBlank()) continue
                    if (answer.length + delta.length > MAX_STREAM_CHARS) {
                        throw IOException("The Gemini stream exceeded AURA's safety limit.")
                    }
                    answer.append(delta)
                    onDelta(delta)
                }
            }
            return ResponseQualityGate.forDisplay(answer.toString(), context.settings.responseStyle)
                .takeIf(String::isNotBlank)
                ?: throw IOException("Gemini returned an empty streamed answer.")
        } finally {
            connection.disconnect()
        }
    }

    private fun statusError(status: Int, responseText: String, model: String): IOException {
        val detail = runCatching {
            JSONObject(responseText).optJSONObject("error")
                ?.optString("message")
                ?.ifBlank { JSONObject(responseText).optString("detail") }
        }.getOrNull().orEmpty()
        return IOException(
            when (status) {
                400 -> if (detail.contains("not found", true) || detail.contains("does not exist", true)) {
                    "Gemini model '$model' is not available. Try the Fast model."
                } else {
                    "Gemini request was invalid: ${SecretRedactor.redact(detail, 320)}"
                }
                401, 403 -> "The Gemini API key was rejected. Check the key and project quota."
                404 -> "The Gemini endpoint was not found. The model may not be available."
                408, 504 -> "Gemini timed out. Try again shortly."
                429 -> "Gemini quota exhausted or rate limited. Try again later."
                502, 503 -> "Gemini is temporarily unavailable. Try again shortly."
                else -> detail.ifBlank { "Gemini returned HTTP $status." }
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
                .put("content", ButlerSystemPrompt.build(context, localProvider = false))
        )

        if (ButlerSystemPrompt.historyAllowed(context, localProvider = false)) {
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

        val genConfig = JSONObject()
            .put("temperature", if (engineering) 0.12 else 0.20)
            .put("maxOutputTokens", maxTokens)
            .put("topP", 0.95)
            .put("topK", 40)

        return JSONObject()
            .put("contents", messages)
            .put("generationConfig", genConfig)
            .put("safetySettings", safetySettings())
    }

    private fun safetySettings(): JSONArray = JSONArray().apply {
        put(JSONObject().put("category", "HARM_CATEGORY_HARASSMENT").put("threshold", "BLOCK_ONLY_HIGH"))
        put(JSONObject().put("category", "HARM_CATEGORY_HATE_SPEECH").put("threshold", "BLOCK_ONLY_HIGH"))
        put(JSONObject().put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT").put("threshold", "BLOCK_ONLY_HIGH"))
        put(JSONObject().put("category", "HARM_CATEGORY_DANGEROUS_CONTENT").put("threshold", "BLOCK_ONLY_HIGH"))
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

    suspend fun groundedResearch(
        query: String,
        settings: ButlerSettings,
        detailed: Boolean
    ): ResearchResult {
        val key = settings.geminiApiKey.trim()
        require(key.isNotBlank()) { "Gemini API key is required for grounded research." }
        val model = settings.geminiModel.trim().ifBlank { MODEL_FAST }
        val endpoint = "$ENDPOINT_PREFIX$model:generateContent?key=$key"

        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.RESEARCH, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)

        try {
            val system = """
                ${AuraIdentity.systemIdentity()}
                You are AURA's grounded research module using Google Search.
                Research the query thoroughly. Use the search tool to find current, reliable sources.
                Cite sources inline as [S1], [S2], etc. Provide: what happened, why it matters,
                important uncertainty, what to watch next, and a short Bottom line.
                ${ButlerSystemPrompt.languageInstruction(settings.speechLanguage)}
            """.trimIndent()

            val body = JSONObject()
                .put("contents", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", query))
                )
                .put("generationConfig", JSONObject()
                    .put("temperature", 0.1)
                    .put("maxOutputTokens", if (detailed) 32_768 else 16_384)
                    .put("topP", 0.95)
                    .put("topK", 40)
                )
                .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                .put("safetySettings", safetySettings())

            val response = SafeHttpClient.postJson(
                endpoint = endpoint,
                body = body.toString(),
                userAgent = USER_AGENT,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = RESEARCH_READ_TIMEOUT_MS,
                maxResponseBytes = MAX_RESPONSE_BYTES
            )
            val status = response.status
            val responseText = response.body
            if (status !in 200..299) throw statusError(status, responseText, model)
            val content = runCatching {
                JSONObject(responseText)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }.getOrElse {
                throw IOException("Gemini grounded research returned an unreadable response.")
            }
            StudentBudgetGuard.commit(budgetLease)
            return ResearchResult(content, "gemini-grounded")
        } catch (cancelled: CancellationException) {
            StudentBudgetGuard.release(budgetLease)
            throw cancelled
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            throw error
        }
    }

    data class ResearchResult(
        val answer: String,
        val provider: String
    ) {
        fun formatted(): String = answer
    }

    companion object {
        const val MODEL_FAST = "gemini-3.5-flash-lite"
        const val MODEL_QUALITY = "gemini-2.5-pro"
        private const val ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 35_000
        private const val RESEARCH_READ_TIMEOUT_MS = 90_000
        private const val MAX_ATTEMPTS_PER_MODEL = 2
        private const val DEFAULT_RETRY_DELAY_MS = 1_200L
        private const val MAX_RETRY_DELAY_MS = 5_000L
        private const val FAILURE_THRESHOLD = 3
        private const val RECOVERY_WINDOW_MS = 30_000L
        private const val MAX_HISTORY_MESSAGES = 12
        private const val MAX_MEMORIES = 8
        private const val MAX_MESSAGE_CHARACTERS = 100_000
        private const val MAX_SYSTEM_PROMPT_CHARACTERS = 50_000
        private const val MAX_REPLY_CHARACTERS = 200_000
        private const val MAX_STREAM_CHARS = 500_000
        private const val USER_AGENT = "AURA-Butler-Android/0.17.2"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

        private val ENGINEERING_TERMS = setOf(
            "code", "program", "debug", "compile", "build error", "stack trace",
            "exception", "kotlin", "java", "python", "powershell", "javascript",
            "typescript", "android", "gradle", "docker", "sql", "api",
            "architecture", "repository", "project", "source file", "script"
        )

        private val COMPLEX_ENGINEERING_TERMS = setOf(
            "architecture", "production", "full project", "complete code",
            "audit", "loophole", "root cause", "refactor", "multi file",
            "multiple files", "entire project", "fix this project", "analyze this project",
            "implement", "migration", "security review", "performance"
        )
    }
}
