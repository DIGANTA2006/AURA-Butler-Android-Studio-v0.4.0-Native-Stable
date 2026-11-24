package `in`.diganta.aurabutler.intelligence

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM encrypted SQLite persistence for proactive suggestions and agent checkpoints. */
class EncryptedProactivePersistence(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), ProactiveSuggestionPersistence {
    private val lock = Any()
    private val cipher = ProactiveCipher()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.rawQuery("PRAGMA busy_timeout=3000", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE suggestions(id TEXT PRIMARY KEY NOT NULL, expires_at INTEGER NOT NULL, encrypted_payload TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX suggestions_expiry_idx ON suggestions(expires_at)")
        db.execSQL(
            "CREATE TABLE agents(id TEXT PRIMARY KEY NOT NULL, next_run INTEGER, encrypted_payload TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX agents_next_run_idx ON agents(next_run)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Unsupported proactive database migration: " + oldVersion + " -> " + newVersion
        )
    }

    override fun loadSuggestions(): List<ProactiveEngine.ProactiveSuggestion> = synchronized(lock) {
        readableDatabase.query(
            "suggestions",
            arrayOf("encrypted_payload"),
            "expires_at>?",
            arrayOf(System.currentTimeMillis().toString()),
            null,
            null,
            "expires_at DESC",
            MAX_SUGGESTIONS.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(decodeSuggestion(cipher.decrypt(cursor.getString(0))))
                }
            }
        }
    }

    override fun saveSuggestion(suggestion: ProactiveEngine.ProactiveSuggestion) {
        synchronized(lock) {
            val plaintext = encodeSuggestion(suggestion).toString()
            require(plaintext.length <= MAX_PAYLOAD_CHARS)
            val values = ContentValues().apply {
                put("id", suggestion.id)
                put("expires_at", suggestion.expiresAt)
                put("encrypted_payload", cipher.encrypt(plaintext))
            }
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.insertWithOnConflict(
                    "suggestions",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                val count = db.rawQuery("SELECT COUNT(*) FROM suggestions", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
                if (count > MAX_SUGGESTIONS) {
                    db.execSQL(
                        """
                        DELETE FROM suggestions
                        WHERE id IN (
                            SELECT id FROM suggestions
                            ORDER BY expires_at ASC
                            LIMIT ?
                        )
                        """.trimIndent(),
                        arrayOf(count - MAX_SUGGESTIONS)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    override fun deleteSuggestion(id: String) {
        synchronized(lock) {
            writableDatabase.delete("suggestions", "id=?", arrayOf(id))
        }
    }

    override fun loadAgentStates(): List<ProactiveEngine.AgentState> = synchronized(lock) {
        readableDatabase.query(
            "agents",
            arrayOf("encrypted_payload"),
            null,
            null,
            null,
            null,
            "next_run ASC",
            MAX_AGENTS.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(decodeAgent(cipher.decrypt(cursor.getString(0))))
                }
            }
        }
    }

    override fun saveAgentState(state: ProactiveEngine.AgentState) {
        synchronized(lock) {
            val values = ContentValues().apply {
                put("id", state.id)
                if (state.nextRun == null) putNull("next_run") else put("next_run", state.nextRun)
                put("encrypted_payload", cipher.encrypt(encodeAgent(state).toString()))
            }
            writableDatabase.insertWithOnConflict(
                "agents",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    private fun encodeSuggestion(value: ProactiveEngine.ProactiveSuggestion): JSONObject =
        JSONObject()
            .put("schema", PAYLOAD_SCHEMA)
            .put("id", value.id)
            .put("type", value.type.name)
            .put("title", value.title)
            .put("description", value.description)
            .put("action", encodeProactiveAction(value.action))
            .put("confidence", value.confidence)
            .put("priority", value.priority.name)
            .put("context", JSONObject()
                .put("trigger", value.context.trigger.name)
                .put("relatedMemoryIds", JSONArray(value.context.relatedMemoryIds.take(MAX_ITEMS)))
                .put("timeRelevance", value.context.timeRelevance.name)
                .putNullableString("locationRelevance", value.context.locationRelevance))
            .put("createdAt", value.createdAt)
            .put("expiresAt", value.expiresAt)
            .put("dismissed", value.dismissed)
            .put("accepted", value.accepted)
            .putNullableObject(
                "pendingApprovalAction",
                value.pendingApprovalAction?.let(::encodeButlerAction)
            )
            .putNullableString("lastActionError", value.lastActionError)

    private fun decodeSuggestion(raw: String): ProactiveEngine.ProactiveSuggestion {
        val json = JSONObject(raw)
        require(json.optInt("schema") == PAYLOAD_SCHEMA)
        val context = json.getJSONObject("context")
        val related = context.optJSONArray("relatedMemoryIds") ?: JSONArray()
        return ProactiveEngine.ProactiveSuggestion(
            id = json.getString("id"),
            type = enumValue(json.getString("type")),
            title = json.getString("title"),
            description = json.getString("description"),
            action = decodeProactiveAction(json.getJSONObject("action")),
            confidence = json.optDouble("confidence").coerceIn(0.0, 1.0),
            priority = enumValue(json.getString("priority")),
            context = ProactiveEngine.SuggestionContext(
                trigger = enumValue(context.getString("trigger")),
                relatedMemoryIds = readStrings(related, MAX_ITEMS),
                timeRelevance = enumValue(context.optString("timeRelevance", "ANYTIME")),
                locationRelevance = if (context.isNull("locationRelevance")) null
                    else context.optString("locationRelevance")
            ),
            createdAt = json.optLong("createdAt"),
            expiresAt = json.optLong("expiresAt"),
            dismissed = json.optBoolean("dismissed"),
            accepted = json.optBoolean("accepted"),
            pendingApprovalAction = if (json.isNull("pendingApprovalAction")) null
                else decodeButlerAction(json.getJSONObject("pendingApprovalAction")),
            lastActionError = if (json.isNull("lastActionError")) null
                else json.optString("lastActionError")
        )
    }

    private fun encodeProactiveAction(action: ProactiveEngine.ProactiveAction): JSONObject =
        JSONObject()
            .put("type", action.type.name)
            .put("payload", anyMapToJson(action.payload))
            .put("requiresConfirmation", action.requiresConfirmation)

    private fun decodeProactiveAction(json: JSONObject) = ProactiveEngine.ProactiveAction(
        type = enumValue(json.getString("type")),
        payload = jsonToAnyMap(json.optJSONObject("payload") ?: JSONObject()),
        requiresConfirmation = json.optBoolean("requiresConfirmation", true)
    )

    private fun encodeButlerAction(action: ButlerAction): JSONObject =
        JSONObject()
            .put("id", action.id)
            .put("kind", action.kind.name)
            .put("title", action.title)
            .put("summary", action.summary)
            .put("risk", action.risk.name)
            .put("payload", stringMapToJson(action.payload))
            .put("createdAtEpochMs", action.createdAtEpochMs)
            .put("expiresAtEpochMs", action.expiresAtEpochMs)

    private fun decodeButlerAction(json: JSONObject) = ButlerAction(
        id = json.getString("id"),
        kind = enumValue<ActionKind>(json.getString("kind")),
        title = json.getString("title"),
        summary = json.getString("summary"),
        risk = enumValue<ActionRisk>(json.getString("risk")),
        payload = jsonToStringMap(json.getJSONObject("payload")),
        createdAtEpochMs = json.getLong("createdAtEpochMs"),
        expiresAtEpochMs = json.getLong("expiresAtEpochMs")
    )

    private fun encodeAgent(state: ProactiveEngine.AgentState): JSONObject =
        JSONObject()
            .put("schema", PAYLOAD_SCHEMA)
            .put("id", state.id)
            .put("enabled", state.enabled)
            .putNullableLong("lastRun", state.lastRun)
            .putNullableLong("nextRun", state.nextRun)
            .put("runCount", state.runCount)
            .put("successCount", state.successCount)
            .put("errorCount", state.errorCount)

    private fun decodeAgent(raw: String): ProactiveEngine.AgentState {
        val json = JSONObject(raw)
        require(json.optInt("schema") == PAYLOAD_SCHEMA)
        return ProactiveEngine.AgentState(
            id = json.getString("id"),
            enabled = json.optBoolean("enabled"),
            lastRun = if (json.isNull("lastRun")) null else json.optLong("lastRun"),
            nextRun = if (json.isNull("nextRun")) null else json.optLong("nextRun"),
            runCount = json.optInt("runCount").coerceAtLeast(0),
            successCount = json.optInt("successCount").coerceAtLeast(0),
            errorCount = json.optInt("errorCount").coerceAtLeast(0)
        )
    }

    private fun anyMapToJson(values: Map<String, Any>): JSONObject =
        JSONObject().also { output ->
            require(values.size <= MAX_ITEMS)
            values.forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 100)
                output.put(key, jsonValue(value))
            }
        }

    private fun jsonValue(value: Any): Any = when (value) {
        is String, is Boolean, is Number -> value
        is Map<*, *> -> JSONObject().also { output ->
            value.forEach { (key, nested) ->
                require(key is String && key.isNotBlank() && nested != null)
                output.put(key, jsonValue(nested))
            }
        }
        is Iterable<*> -> JSONArray().also { output ->
            value.take(MAX_ITEMS).forEach { nested ->
                require(nested != null)
                output.put(jsonValue(nested))
            }
        }
        else -> throw IllegalArgumentException(
            "Unsupported proactive action payload type: " + value.javaClass.name
        )
    }

    private fun jsonToAnyMap(json: JSONObject): Map<String, Any> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = readJsonValue(json.get(key))
            if (value != null) put(key, value)
        }
    }

    private fun readJsonValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonToAnyMap(value)
        is JSONArray -> buildList {
            for (index in 0 until minOf(value.length(), MAX_ITEMS)) {
                readJsonValue(value.get(index))?.let(::add)
            }
        }
        is String, is Boolean, is Number -> value
        else -> throw IllegalArgumentException("Unsupported stored proactive payload value.")
    }

    private fun stringMapToJson(values: Map<String, String>): JSONObject =
        JSONObject().also { output ->
            require(values.size <= MAX_ITEMS)
            values.forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 100)
                output.put(key, value.take(MAX_STRING_CHARS))
            }
        }

    private fun jsonToStringMap(json: JSONObject): Map<String, String> = buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!json.isNull(key)) put(key, json.optString(key).take(MAX_STRING_CHARS))
        }
    }

    private fun readStrings(array: JSONArray, limit: Int): List<String> = buildList {
        for (index in 0 until minOf(array.length(), limit)) {
            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown persisted proactive enum: " + value)

    private fun JSONObject.putNullableString(key: String, value: String?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.putNullableLong(key: String, value: Long?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.putNullableObject(key: String, value: JSONObject?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private class ProactiveCipher {
        private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        }

        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val bytes = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            return "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        fun decrypt(value: String): String {
            val parts = value.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == "v1")
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val payload = Base64.decode(parts[2], Base64.NO_WRAP)
            require(iv.size == 12 && payload.size >= 16)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }

        private fun createKey(): SecretKey {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return generator.generateKey()
        }

        private companion object {
            const val KEY_ALIAS = "aura_proactive_state_aes_gcm_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_proactive_state.db"
        const val DATABASE_VERSION = 1
        const val PAYLOAD_SCHEMA = 1
        const val MAX_SUGGESTIONS = 200
        const val MAX_AGENTS = 100
        const val MAX_ITEMS = 100
        const val MAX_STRING_CHARS = 2_000
        const val MAX_PAYLOAD_CHARS = 100_000
    }
}
