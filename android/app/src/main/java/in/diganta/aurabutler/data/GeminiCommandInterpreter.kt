package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.domain.CommandNormalizer
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.AiWorkload
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CommandRepair(
    val correctedCommand: String,
    val mediaQuery: String = "",
    val confidence: Double = 0.0
)

/**
 * Optional Gemini command repair for short, action-like requests.
 *
 * It never executes an action. It only returns a narrowly corrected command
 * which is passed back through AURA's deterministic parser and safety policy.
 */
class GeminiCommandInterpreter {
    suspend fun repairCommand(
        raw: String,
        settings: ButlerSettings,
        mediaOnly: Boolean = false
    ): CommandRepair? = withContext(Dispatchers.IO) {
        val command = raw.trim()
        val key = settings.geminiApiKey.trim()

        if (
            !settings.aiEnabled ||
            !settings.aiCommandRepairEnabled ||
            !settings.smartCommandCorrection ||
            !HybridAiProvider.publicProviderAllowed(settings, HybridAiProvider.PROVIDER_GEMINI) ||
            key.isBlank() ||
            command.isBlank() ||
            command.length > MAX_COMMAND_CHARS ||
            SecretRedactor.containsSensitive(command) ||
            !CommandNormalizer.isLikelyCommand(command)
        ) {
            return@withContext null
        }

        val budgetLease = StudentBudgetGuard.tryReserve(
            AiWorkload.AUXILIARY,
            "https://generativelanguage.googleapis.com"
        )
        if (!budgetLease.allowed) return@withContext null

        val model = normalizeModel(settings.geminiModel)
        val endpoint = "$ENDPOINT_PREFIX$model:generateContent"
        val connection = (
            URL(endpoint).openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("x-goog-api-key", key)
            setRequestProperty(
                "Content-Type",
                "application/json; charset=utf-8"
            )
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "AURA-Butler-Android/0.17.2"
            )
        }

        try {
            val body = requestBody(command, mediaOnly)
            connection.outputStream.use { output ->
                output.write(
                    body.toString().toByteArray(StandardCharsets.UTF_8)
                )
            }

            val status = connection.responseCode
            val responseText = (
                if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                ).let { SafeHttpClient.readUtf8(it, 1024 * 1024) }

            if (status !in 200..299) {
                StudentBudgetGuard.release(budgetLease)
                return@withContext null
            }

            val candidateText = JSONObject(responseText)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                .orEmpty()

            val parsed = parseJsonObject(candidateText)
                ?: run {
                    StudentBudgetGuard.release(budgetLease)
                    return@withContext null
                }
            val corrected = parsed
                .optString("correctedCommand")
                .trim()
                .replace(Regex("""\s+"""), " ")
                .take(MAX_COMMAND_CHARS)
            val mediaQuery = parsed
                .optString("mediaQuery")
                .trim()
                .replace(Regex("""\s+"""), " ")
                .take(MAX_MEDIA_QUERY_CHARS)
            val confidence = parsed
                .optDouble("confidence", 0.0)
                .coerceIn(0.0, 1.0)

            if (
                corrected.isBlank() ||
                confidence < MIN_CONFIDENCE ||
                !CommandNormalizer.isLikelyCommand(corrected)
            ) {
                StudentBudgetGuard.release(budgetLease)
                return@withContext null
            }

            StudentBudgetGuard.commit(budgetLease)
            CommandRepair(
                correctedCommand = corrected,
                mediaQuery = mediaQuery,
                confidence = confidence
            )
        } catch (_: Exception) {
            StudentBudgetGuard.release(budgetLease)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBody(
        command: String,
        mediaOnly: Boolean
    ): JSONObject {
        val mode = if (mediaOnly) {
            """
                This is a music or video playback command. Correct only obvious
                transcription or spelling mistakes in the requested title,
                artist, language, or platform. Examples:
                "play pasuri" becomes "play Pasoori on YouTube" with mediaQuery
                "Pasoori". "play kesaria" becomes "play Kesariya on YouTube"
                with mediaQuery "Kesariya".
            """.trimIndent()
        } else {
            """
                This is a short Android-assistant command. Correct only obvious
                speech-to-text or spelling errors while preserving the user's
                intended action, names, numbers, language, and destination.
            """.trimIndent()
        }

        val prompt = """
            You repair commands for a private Android assistant named AURA.
            $mode

            Rules:
            - Never invent a new action, recipient, number, URL, app, or message.
            - Never add a purchase, payment, deletion, call, send, or share action.
            - Preserve ambiguous proper names instead of guessing.
            - Use YouTube only when the user asked to play music or video without
              naming another playback service.
            - Return only JSON matching the requested schema.
            - confidence is a number from 0 to 1.

            Original command:
            ${SecretRedactor.redact(command, MAX_COMMAND_CHARS)}
        """.trimIndent()

        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "correctedCommand",
                        JSONObject().put("type", "STRING")
                    )
                    .put(
                        "mediaQuery",
                        JSONObject().put("type", "STRING")
                    )
                    .put(
                        "confidence",
                        JSONObject().put("type", "NUMBER")
                    )
            )
            .put(
                "required",
                JSONArray()
                    .put("correctedCommand")
                    .put("mediaQuery")
                    .put("confidence")
            )

        return JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", prompt)
                            )
                        )
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0)
                    .put("maxOutputTokens", 220)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
            )
    }

    private fun parseJsonObject(value: String): JSONObject? {
        val cleaned = value
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching { JSONObject(cleaned) }.getOrNull()
    }

    private fun normalizeModel(value: String): String = when (value.trim()) {
        GeminiAiProvider.MODEL_QUALITY -> GeminiAiProvider.MODEL_QUALITY
        else -> GeminiAiProvider.MODEL_FAST
    }

    private companion object {
        const val ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_COMMAND_CHARS = 280
        const val MAX_MEDIA_QUERY_CHARS = 180
        const val MIN_CONFIDENCE = 0.68
    }
}
