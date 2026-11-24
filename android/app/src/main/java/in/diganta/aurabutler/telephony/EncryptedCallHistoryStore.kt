package `in`.diganta.aurabutler.telephony

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, encrypted metadata-only call history. It never stores call audio or transcripts.
 * The history is intentionally bounded and expires old entries on read/write.
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

    @Synchronized
    fun append(record: AuraCallHistoryRecord) {
        val records = readJson().apply {
            put(record.toJson())
        }
        val bounded = JSONArray()
        val start = (records.length() - MAX_RECORDS).coerceAtLeast(0)
        for (index in start until records.length()) bounded.put(records.getJSONObject(index))
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

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_RECORDS).apply()
    }

    @Synchronized
    fun retentionDays(): Int = preferences.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)

    @Synchronized
    fun setRetentionDays(days: Int) {
        require(days in SUPPORTED_RETENTION_DAYS) { "Unsupported call-history retention period." }
        preferences.edit().putInt(KEY_RETENTION_DAYS, days).apply()
        writeJson(removeExpired(readJson()))
    }

    private fun readJson(): JSONArray = runCatching {
        JSONArray(preferences.getString(KEY_RECORDS, "[]") ?: "[]")
    }.getOrElse {
        // A malformed record must not crash call handling; callers can clear history to recover.
        JSONArray()
    }

    private fun writeJson(value: JSONArray) {
        preferences.edit().putString(KEY_RECORDS, value.toString()).apply()
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
        const val KEY_RETENTION_DAYS = "retention_days_v1"
        const val MAX_RECORDS = 100
        const val DEFAULT_RETENTION_DAYS = 90
        const val MIN_RETENTION_DAYS = 30
        const val MAX_RETENTION_DAYS = 365
        val SUPPORTED_RETENTION_DAYS = setOf(30, 90, 180, 365)
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    }
}
