package `in`.diganta.aurabutler.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted durable backing store for semantic memory. The database contains
 * only row identifiers/timestamps and AES-GCM ciphertext. Embeddings and
 * metadata are encrypted together with the corresponding memory text.
 */
class EncryptedSemanticMemoryPersistence(
    context: Context,
    private val maxEntries: Int = MAX_ENTRIES
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
), SemanticMemoryPersistence {

    private val lock = Any()
    private val cipher = KeystoreCipher()

    init {
        require(maxEntries > 0)
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
            CREATE TABLE semantic_memories (
                id TEXT PRIMARY KEY NOT NULL,
                encrypted_payload TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_accessed INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX semantic_memories_created_idx ON semantic_memories(created_at DESC)")
        db.execSQL("CREATE INDEX semantic_memories_accessed_idx ON semantic_memories(last_accessed ASC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Unsupported semantic-memory database migration: " + oldVersion + " -> " + newVersion
        )
    }

    override fun loadAll(): List<SemanticMemory.MemoryEntry> = synchronized(lock) {
        readableDatabase.query(
            "semantic_memories",
            arrayOf("encrypted_payload"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            maxEntries.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(decodeEntry(cipher.decrypt(cursor.getString(0))))
                }
            }
        }
    }

    override fun upsert(entry: SemanticMemory.MemoryEntry) = synchronized(lock) {
        val values = ContentValues().apply {
            put("id", entry.id)
            put("encrypted_payload", cipher.encrypt(encodeEntry(entry)))
            put("created_at", entry.createdAt)
            put("last_accessed", entry.lastAccessed)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "semantic_memories",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            pruneToCapacity(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(id: String) {
        synchronized(lock) {
            writableDatabase.delete("semantic_memories", "id=?", arrayOf(id))
        }
    }

    override fun clear() {
        synchronized(lock) {
            writableDatabase.delete("semantic_memories", null, null)
        }
    }

    private fun pruneToCapacity(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM semantic_memories", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        val excess = count - maxEntries
        if (excess <= 0) return
        db.execSQL(
            """
            DELETE FROM semantic_memories
            WHERE id IN (
                SELECT id FROM semantic_memories
                ORDER BY last_accessed ASC, created_at ASC
                LIMIT ?
            )
            """.trimIndent(),
            arrayOf(excess)
        )
    }

    private fun encodeEntry(entry: SemanticMemory.MemoryEntry): String {
        val vector = entry.embedding?.let { values ->
            val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            values.forEach(bytes::putFloat)
            Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
        }.orEmpty()
        val relatedEntities = JSONArray().also { array ->
            entry.metadata.relatedEntities.forEach(array::put)
        }
        val temporalTags = JSONArray().also { array ->
            entry.metadata.temporalTags.forEach(array::put)
        }
        val tags = JSONArray().also { array ->
            entry.tags.sorted().forEach(array::put)
        }
        return JSONObject()
            .put("schema", PAYLOAD_SCHEMA)
            .put("id", entry.id)
            .put("content", entry.content)
            .put("label", entry.label)
            .put("embedding", vector)
            .put("metadata", JSONObject()
                .put("context", entry.metadata.context)
                .put("relatedEntities", relatedEntities)
                .put("temporalTags", temporalTags)
                .put("sentiment", entry.metadata.sentiment)
                .put("confidence", entry.metadata.confidence))
            .put("accessCount", entry.accessCount)
            .put("lastAccessed", entry.lastAccessed)
            .put("createdAt", entry.createdAt)
            .put("importance", entry.importance)
            .put("tags", tags)
            .put("source", entry.source.name)
            .toString()
    }

    private fun decodeEntry(plain: String): SemanticMemory.MemoryEntry {
        val json = JSONObject(plain)
        require(json.optInt("schema") == PAYLOAD_SCHEMA) {
            "Unsupported semantic-memory payload schema."
        }
        val encodedVector = json.optString("embedding")
        val embedding = if (encodedVector.isBlank()) {
            null
        } else {
            val bytes = Base64.decode(encodedVector, Base64.NO_WRAP)
            require(bytes.isNotEmpty() && bytes.size % Float.SIZE_BYTES == 0) {
                "Invalid encrypted semantic embedding payload."
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
        }
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        return SemanticMemory.MemoryEntry(
            id = json.getString("id"),
            content = json.getString("content"),
            label = json.getString("label"),
            embedding = embedding,
            metadata = SemanticMemory.MemoryMetadata(
                context = metadata.optString("context"),
                relatedEntities = readStrings(metadata.optJSONArray("relatedEntities")),
                temporalTags = readStrings(metadata.optJSONArray("temporalTags")),
                sentiment = metadata.optDouble("sentiment", 0.0).coerceIn(-1.0, 1.0),
                confidence = metadata.optDouble("confidence", 1.0).coerceIn(0.0, 1.0)
            ),
            accessCount = json.optInt("accessCount", 0).coerceAtLeast(0),
            lastAccessed = json.optLong("lastAccessed", System.currentTimeMillis()),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            importance = json.optDouble("importance", 0.5).coerceIn(0.0, 1.0),
            tags = readStrings(json.optJSONArray("tags")).toSet(),
            source = runCatching {
                SemanticMemory.MemorySource.valueOf(json.optString("source"))
            }.getOrDefault(SemanticMemory.MemorySource.USER_INPUT)
        )
    }

    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_METADATA_ITEMS)) {
                array.optString(index).trim()
                    .takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }

    private class KeystoreCipher {
        private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        }

        fun encrypt(plainText: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            return "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }

        fun decrypt(encoded: String): String {
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == "v1") {
                "Unsupported encrypted semantic-memory record."
            }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val payload = Base64.decode(parts[2], Base64.NO_WRAP)
            require(iv.size == 12 && payload.size >= 16) {
                "Invalid encrypted semantic-memory record."
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
            const val KEY_ALIAS = "aura_semantic_memory_aes_gcm_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_semantic_memory.db"
        const val DATABASE_VERSION = 1
        const val PAYLOAD_SCHEMA = 1
        const val MAX_ENTRIES = 2_000
        const val MAX_METADATA_ITEMS = 64
    }
}
