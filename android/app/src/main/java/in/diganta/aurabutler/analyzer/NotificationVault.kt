package `in`.diganta.aurabutler.analyzer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * Encrypted, bounded notification history.
 *
 * Notification content is encrypted with a non-exportable Android Keystore key.
 * SQLite contains ciphertext plus non-sensitive routing fields used to filter
 * locally. Secret-like text is redacted before it is encrypted.
 */
class NotificationVault(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val lock = Any()
    private val cipher = VaultCipher()

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
            CREATE TABLE notifications (
                id TEXT PRIMARY KEY NOT NULL,
                notification_key_hash TEXT NOT NULL,
                package_name TEXT NOT NULL,
                app_name_cipher TEXT NOT NULL,
                title_cipher TEXT NOT NULL,
                text_cipher TEXT NOT NULL,
                sub_text_cipher TEXT NOT NULL,
                sender_cipher TEXT NOT NULL,
                channel TEXT NOT NULL,
                priority TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                is_conversation INTEGER NOT NULL,
                is_group_conversation INTEGER NOT NULL,
                content_hash TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX notifications_posted_idx ON notifications(posted_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX notifications_channel_idx ON notifications(channel, posted_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX notifications_priority_idx ON notifications(priority, posted_at DESC)"
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 1) onCreate(db)
    }

    fun add(item: CapturedNotification, retentionDays: Int): Boolean =
        synchronized(lock) {
            if (
                item.packageName.isBlank() ||
                (item.title.isBlank() && item.text.isBlank() && item.subText.isBlank())
            ) {
                return@synchronized false
            }

            val safeTitle = SecretRedactor.redact(item.title.trim(), MAX_FIELD_CHARS)
            val safeText = SecretRedactor.redact(item.text.trim(), MAX_TEXT_CHARS)
            val safeSubText = SecretRedactor.redact(item.subText.trim(), MAX_FIELD_CHARS)
            val safeSender = SecretRedactor.redact(item.sender.trim(), MAX_FIELD_CHARS)
            val safeAppName = item.appName.trim().take(MAX_FIELD_CHARS)
            val contentHash = sha256(
                listOf(
                    item.packageName,
                    safeTitle,
                    safeText,
                    safeSubText,
                    (item.postedAtEpochMs / DEDUPE_WINDOW_MS).toString()
                ).joinToString("|").lowercase(Locale.ROOT)
            )

            val values = ContentValues().apply {
                put("id", item.id)
                put("notification_key_hash", sha256(item.notificationKey))
                put("package_name", item.packageName.take(180))
                put("app_name_cipher", cipher.encrypt(safeAppName))
                put("title_cipher", cipher.encrypt(safeTitle))
                put("text_cipher", cipher.encrypt(safeText))
                put("sub_text_cipher", cipher.encrypt(safeSubText))
                put("sender_cipher", cipher.encrypt(safeSender))
                put("channel", item.channel.name)
                put("priority", item.priority.name)
                put("posted_at", item.postedAtEpochMs)
                put("captured_at", item.capturedAtEpochMs)
                put("is_conversation", if (item.isConversation) 1 else 0)
                put("is_group_conversation", if (item.isGroupConversation) 1 else 0)
                put("content_hash", contentHash)
            }

            val row = writableDatabase.insertWithOnConflict(
                "notifications",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
            pruneLocked(retentionDays)
            capRowsLocked()
            row != -1L
        }

    fun list(
        channel: AnalyzerChannel? = null,
        minimumPriority: NotificationPriority? = null,
        sinceEpochMs: Long = 0L,
        limit: Int = DEFAULT_LIMIT
    ): List<CapturedNotification> = synchronized(lock) {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (channel != null) {
            clauses += "channel=?"
            args += channel.name
        }
        if (minimumPriority != null) {
            val priorities = NotificationPriority.entries
                .filter { it.weight >= minimumPriority.weight }
                .map { it.name }
            if (priorities.isNotEmpty()) {
                clauses += "priority IN (${priorities.joinToString(",") { "?" }})"
                args += priorities
            }
        }
        if (sinceEpochMs > 0L) {
            clauses += "posted_at>=?"
            args += sinceEpochMs.toString()
        }

        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        readableDatabase.query(
            "notifications",
            COLUMNS,
            selection,
            args.takeIf { it.isNotEmpty() }?.toTypedArray(),
            null,
            null,
            "posted_at DESC",
            limit.coerceIn(1, MAX_QUERY_LIMIT).toString()
        ).use { cursor ->
            buildList {
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val keyIndex = cursor.getColumnIndexOrThrow("notification_key_hash")
                val packageIndex = cursor.getColumnIndexOrThrow("package_name")
                val appIndex = cursor.getColumnIndexOrThrow("app_name_cipher")
                val titleIndex = cursor.getColumnIndexOrThrow("title_cipher")
                val textIndex = cursor.getColumnIndexOrThrow("text_cipher")
                val subTextIndex = cursor.getColumnIndexOrThrow("sub_text_cipher")
                val senderIndex = cursor.getColumnIndexOrThrow("sender_cipher")
                val channelIndex = cursor.getColumnIndexOrThrow("channel")
                val priorityIndex = cursor.getColumnIndexOrThrow("priority")
                val postedIndex = cursor.getColumnIndexOrThrow("posted_at")
                val capturedIndex = cursor.getColumnIndexOrThrow("captured_at")
                val conversationIndex = cursor.getColumnIndexOrThrow("is_conversation")
                val groupIndex = cursor.getColumnIndexOrThrow("is_group_conversation")

                while (cursor.moveToNext()) {
                    val appName = cipher.decrypt(cursor.getString(appIndex)) ?: continue
                    val title = cipher.decrypt(cursor.getString(titleIndex)) ?: continue
                    val text = cipher.decrypt(cursor.getString(textIndex)) ?: continue
                    val subText = cipher.decrypt(cursor.getString(subTextIndex)) ?: continue
                    val sender = cipher.decrypt(cursor.getString(senderIndex)) ?: continue
                    add(
                        CapturedNotification(
                            id = cursor.getString(idIndex),
                            notificationKey = cursor.getString(keyIndex),
                            packageName = cursor.getString(packageIndex),
                            appName = appName,
                            title = title,
                            text = text,
                            subText = subText,
                            sender = sender,
                            channel = runCatching {
                                AnalyzerChannel.valueOf(cursor.getString(channelIndex))
                            }.getOrDefault(AnalyzerChannel.OTHER),
                            priority = runCatching {
                                NotificationPriority.valueOf(
                                    cursor.getString(priorityIndex)
                                )
                            }.getOrDefault(NotificationPriority.NORMAL),
                            postedAtEpochMs = cursor.getLong(postedIndex),
                            capturedAtEpochMs = cursor.getLong(capturedIndex),
                            isConversation = cursor.getInt(conversationIndex) == 1,
                            isGroupConversation = cursor.getInt(groupIndex) == 1
                        )
                    )
                }
            }
        }
    }

    fun count(): Int = synchronized(lock) {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM notifications",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun clear(): Int = synchronized(lock) {
        writableDatabase.delete("notifications", null, null)
    }

    fun prune(retentionDays: Int): Int = synchronized(lock) {
        pruneLocked(retentionDays)
    }

    private fun pruneLocked(retentionDays: Int): Int {
        val safeDays = retentionDays.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
        val cutoff = System.currentTimeMillis() - safeDays * DAY_MS
        return writableDatabase.delete(
            "notifications",
            "posted_at < ?",
            arrayOf(cutoff.toString())
        )
    }

    private fun capRowsLocked() {
        writableDatabase.execSQL(
            """
            DELETE FROM notifications
            WHERE id NOT IN (
                SELECT id FROM notifications
                ORDER BY posted_at DESC
                LIMIT $MAX_ROWS
            )
            """.trimIndent()
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class VaultCipher {
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
            val parts = encoded.split(':', limit = 3)
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
            const val KEY_ALIAS = "aura_notification_vault_v1"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }

    private companion object {
        const val DATABASE_NAME = "aura_notification_vault_v1.sqlite3"
        const val DATABASE_VERSION = 1
        const val MAX_ROWS = 1_500
        const val DEFAULT_LIMIT = 120
        const val MAX_QUERY_LIMIT = 500
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 30
        const val MAX_FIELD_CHARS = 300
        const val MAX_TEXT_CHARS = 2_000
        const val DEDUPE_WINDOW_MS = 30_000L
        const val DAY_MS = 86_400_000L

        val COLUMNS = arrayOf(
            "id",
            "notification_key_hash",
            "package_name",
            "app_name_cipher",
            "title_cipher",
            "text_cipher",
            "sub_text_cipher",
            "sender_cipher",
            "channel",
            "priority",
            "posted_at",
            "captured_at",
            "is_conversation",
            "is_group_conversation"
        )
    }
}
