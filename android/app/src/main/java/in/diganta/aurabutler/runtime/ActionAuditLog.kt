package `in`.diganta.aurabutler.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ActionAuditLog(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val directory = File(appContext.filesDir, "audit")
    private val file = File(directory, "aura-actions.jsonl")
    private val rotated = File(directory, "aura-actions.previous.jsonl")

    suspend fun record(
        event: String,
        fields: Map<String, Any?> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("time", Instant.now().toString())
            .put("event", event.take(80))
            .put("fields", JSONObject(SecretRedactor.redactMap(fields)))

        synchronized(lock) {
            directory.mkdirs()
            rotateIfNeeded()
            FileOutputStream(file, true).use { output ->
                output.write(
                    (payload.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                )
            }
        }
    }

    fun recent(limit: Int = 25): List<String> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        file.readLines()
            .takeLast(limit.coerceIn(1, 100))
    }

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        if (rotated.exists()) rotated.delete()
        file.renameTo(rotated)
    }

    private companion object {
        const val MAX_BYTES = 1_500_000L
    }
}
