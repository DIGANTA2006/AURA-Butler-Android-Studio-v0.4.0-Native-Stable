package `in`.diganta.aurabutler.planning

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted durable PlanStore backend. Every current plan and its bounded
 * checkpoint history are encrypted as one atomic SQLite row.
 */
class EncryptedPlanStorePersistence(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), PlanStorePersistence, Closeable {

    private val lock = Any()
    private val cipher = PlanCipher()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout=3000", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE plans (
                plan_id TEXT PRIMARY KEY NOT NULL,
                updated_at INTEGER NOT NULL,
                encrypted_state TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX plans_updated_idx ON plans(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Unsupported encrypted plan-store migration: " + oldVersion + " -> " + newVersion
        )
    }

    override fun loadAll(): List<PersistedPlanState> = synchronized(lock) {
        readableDatabase.query(
            "plans",
            arrayOf("encrypted_state"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            MAX_PLANS.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val plaintext = cipher.decrypt(cursor.getString(0))
                    add(decodeState(plaintext))
                }
            }
        }
    }

    override fun save(state: PersistedPlanState) = synchronized(lock) {
        require(state.history.size <= MAX_HISTORY) {
            "The plan checkpoint history exceeded its configured limit."
        }
        val plaintext = encodeState(state)
        require(plaintext.length <= MAX_PAYLOAD_CHARS) {
            "The plan checkpoint exceeded the encrypted storage limit."
        }
        val values = ContentValues().apply {
            put("plan_id", state.current.id)
            put("updated_at", state.current.updatedAt)
            put("encrypted_state", cipher.encrypt(plaintext))
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("plans", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            prunePlans(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(planId: String) {
        synchronized(lock) {
            writableDatabase.delete("plans", "plan_id=?", arrayOf(planId))
        }
    }

    override fun close() {
        synchronized(lock) {
            super.close()
        }
    }

    private fun prunePlans(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM plans", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val excess = count - MAX_PLANS
        if (excess > 0) {
            db.execSQL(
                """
                DELETE FROM plans
                WHERE plan_id IN (
                    SELECT plan_id FROM plans
                    ORDER BY updated_at ASC
                    LIMIT ?
                )
                """.trimIndent(),
                arrayOf(excess)
            )
        }
    }

    private fun encodeState(state: PersistedPlanState): String =
        JSONObject()
            .put("schema", PAYLOAD_SCHEMA)
            .put("current", encodePlan(state.current))
            .put("history", JSONArray().also { array ->
                state.history.forEach { array.put(encodePlan(it)) }
            })
            .toString()

    private fun decodeState(plaintext: String): PersistedPlanState {
        val root = JSONObject(plaintext)
        require(root.optInt("schema") == PAYLOAD_SCHEMA) {
            "Unsupported encrypted plan-store payload schema."
        }
        val current = decodePlan(root.getJSONObject("current"))
        val historyJson = root.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until minOf(historyJson.length(), MAX_HISTORY)) {
                val item = historyJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid plan checkpoint history entry.")
                add(decodePlan(item))
            }
        }
        require(history.all { it.id == current.id }) {
            "A plan checkpoint history contained a different plan identity."
        }
        return PersistedPlanState(current, history)
    }

    private fun encodePlan(plan: TaskPlanner.Plan): JSONObject =
        JSONObject()
            .put("id", plan.id)
            .put("title", plan.title)
            .put("description", plan.description)
            .put("tasks", JSONArray().also { array ->
                plan.tasks.forEach { array.put(encodeTask(it)) }
            })
            .put("status", plan.status.name)
            .put("createdAt", plan.createdAt)
            .put("updatedAt", plan.updatedAt)
            .putNullableLong("completedAt", plan.completedAt)
            .putNullableString("contextSnapshot", plan.contextSnapshot)
            .put("metadata", stringMapToJson(plan.metadata))

    private fun decodePlan(json: JSONObject): TaskPlanner.Plan {
        val status = enumValue<TaskPlanner.PlanStatus>(json.getString("status"))
        val tasksJson = json.optJSONArray("tasks") ?: JSONArray()
        val tasks = buildList {
            for (index in 0 until minOf(tasksJson.length(), MAX_TASKS)) {
                val task = tasksJson.optJSONObject(index)
                    ?: throw IllegalArgumentException("Invalid persisted plan task.")
                add(decodeTask(task))
            }
        }
        return TaskPlanner.Plan(
            id = json.getString("id"),
            title = json.getString("title"),
            description = json.getString("description"),
            tasks = tasks,
            status = status,
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            completedAt = if (json.isNull("completedAt")) null else json.optLong("completedAt"),
            contextSnapshot = if (json.isNull("contextSnapshot")) null else json.optString("contextSnapshot"),
            metadata = json.optJSONObject("metadata")?.let(::jsonToStringMap).orEmpty()
        )
    }

    private fun encodeTask(task: TaskPlanner.TaskNode): JSONObject =
        JSONObject()
            .put("id", task.id)
            .put("title", task.title)
            .put("description", task.description)
            .put("type", task.type.name)
            .put("dependencies", JSONArray(task.dependencies))
            .putNullableString("tool", task.tool)
            .put("toolArguments", anyMapToJson(task.toolArguments))
            .put("status", task.status.name)
            .putNullableString("result", task.result)
            .putNullableString("error", task.error)
            .put("retryCount", task.retryCount)
            .put("maxRetries", task.maxRetries)
            .put("checkpoint", task.checkpoint)
            .put("estimatedDurationMs", task.estimatedDurationMs)
            .put("createdAt", task.createdAt)
            .putNullableLong("startedAt", task.startedAt)
            .putNullableLong("completedAt", task.completedAt)

    private fun decodeTask(json: JSONObject): TaskPlanner.TaskNode {
        val dependencies = json.optJSONArray("dependencies") ?: JSONArray()
        val args = json.optJSONObject("toolArguments")?.let(::jsonToAnyMap).orEmpty()
        return TaskPlanner.TaskNode(
            id = json.getString("id"),
            title = json.getString("title"),
            description = json.getString("description"),
            type = enumValue(json.getString("type")),
            dependencies = buildList {
                for (index in 0 until minOf(dependencies.length(), MAX_TASKS)) {
                    dependencies.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            },
            tool = if (json.isNull("tool")) null else json.optString("tool"),
            toolArguments = args,
            status = enumValue(json.getString("status")),
            result = if (json.isNull("result")) null else json.optString("result"),
            error = if (json.isNull("error")) null else json.optString("error"),
            retryCount = json.optInt("retryCount", 0).coerceIn(0, 20),
            maxRetries = json.optInt("maxRetries", 2).coerceIn(0, 5),
            checkpoint = json.optBoolean("checkpoint", false),
            estimatedDurationMs = json.optLong("estimatedDurationMs", 5_000L).coerceIn(1L, MAX_TASK_DURATION_MS),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            startedAt = if (json.isNull("startedAt")) null else json.optLong("startedAt"),
            completedAt = if (json.isNull("completedAt")) null else json.optLong("completedAt")
        )
    }

    private fun anyMapToJson(values: Map<String, Any>): JSONObject =
        JSONObject().also { target ->
            values.forEach { (key, value) ->
                require(key.isNotBlank() && key.length <= 100) {
                    "A task argument contained an invalid key."
                }
                target.put(key, toJsonValue(value))
            }
        }

    private fun toJsonValue(value: Any): Any = when (value) {
        is String -> value
        is Boolean -> value
        is Byte, is Short, is Int, is Long, is Float, is Double -> value as Number
        is Map<*, *> -> JSONObject().also { target ->
            value.forEach { (key, nested) ->
                require(key is String && key.isNotBlank() && nested != null) {
                    "Task argument objects must have non-empty string keys and non-null values."
                }
                target.put(key, toJsonValue(nested))
            }
        }
        is Iterable<*> -> JSONArray().also { target ->
            value.forEach { nested ->
                require(nested != null) { "Task argument arrays cannot contain null." }
                target.put(toJsonValue(nested))
            }
        }
        else -> throw IllegalArgumentException(
            "Unsupported task argument type: " + value.javaClass.name
        )
    }

    private fun jsonToAnyMap(json: JSONObject): Map<String, Any> =
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = fromJsonValue(json.get(key))
                if (value != null) put(key, value)
            }
        }

    private fun fromJsonValue(value: Any): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonToAnyMap(value)
        is JSONArray -> buildList {
            for (index in 0 until minOf(value.length(), MAX_ARRAY_ITEMS)) {
                fromJsonValue(value.get(index))?.let(::add)
            }
        }
        is Int -> value
        is Long -> value
        is Double -> value
        is Float -> value
        is Number -> value
        is String -> value
        is Boolean -> value
        else -> throw IllegalArgumentException(
            "Unsupported persisted task argument type: " + value.javaClass.name
        )
    }

    private fun stringMapToJson(values: Map<String, String>): JSONObject =
        JSONObject().also { target ->
            values.forEach { (key, value) -> target.put(key, value) }
        }

    private fun jsonToStringMap(json: JSONObject): Map<String, String> =
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!json.isNull(key)) put(key, json.optString(key))
            }
        }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown persisted enum value: " + value)

    private fun JSONObject.putNullableString(key: String, value: String?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private fun JSONObject.putNullableLong(key: String, value: Long?): JSONObject =
        if (value == null) put(key, JSONObject.NULL) else put(key, value)

    private class PlanCipher {
        private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        }

        fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            return "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }

        fun decrypt(encoded: String): String {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == "v1") {
                "The persisted plan has an unsupported encryption version."
            }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val payload = Base64.decode(parts[2], Base64.NO_WRAP)
            require(iv.size == 12 && payload.size >= 16) {
                "The persisted plan has an invalid encrypted payload."
            }
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
            const val KEY_ALIAS = "aura_plan_store_aes_gcm_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_encrypted_plans.db"
        const val DATABASE_VERSION = 1
        const val PAYLOAD_SCHEMA = 1
        const val MAX_PLANS = 250
        const val MAX_HISTORY = 10
        const val MAX_TASKS = 100
        const val MAX_ARRAY_ITEMS = 500
        const val MAX_PAYLOAD_CHARS = 2_000_000
        const val MAX_TASK_DURATION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
