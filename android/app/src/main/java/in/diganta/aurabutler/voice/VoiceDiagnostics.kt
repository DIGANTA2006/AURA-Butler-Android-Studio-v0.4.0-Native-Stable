package `in`.diganta.aurabutler.voice

import android.content.Context
import `in`.diganta.aurabutler.data.SecureStore

data class WakeServiceStatus(
    val active: Boolean,
    val status: String,
    val updatedAtEpochMs: Long,
    val rms: Float,
    val peak: Float,
    val source: String
)

/**
 * Stores voice-runtime health only. It never stores microphone audio.
 */
object VoiceDiagnostics {
    private const val PREFS = "aura_voice_diagnostics"
    private const val KEY_STAGE = "stage"
    private const val KEY_TIME = "stage_time"
    private const val KEY_RECOVERY_MESSAGE = "recovery_message"
    private const val KEY_SERVICE_ACTIVE = "wake_service_active"
    private const val KEY_SERVICE_STATUS = "wake_service_status"
    private const val KEY_SERVICE_UPDATED_AT = "wake_service_updated_at"
    private const val KEY_WAKE_RMS = "wake_rms"
    private const val KEY_WAKE_PEAK = "wake_peak"
    private const val KEY_WAKE_SOURCE = "wake_source"
    private const val RECENT_STAGE_WINDOW_MS = 10 * 60 * 1000L

    const val IDLE = "idle"
    const val LOADING_WAKE_MODEL = "loading_wake_model"
    const val WAKE_LISTENING = "wake_listening"
    const val CAPTURING_COMMAND = "capturing_command"
    const val LOADING_WHISPER = "loading_whisper"
    const val TRANSCRIBING = "transcribing"
    const val LOADING_VOICE = "loading_voice"
    const val SPEAKING = "speaking"

    fun mark(context: Context, stage: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGE, stage)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun markWakeService(
        context: Context,
        active: Boolean,
        status: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ACTIVE, active)
            .putString(KEY_SERVICE_STATUS, status.take(120))
            .putLong(KEY_SERVICE_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun updateWakeAudio(
        context: Context,
        rms: Float,
        peak: Float,
        source: String
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_WAKE_RMS, rms.coerceAtLeast(0f))
            .putFloat(KEY_WAKE_PEAK, peak.coerceAtLeast(0f))
            .putString(KEY_WAKE_SOURCE, source.take(80))
            .putLong(KEY_SERVICE_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun wakeStatus(context: Context): WakeServiceStatus {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = prefs.getLong(KEY_SERVICE_UPDATED_AT, 0L)
        val fresh = System.currentTimeMillis() - updated < 30_000L
        return WakeServiceStatus(
            active = prefs.getBoolean(KEY_SERVICE_ACTIVE, false) && fresh,
            status = prefs.getString(KEY_SERVICE_STATUS, "unknown") ?: "unknown",
            updatedAtEpochMs = updated,
            rms = prefs.getFloat(KEY_WAKE_RMS, 0f),
            peak = prefs.getFloat(KEY_WAKE_PEAK, 0f),
            source = prefs.getString(KEY_WAKE_SOURCE, "") ?: ""
        )
    }

    fun recoverInterruptedNativeStage(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = prefs.getString(KEY_STAGE, IDLE) ?: IDLE
        val age = System.currentTimeMillis() - prefs.getLong(KEY_TIME, 0L)
        val unsafeStage = stage in setOf(
            LOADING_WAKE_MODEL,
            CAPTURING_COMMAND,
            LOADING_WHISPER,
            TRANSCRIBING,
            LOADING_VOICE,
            SPEAKING
        )
        if (unsafeStage && age in 0L..RECENT_STAGE_WINDOW_MS) {
            runCatching {
                val store = SecureStore(app)
                val settings = store.loadSettings()
                if (settings.wakeWordEnabled) {
                    store.saveSettings(settings.copy(wakeWordEnabled = false))
                }
            }
            prefs.edit()
                .putString(
                    KEY_RECOVERY_MESSAGE,
                    "AURA recovered after the native voice engine stopped during $stage. " +
                        "Wake listening was turned off to prevent a crash loop. Test the " +
                        "microphone and voice, then enable it again."
                )
                .putString(KEY_STAGE, IDLE)
                .putBoolean(KEY_SERVICE_ACTIVE, false)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .apply()
        } else {
            mark(app, IDLE)
        }
    }

    fun consumeRecoveryMessage(context: Context): String? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_RECOVERY_MESSAGE, null)
        if (message != null) prefs.edit().remove(KEY_RECOVERY_MESSAGE).apply()
        return message
    }
}
