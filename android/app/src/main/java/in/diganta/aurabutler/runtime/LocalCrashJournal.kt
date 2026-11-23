package `in`.diganta.aurabutler.runtime

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/** Local-only, secret-redacted crash breadcrumb; never uploaded. */
object LocalCrashJournal {
    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val app = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { write(app, thread, throwable) }
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    fun summary(context: Context): String? = runCatching {
        file(context).takeIf(File::isFile)?.readText()?.take(2_000)
    }.getOrNull()?.takeIf(String::isNotBlank)

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        val safe = SecretRedactor.redact(writer.toString(), 10_000)
        file(context).writeText(
            "time=${Instant.now()}\nthread=${thread.name.take(80)}\n$safe"
        )
    }

    private fun file(context: Context): File =
        File(context.filesDir, "aura_last_crash.txt")
}
