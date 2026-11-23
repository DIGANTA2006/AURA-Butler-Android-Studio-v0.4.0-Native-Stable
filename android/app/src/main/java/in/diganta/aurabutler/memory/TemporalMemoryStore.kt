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
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local temporal fact history.
 *
 * The existing PrivateMemoryStore remains the fast current-fact store. This
 * database records when a label changed so AURA can answer questions such as
 * "what did I prefer before?" without deleting the old state.
 */
class TemporalMemoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val lock = Any()
    private val cipher = TemporalCipher()

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
            CREATE TABLE fact_versions (
                id TEXT PRIMARY KEY NOT NULL,
                label_hash TEXT NOT NULL,
                label_cipher TEXT NOT NULL,
                value_hash TEXT NOT NULL,
                value_cipher TEXT NOT NULL,
                source TEXT NOT NULL,
                confidence REAL NOT NULL,
                valid_from INTEGER NOT NULL,
                valid_to INTEGER,
                is_current INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX fact_versions_label_idx ON fact_versions(label_hash, valid_from DESC)"
        )
        db.execSQL(
            "CREATE INDEX fact_versions_current_idx ON fact_versions(is_current, valid_from DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun synchronize(
        items: List<MemoryItem>,
        source: String = "conversation",
        confidence: Double = 0.85
    ) = synchronized(lock) {
        items
            .asSequence()
            .filter { it.label.isNotBlank() && it.value.isNotBlank() }
            .filterNot { SecretRedactor.containsLikelySecret("${it.label}\n${it.value}") }
            .take(MAX_FACTS_PER_SYNC)
            .forEach { recordLocked(it, source, confidence) }
        pruneLocked()
    }

    fun record(
        item: MemoryItem,
        source: String = "conversation",
        confidence: Double = 0.85
    ): Boolean = synchronized(lock) {
        if (
            item.label.isBlank() ||
            item.value.isBlank() ||
            SecretRedactor.containsLikelySecret("${item.label}\n${item.value}")
        ) {
            return@synchronized false
        }
        val changed = recordLocked(item, source, confidence)
        pruneLocked()
        changed
    }

    fun history(query: String, limit: Int = 30): List<String> = synchronized(lock) {
        val terms = tokenize(query)
        if (terms.isEmpty()) return@synchronized emptyList()

        readableDatabase.query(
            "fact_versions",
            arrayOf(
                "label_cipher",
                "value_cipher",
                "source",
                "confidence",
                "valid_from",
                "valid_to",
                "is_current"
            ),
            null,
            null,
            null,
            null,
            "valid_from DESC",
            MAX_ROWS.toString()
        ).use { cursor ->
            buildList {
                val labelIndex = cursor.getColumnIndexOrThrow("label_cipher")
                val valueIndex = cursor.getColumnIndexOrThrow("value_cipher")
                val sourceIndex = cursor.getColumnIndexOrThrow("source")
                val confidenceIndex = cursor.getColumnIndexOrThrow("confidence")
                val fromIndex = cursor.getColumnIndexOrThrow("valid_from")
                val toIndex = cursor.getColumnIndexOrThrow("valid_to")
                val currentIndex = cursor.getColumnIndexOrThrow("is_current")

                val safeLimit = limit.coerceIn(1, 80)
                while (cursor.moveToNext() && size < safeLimit) {
                    val label = cipher.decrypt(cursor.getString(labelIndex)) ?: continue
                    val value = cipher.decrypt(cursor.getString(valueIndex)) ?: continue
                    val searchable = tokenize("$label $value")
                    val overlap = terms.intersect(searchable).size
                    if (overlap == 0) continue

                    val validTo = if (cursor.isNull(toIndex)) null else cursor.getLong(toIndex)
                    add(
                        buildString {
                            append(label)
                            append(": ")
                            append(value)
                            append(" | from=")
                            append(formatEpoch(cursor.getLong(fromIndex)))
                            append(" | to=")
                            append(validTo?.let(::formatEpoch) ?: "current")
                            append(" | current=")
                            append(cursor.getInt(currentIndex) == 1)
                            append(" | source=")
                            append(cursor.getString(sourceIndex))
                            append(" | confidence=")
                            append(String.format(Locale.ROOT, "%.2f", cursor.getDouble(confidenceIndex)))
                        }
                    )
                }
            }
        }
    }

    fun currentFacts(limit: Int = 100): List<MemoryItem> = synchronized(lock) {
        readableDatabase.query(
            "fact_versions",
            arrayOf("id", "label_cipher", "value_cipher", "valid_from"),
            "is_current=1",
            null,
            null,
            null,
            "valid_from ASC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val labelIndex = cursor.getColumnIndexOrThrow("label_cipher")
                val valueIndex = cursor.getColumnIndexOrThrow("value_cipher")
                val fromIndex = cursor.getColumnIndexOrThrow("valid_from")
                while (cursor.moveToNext()) {
                    val label = cipher.decrypt(cursor.getString(labelIndex)) ?: continue
                    val value = cipher.decrypt(cursor.getString(valueIndex)) ?: continue
                    add(
                        MemoryItem(
                            id = cursor.getString(idIndex),
                            label = label,
                            value = value,
                            createdAtEpochMs = cursor.getLong(fromIndex)
                        )
                    )
                }
            }
        }
    }

    private fun recordLocked(
        item: MemoryItem,
        source: String,
        confidence: Double
    ): Boolean {
        val safeLabel = SecretRedactor.redact(item.label.trim(), 180)
        val safeValue = SecretRedactor.redact(item.value.trim(), 2_000)
        val labelHash = sha256(normalize(safeLabel))
        val valueHash = sha256(normalize(safeValue))
        val now = item.createdAtEpochMs.coerceAtMost(System.currentTimeMillis())

        val current = readableDatabase.query(
            "fact_versions",
            arrayOf("id", "value_hash"),
            "label_hash=? AND is_current=1",
            arrayOf(labelHash),
            null,
            null,
            "valid_from DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) to cursor.getString(1)
            } else {
                null
            }
        }

        if (current?.second == valueHash) return false

        writableDatabase.beginTransaction()
        try {
            if (current != null) {
                val closeValues = ContentValues().apply {
                    put("valid_to", now)
                    put("is_current", 0)
                }
                writableDatabase.update(
                    "fact_versions",
                    closeValues,
                    "id=?",
                    arrayOf(current.first)
                )
            }

            val values = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("label_hash", labelHash)
                put("label_cipher", cipher.encrypt(safeLabel))
                put("value_hash", valueHash)
                put("value_cipher", cipher.encrypt(safeValue))
                put("source", source.take(80))
                put("confidence", confidence.coerceIn(0.0, 1.0))
                put("valid_from", now)
                putNull("valid_to")
                put("is_current", 1)
            }
            writableDatabase.insertOrThrow("fact_versions", null, values)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return true
    }

    private fun pruneLocked() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * DAY_MS
        writableDatabase.delete(
            "fact_versions",
            "is_current=0 AND valid_to IS NOT NULL AND valid_to < ?",
            arrayOf(cutoff.toString())
        )
        writableDatabase.execSQL(
            """
            DELETE FROM fact_versions
            WHERE id NOT IN (
                SELECT id FROM fact_versions
                ORDER BY is_current DESC, valid_from DESC
                LIMIT $MAX_ROWS
            )
            """.trimIndent()
        )
    }


    private fun formatEpoch(epochMs: Long): String = runCatching {
        historyTimeFormatter().format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault(epochMs.toString())

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun tokenize(value: String): Set<String> = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[^\p{L}\p{N}']+"""), " ")
        .split(Regex("""\s+"""))
        .filter { it.length >= 2 }
        .take(100)
        .toSet()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class TemporalCipher {
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

        fun decrypt(value: String): String? = runCatching {
            val parts = value.split(':', limit = 3)
            require(parts.size == 3 && parts[0] == "v1")
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
            const val KEY_ALIAS = "aura_temporal_memory_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_temporal_memory_v1.sqlite3"
        const val DATABASE_VERSION = 1
        const val MAX_ROWS = 4_000
        const val MAX_FACTS_PER_SYNC = 1_500
        const val RETENTION_DAYS = 1_825L
        const val DAY_MS = 86_400_000L
        fun historyTimeFormatter(): DateTimeFormatter = DateTimeFormatter
            .ofPattern("dd MMM yyyy, h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
}
