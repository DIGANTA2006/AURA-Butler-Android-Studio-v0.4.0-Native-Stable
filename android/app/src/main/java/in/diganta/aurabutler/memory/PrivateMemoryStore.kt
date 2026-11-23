package `in`.diganta.aurabutler.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.runtime.SecretRedactor
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private, bounded, searchable on-device memory.
 *
 * Labels and values are encrypted with a non-exportable Android Keystore key.
 * SQLite stores only ciphertext, a one-way deduplication hash, identifiers and
 * timestamps. Search decrypts the bounded local set inside AURA's process.
 */
class PrivateMemoryStore(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {

    private val lock = Any()
    private val cipher = MemoryCipher()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout=2500", null).close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memories (
                id TEXT PRIMARY KEY NOT NULL,
                label_cipher TEXT NOT NULL,
                value_cipher TEXT NOT NULL,
                normalized_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX memories_created_at_idx ON memories(created_at DESC)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // This database name starts at the encrypted schema. Future migrations
        // must be additive and preserve ciphertext.
        if (oldVersion < 1) onCreate(db)
    }

    fun list(limit: Int = MAX_MEMORIES): List<MemoryItem> = synchronized(lock) {
        pruneExpiredLocked()
        listLocked(limit)
    }

    fun replaceAll(items: List<MemoryItem>) = synchronized(lock) {
        val safeItems = items
            .asSequence()
            .filter { it.label.isNotBlank() && it.value.isNotBlank() }
            .filterNot { SecretRedactor.containsLikelySecret("${it.label}\n${it.value}") }
            .distinctBy { normalize(it.label, it.value) }
            .toList()
            .takeLast(MAX_MEMORIES)

        writableDatabase.beginTransaction()
        try {
            val keepIds = safeItems.mapTo(linkedSetOf(), MemoryItem::id)
            if (keepIds.isEmpty()) {
                writableDatabase.delete("memories", null, null)
            } else {
                val placeholders = keepIds.joinToString(",") { "?" }
                writableDatabase.delete(
                    "memories",
                    "id NOT IN ($placeholders)",
                    keepIds.toTypedArray()
                )
            }

            safeItems.forEach(::upsertLocked)
            pruneExpiredLocked()
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun add(item: MemoryItem): Boolean = synchronized(lock) {
        if (
            item.label.isBlank() ||
            item.value.isBlank() ||
            SecretRedactor.containsLikelySecret("${item.label}\n${item.value}")
        ) {
            return@synchronized false
        }
        upsertLocked(item)
        pruneExpiredLocked()
        pruneToCapLocked()
        true
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        writableDatabase.delete(
            "memories",
            "id=?",
            arrayOf(id)
        ) > 0
    }

    fun deleteMatching(query: String, limit: Int = 50): Int = synchronized(lock) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val queryTerms = terms(normalizedQuery)
        if (normalizedQuery.isBlank() || queryTerms.isEmpty()) {
            return@synchronized 0
        }

        val matches = listLocked(MAX_MEMORIES)
            .mapNotNull { item ->
                val searchable = "${item.label} ${item.value}".lowercase(Locale.ROOT)
                val overlap = queryTerms.intersect(terms(searchable)).size
                when {
                    searchable.contains(normalizedQuery) -> 10_000 + overlap to item
                    overlap > 0 -> overlap to item
                    else -> null
                }
            }
            .sortedByDescending { it.first }
            .take(limit.coerceIn(1, 100))
            .map { it.second.id }

        if (matches.isEmpty()) return@synchronized 0
        val placeholders = matches.joinToString(",") { "?" }
        writableDatabase.delete(
            "memories",
            "id IN ($placeholders)",
            matches.toTypedArray()
        )
    }

    fun search(
        query: String,
        limit: Int = 8
    ): List<MemoryItem> = synchronized(lock) {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return@synchronized emptyList()

        pruneExpiredLocked()
        val now = System.currentTimeMillis()
        val queryProfile = HybridRetrievalScorer.prepare(query)
        val scored = listLocked(MAX_MEMORIES).mapNotNull { item ->
            val document = "${item.label} ${item.value}"
            val ageDays = ((now - item.createdAtEpochMs).coerceAtLeast(0L)) / 86_400_000.0
            val score = HybridRetrievalScorer.score(queryProfile, document, ageDays)
            // Keep exact lexical matches and useful fuzzy matches. The threshold
            // deliberately rejects weak coincidental character overlap.
            if (score.lexical <= 0.0 && score.fuzzy < 0.075) return@mapNotNull null
            score.total to item
        }

        scored.sortedByDescending { it.first }
            .take(limit.coerceIn(1, 20))
            .map { it.second }
    }

    private fun listLocked(limit: Int): List<MemoryItem> =
        readableDatabase.query(
            "memories",
            arrayOf("id", "label_cipher", "value_cipher", "created_at"),
            null,
            null,
            null,
            null,
            "created_at ASC",
            limit.coerceIn(1, MAX_MEMORIES).toString()
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val labelIndex = cursor.getColumnIndexOrThrow("label_cipher")
                val valueIndex = cursor.getColumnIndexOrThrow("value_cipher")
                val createdIndex = cursor.getColumnIndexOrThrow("created_at")
                while (cursor.moveToNext()) {
                    val label = cipher.decrypt(cursor.getString(labelIndex))
                    val value = cipher.decrypt(cursor.getString(valueIndex))
                    if (label == null || value == null) continue
                    add(
                        MemoryItem(
                            id = cursor.getString(idIndex),
                            label = label,
                            value = value,
                            createdAtEpochMs = cursor.getLong(createdIndex)
                        )
                    )
                }
            }
        }

    private fun upsertLocked(item: MemoryItem) {
        val safeLabel = SecretRedactor.redact(item.label.trim(), 120)
        val safeValue = SecretRedactor.redact(item.value.trim(), 2_000)
        val values = ContentValues().apply {
            put("id", item.id)
            put("label_cipher", cipher.encrypt(safeLabel))
            put("value_cipher", cipher.encrypt(safeValue))
            put("normalized_hash", sha256(normalize(safeLabel, safeValue)))
            put("created_at", item.createdAtEpochMs)
        }
        writableDatabase.insertWithOnConflict(
            "memories",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun pruneExpiredLocked() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 86_400_000L
        writableDatabase.delete(
            "memories",
            "created_at < ?",
            arrayOf(cutoff.toString())
        )
    }

    private fun pruneToCapLocked() {
        val excess = readableDatabase.rawQuery(
            "SELECT MAX(COUNT(*) - ?, 0) FROM memories",
            arrayOf(MAX_MEMORIES.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        if (excess <= 0) return
        writableDatabase.execSQL(
            """
            DELETE FROM memories
            WHERE id IN (
                SELECT id FROM memories
                ORDER BY created_at ASC
                LIMIT $excess
            )
            """.trimIndent()
        )
    }

    private fun normalize(label: String, value: String): String =
        "${label.trim()}|${value.trim()}"
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .take(2_200)

    private fun terms(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}']+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }
            .take(80)
            .toSet()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class MemoryCipher {
        private val key: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        }

        fun encrypt(plainText: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val payload = Base64.encodeToString(
                cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)),
                Base64.NO_WRAP
            )
            return "v1:$iv:$payload"
        }

        fun decrypt(encoded: String): String? = runCatching {
            if (!encoded.startsWith("v1:")) {
                // Defensive compatibility for a never-released development DB.
                return@runCatching encoded
            }
            val parts = encoded.split(':', limit = 3)
            require(parts.size == 3)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val payload = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        }.getOrNull()

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
                    .build()
            )
            return generator.generateKey()
        }

        private companion object {
            const val KEY_ALIAS = "aura_private_memory_v3"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_private_memory_v3.sqlite3"
        const val DATABASE_VERSION = 1
        const val MAX_MEMORIES = 1_500
        const val RETENTION_DAYS = 365L
    }
}
