package `in`.diganta.aurabutler.data

import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.MemoryItem
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

/**
 * Bounded Qwen planner used only for multi-step requests.
 *
 * The model proposes deterministic AURA commands; Android still owns execution,
 * permissions and confirmation. This is intentionally a system-level expert router,
 * not a claim that the 8B model itself has been converted into a neural MoE.
 */
class StructuredAgentProvider {
    data class Step(
        val expert: String,
        val command: String,
        val reason: String
    )

    suspend fun plan(
        task: String,
        memories: List<MemoryItem>,
        settings: ButlerSettings
    ): List<Step> = withContext(Dispatchers.IO) {
        check(settings.aiEnabled && settings.localAiEnabled && settings.localAiApiKey.isNotBlank()) {
            "Private Qwen is not configured for multi-step planning."
        }
        val endpoint = plannerEndpoint(settings)
        val budgetLease = StudentBudgetGuard.tryReserve(AiWorkload.AUXILIARY, endpoint)
        if (!budgetLease.allowed) throw IOException(budgetLease.message)

        try {
        val memoryArray = JSONArray()
        memories.take(4).forEach { item ->
            val safe = SecretRedactor.redact("${item.label}: ${item.value}", 1_000)
            if (safe.isNotBlank()) memoryArray.put(safe)
        }
        val body = JSONObject()
            .put("task", SecretRedactor.redact(task, 4_000))
            .put("memories", memoryArray)

        val response = SafeHttpClient.postJsonFollowingTrustedRedirects(
            endpoint = endpoint,
            body = body.toString(),
            bearerToken = settings.localAiApiKey,
            userAgent = USER_AGENT,
            connectTimeoutMs = 20_000,
            readTimeoutMs = 60_000,
            maxResponseBytes = 512 * 1024
        )
        if (response.status !in 200..299) {
            val detail = runCatching { JSONObject(response.body).optString("detail") }
                .getOrDefault("")
            throw IOException(
                when (response.status) {
                    401, 403 -> "Private planner rejected the AURA cloud token."
                    422 -> "That request does not need a multi-step plan."
                    429 -> "Today's small-helper AI budget is exhausted."
                    502, 503, 504 -> "The private planner is warming up or temporarily unavailable."
                    else -> detail.ifBlank { "Private planner returned HTTP ${response.status}." }
                }
            )
        }

        val root = runCatching { JSONObject(response.body) }
            .getOrElse { throw IOException("Private planner returned unreadable data.") }
        val array = root.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (index in 0 until minOf(array.length(), MAX_STEPS)) {
                val item = array.optJSONObject(index) ?: continue
                val command = item.optString("command").trim().take(MAX_COMMAND_CHARS)
                if (command.isBlank() || SecretRedactor.containsSensitive(command)) continue
                add(
                    Step(
                        expert = item.optString("expert", "utility").trim().take(32),
                        command = command,
                        reason = item.optString("reason").trim().take(240)
                    )
                )
            }
        }
        if (steps.size < 2) throw IOException("The planner did not produce a useful multi-step plan.")
        StudentBudgetGuard.commit(budgetLease)
        steps
        } catch (error: Exception) {
            StudentBudgetGuard.release(budgetLease)
            throw error
        }
    }

    private fun plannerEndpoint(settings: ButlerSettings): String {
        val chat = SafeEndpointValidator.normalizeOpenAiEndpoint(settings.localAiEndpoint)
        val uri = URI(chat)
        return URI(uri.scheme, uri.userInfo, uri.host, uri.port, "/v1/agent/plan", null, null)
            .toString()
    }

    companion object {
        private const val MAX_STEPS = 5
        private const val MAX_COMMAND_CHARS = 500
        private const val USER_AGENT = "AURA-Butler-Android/0.17.2"
    }
}
