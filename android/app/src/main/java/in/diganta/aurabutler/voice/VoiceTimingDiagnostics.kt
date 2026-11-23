package `in`.diganta.aurabutler.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Measures AURA's real on-device speech timing without storing speech content.
 */
object VoiceTimingDiagnostics {
    private const val PREFS = "aura_voice_timing"
    private const val TAG = "AuraVoiceTiming"

    private const val KEY_ID = "id"
    private const val KEY_ENGINE = "engine"
    private const val KEY_CHARS = "chars"
    private const val KEY_REQUESTED = "requested_elapsed"
    private const val KEY_STARTED = "started_elapsed"
    private const val KEY_AI_MS = "ai_ms"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_UPDATED_AT = "updated_at"

    private const val ESTIMATED_CHARACTERS_PER_SECOND = 13L

    fun noteAiLatency(context: Context, latencyMs: Long) {
        prefs(context).edit()
            .putLong(KEY_AI_MS, latencyMs.coerceAtLeast(0L))
            .apply()
    }

    fun begin(
        context: Context,
        text: String,
        engine: String,
        id: String = "aura-voice-${UUID.randomUUID()}"
    ): String {
        val now = SystemClock.elapsedRealtime()
        prefs(context).edit()
            .putString(KEY_ID, id)
            .putString(KEY_ENGINE, engine.take(80))
            .putInt(KEY_CHARS, text.length)
            .putLong(KEY_REQUESTED, now)
            .putLong(KEY_STARTED, 0L)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        Log.i(
            TAG,
            "requested engine=$engine chars=${text.length} " +
                "estimatedReadMs=${estimateReadMs(text.length)}"
        )
        return id
    }

    fun markStarted(context: Context, id: String) {
        val preferences = prefs(context)
        if (preferences.getString(KEY_ID, "") != id) return

        val now = SystemClock.elapsedRealtime()
        preferences.edit()
            .putLong(KEY_STARTED, now)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun complete(
        context: Context,
        id: String,
        completedNormally: Boolean = true,
        modelLoadMs: Long = 0L,
        generationMs: Long = 0L,
        audioDurationMs: Long = 0L
    ) {
        val preferences = prefs(context)
        if (preferences.getString(KEY_ID, "") != id) return

        val end = SystemClock.elapsedRealtime()
        val requested = preferences.getLong(KEY_REQUESTED, end)
        val started = preferences.getLong(KEY_STARTED, 0L)
        val chars = preferences.getInt(KEY_CHARS, 0)
        val engine = preferences.getString(KEY_ENGINE, "voice") ?: "voice"
        val aiMs = preferences.getLong(KEY_AI_MS, 0L)

        val startupMs = if (started > 0L) {
            (started - requested).coerceAtLeast(0L)
        } else {
            (end - requested).coerceAtLeast(0L)
        }
        val speechMs = if (started > 0L) {
            (end - started).coerceAtLeast(0L)
        } else {
            0L
        }
        val voiceTotalMs = (end - requested).coerceAtLeast(0L)
        val endToEndMs = (aiMs + voiceTotalMs).coerceAtLeast(0L)
        val estimateMs = estimateReadMs(chars)

        val summary = buildString {
            append(engine)
            append(" • AI ")
            append(formatMs(aiMs))
            append(" • voice start ")
            append(formatMs(startupMs))
            append(" • speaking ")
            append(formatMs(speechMs))
            append(" • total ")
            append(formatMs(endToEndMs))
            append(" • ")
            append(chars)
            append(" chars")
            append(" • expected ~")
            append(formatMs(estimateMs))
            if (modelLoadMs > 0L) append(" • Piper load ${formatMs(modelLoadMs)}")
            if (generationMs > 0L) append(" • synth ${formatMs(generationMs)}")
            if (audioDurationMs > 0L) append(" • audio ${formatMs(audioDurationMs)}")
            if (!completedNormally) append(" • interrupted")
        }

        preferences.edit()
            .putString(KEY_SUMMARY, summary)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        Log.i(
            TAG,
            "complete=$completedNormally id=$id engine=$engine chars=$chars " +
                "aiMs=$aiMs startupMs=$startupMs speechMs=$speechMs " +
                "totalMs=$endToEndMs estimatedMs=$estimateMs " +
                "modelLoadMs=$modelLoadMs generationMs=$generationMs " +
                "audioDurationMs=$audioDurationMs"
        )
    }

    fun fail(context: Context, id: String) {
        complete(
            context = context,
            id = id,
            completedNormally = false
        )
    }

    fun lastSummary(context: Context): String =
        prefs(context).getString(KEY_SUMMARY, "").orEmpty()

    fun estimateReadMs(characters: Int): Long {
        if (characters <= 0) return 0L
        return (
            characters.toLong() * 1_000L /
                ESTIMATED_CHARACTERS_PER_SECOND
            ).coerceAtLeast(1L)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun formatMs(value: Long): String =
        when {
            value <= 0L -> "0s"
            value < 1_000L -> "${value}ms"
            else -> String.format("%.1fs", value / 1_000.0)
        }
}
