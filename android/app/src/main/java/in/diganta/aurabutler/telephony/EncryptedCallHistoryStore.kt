package `in`.diganta.aurabutler.telephony

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small, encrypted metadata-only call history. It never stores call audio or transcripts.
 * The history is intentionally bounded and expires old entries on read/write.
 *
 * Failure policy: a read error is never reported as an empty history, and it never causes
 * the stored data to be overwritten.
 * - If the encrypted value cannot be decrypted, reads and writes throw
 *   [HistoryUnavailableException] and the stored value is left untouched.
 * - If the value decrypts but is not valid JSON, the raw value is copied to a quarantine key
 *   (only when that key is still empty) before the history restarts, so it can be recovered.
 * - Writes are committed synchronously and a failed commit is reported instead of ignored.
 */
@Suppress("DEPRECATION")
class EncryptedCallHistoryStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Stored history exists but cannot be read or saved. Callers must report this, never show it as empty. */
    class HistoryUnavailableException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    @Synchronized
    fun append(record: AuraCallHistoryRecord) {
        val records = readJson()
        records.put(record.toJson())
        val bounded = JSONArray()
        val start = (records.length() - MAX_RECORDS).coerceAtLeast(0)
        for (index in start until records.length()) {
            records.optJSONObject(index)?.let { bounded.put(it) }
        }
        writeJson(removeExpired(bounded))
    }

    @Synchronized
    fun recent(limit: Int = MAX_RECORDS): List<AuraCallHistoryRecord> {
        val current = removeExpired(readJson())
        writeJson(current)
        return (0 until current.length()).mapNotNull { index ->
            runCatching { current.getJSONObject(index).toRecord() }.getOrNull()
        }.sortedByDescending { it.endedAtEpochMs }.take(limit.coerceIn(1, MAX_RECORDS))
    }

    @Synchronized
    fun delete(id: String) {
        val current = readJson()
        val remaining = JSONArray()
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            if (item.optString("id") != id) remaining.put(item)
        }
        writeJson(remaining)
    }

    /** Explicit user action: clears the stored history. */
    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_RECORDS).apply()
    }

    @Synchronized
    fun retentionDays(): Int = preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    @Synchronized
    fun setRetentionDays(days: Int) {
        require(days in SUPPORTED_RETENTION_DAYS) { "Unsupported call-history retention period." }
        val current = readJson()
        preferences.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        writeJson(removeExpired(current))
    }

    /**
     * Returns the stored history. An empty array means nothing is stored yet.
     * Throws [HistoryUnavailableException] when stored data exists but cannot be decrypted.
     */
    private fun readJson(): JSONArray {
        val raw = try {
            preferences.getString(KEY_RECORDS, null)
        } catch (error: Exception) {
            throw HistoryUnavailableException(
                "Encrypted call history could not be decrypted. The stored data was left untouched.",
                error
            )
        }
        if (raw.isNullOrEmpty()) return JSONArray()
        return try {
            JSONArray(raw)
        } catch (error: JSONException) {
            quarantine(raw)
            JSONArray()
        }
    }

    /** Keeps the first unreadable value so it can be recovered; an existing quarantine is never replaced. */
    private fun quarantine(raw: String) {
        if (!preferences.contains(KEY_QUARANTINE)) {
            preferences.edit().putString(KEY_QUARANTINE, raw).apply()
        }
    }

    private fun writeJson(value: JSONArray) {
        val saved = preferences.edit().putString(KEY_RECORDS, value.toString()).commit()
        if (!saved) {
            throw HistoryUnavailableException("Encrypted call history could not be saved.")
        }
    }

    private fun removeExpired(records: JSONArray): JSONArray {
        val retentionDays = preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        val cutoff = System.currentTimeMillis() - retentionDays * MILLIS_PER_DAY
        val retained = JSONArray()
        for (index in 0 until records.length()) {
            val item = records.optJSONObject(index) ?: continue
            if (item.optLong("endedAtEpochMs") >= cutoff) retained.put(item)
        }
        return retained
    }

    private fun AuraCallHistoryRecord.toJson() = JSONObject().apply {
        put("id", id)
        put("phoneNumber", phoneNumber)
        put("incoming", incoming)
        put("startedAtEpochMs", startedAtEpochMs)
        put("endedAtEpochMs", endedAtEpochMs)
        put("connectedAtEpochMs", connectedAtEpochMs)
        put("disconnectCauseCode", disconnectCauseCode)
        put("transport", transport)
        put("contactName", contactName)
        put("languageTag", languageTag)
        put("summary", summary)
        put("transcript", transcript)
    }

    private fun JSONObject.toRecord() = AuraCallHistoryRecord(
        id = getString("id"),
        phoneNumber = optString("phoneNumber").takeIf { it.isNotBlank() && it != "null" },
        incoming = optBoolean("incoming"),
        startedAtEpochMs = optLong("startedAtEpochMs"),
        endedAtEpochMs = optLong("endedAtEpochMs"),
        connectedAtEpochMs = optLong("connectedAtEpochMs"),
        disconnectCauseCode = if (isNull("disconnectCauseCode")) null else optInt("disconnectCauseCode"),
        transport = optString("transport", "CELLULAR").ifBlank { "CELLULAR" },
        contactName = optString("contactName").takeIf { it.isNotBlank() && it != "null" },
        languageTag = optString("languageTag").takeIf { it.isNotBlank() && it != "null" },
        summary = optString("summary").takeIf { it.isNotBlank() && it != "null" },
        transcript = optString("transcript").takeIf { it.isNotBlank() && it != "null" }
    )

    private companion object {
        const val PREFS_NAME = "aura_encrypted_call_history"
        const val KEY_RECORDS = "records_v1"
        const val KEY_QUARANTINE = "records_unreadable_quarantine_v1"
        const val KEY_RETENTION_DAYS = "retention_days_v1"
        const val MAX_RECORDS = 100
        const val DEFAULT_RETENTION_DAYS = 90
        const val MIN_RETENTION_DAYS = 30
        const val MAX_RETENTION_DAYS = 365
        val SUPPORTED_RETENTION_DAYS = setOf(30, 90, 180, 365)
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    }
}
