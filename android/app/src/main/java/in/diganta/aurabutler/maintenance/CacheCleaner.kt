package `in`.diganta.aurabutler.maintenance

import android.content.Context
import java.io.File

/**
 * Clears disposable app cache only: Android's cache folders, which include AURA's
 * temporary screen captures and image copies. Chats, memory, reminders, settings,
 * the encrypted stores and the speech and embedding models live in filesDir, the
 * APK or noBackupFilesDir and are never touched here.
 */
object CacheCleaner {
    data class CleanupReport(val freedBytes: Long, val removedItems: Int)

    fun cacheBytes(context: Context): Long =
        cacheRoots(context).sumOf { sizeOf(it) }

    fun clear(context: Context): CleanupReport {
        var freed = 0L
        var removed = 0
        cacheRoots(context).forEach { root ->
            root.listFiles()?.forEach { entry ->
                val size = sizeOf(entry)
                if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                    freed += size
                    removed += 1
                }
            }
        }
        return CleanupReport(freedBytes = freed, removedItems = removed)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes bytes"
    }

    private fun cacheRoots(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir)

    private fun sizeOf(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        else -> 0L
    }
}