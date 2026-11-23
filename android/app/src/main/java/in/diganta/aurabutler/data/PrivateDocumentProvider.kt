package `in`.diganta.aurabutler.data

import android.util.Base64
import `in`.diganta.aurabutler.document.DocumentInputReader
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.runtime.AiWorkload
import `in`.diganta.aurabutler.runtime.SafeEndpointValidator
import `in`.diganta.aurabutler.runtime.SecretRedactor
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Sends an explicitly selected document only to the owner's private Qwen endpoint. */
class PrivateDocumentProvider {
    data class Result(
        val answer: String,
        val chunks: List<String>,
        val pageCount: Int,
        val extractedChars: Int
    )

    suspend fun analyze(
        document: DocumentInputReader.PreparedDocument,
        prompt: String,
        settings: ButlerSettings
    ): Result = withContext(Dispatchers.IO) {
        check(settings.aiEnabled && settings.localAiEnabled && settings.localAiApiKey.isNotBlank()) {
            "Enable private Qwen and add your AURA cloud token before analyzing documents."
        }
        require(document.bytes.size <= DocumentInputReader.MAX_BYTES)
        val endpoint = documentEndpoint(settings)
        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.TEXT, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)

        try {
        val body = JSONObject()
            .put("filename", document.displayName)
            .put("mime_type", document.mimeType)
            .put("data_base64", Base64.encodeToString(document.bytes, Base64.NO_WRAP))
            .put(
                "prompt",
                SecretRedactor.redact(
                    prompt.ifBlank {
                        if (document.mimeType == "application/zip" || document.mimeType == DocumentInputReader.PROJECT_SNAPSHOT_MIME) {
                            "Perform a repository-aware engineering audit. Use __AURA__/PROJECT_MAP.md only as deterministic navigation metadata, then verify conclusions against the actual source excerpts. Trace entrypoints, interfaces, call sites, configuration and tests. Separate VERIFIED DEFECTS from hypotheses. For each high-value defect give the root cause, affected files, smallest coherent patch, compatibility risks and exact build/test verification commands. Never claim execution unless evidence proves it."
                        } else {
                            "Summarize this document, explain the important points, and identify dates, tasks, warnings, code errors or decisions that matter."
                        }
                    },
                    4_000
                )
            )

        val response = SafeHttpClient.postJsonFollowingTrustedRedirects(
            endpoint = endpoint,
            body = body.toString(),
            bearerToken = settings.localAiApiKey,
            userAgent = "AURA-Butler-Android/0.17.2",
            connectTimeoutMs = 20_000,
            readTimeoutMs = 120_000,
            maxResponseBytes = 3 * 1024 * 1024
        )
        if (response.status !in 200..299) {
            val detail = runCatching {
                JSONObject(response.body).optString("detail")
            }.getOrDefault("")
            throw IOException(
                when (response.status) {
                    401, 403 -> "Private document analysis rejected the AURA cloud token."
                    413 -> "The document is too large for private analysis."
                    415, 422 -> detail.ifBlank { "That document format could not be extracted." }
                    429 -> "Today's private AI budget is exhausted or the server is busy."
                    502, 503, 504 -> "Private document analysis is warming up or temporarily unavailable."
                    else -> detail.ifBlank { "Private document analysis returned HTTP ${response.status}." }
                }
            )
        }
        val root = runCatching { JSONObject(response.body) }
            .getOrElse { throw IOException("Private document analysis returned unreadable data.") }
        val answer = root.optString("answer").trim()
        if (answer.isBlank()) throw IOException("Private document analysis returned an empty answer.")
        val chunksJson = root.optJSONArray("chunks") ?: JSONArray()
        val chunks = buildList {
            for (index in 0 until minOf(chunksJson.length(), 10)) {
                chunksJson.optString(index).trim().takeIf(String::isNotBlank)?.let {
                    add(it.take(1_800))
                }
            }
        }
        val result = Result(
            answer = answer.take(32_000),
            chunks = chunks,
            pageCount = root.optInt("page_count", 0).coerceAtLeast(0),
            extractedChars = root.optInt("extracted_chars", 0).coerceAtLeast(0)
        )
        StudentBudgetGuard.commit(budgetLease)
        result
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            throw error
        }
    }

    private fun documentEndpoint(settings: ButlerSettings): String {
        val chat = SafeEndpointValidator.normalizeOpenAiEndpoint(settings.localAiEndpoint)
        val uri = URI(chat)
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port, "/v1/documents/analyze", null, null)
            .toString()
    }
}
