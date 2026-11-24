package `in`.diganta.aurabutler.telephony

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Visible foreground-service indicator and audio-focus owner during an active VoIP call. */
class AuraVoipForegroundService : Service() {
    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private var focusAcquired = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            AuraVoipCallManager.end()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AURA VoIP calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Visible indicator while AURA uses the microphone for an owner-hosted VoIP call"
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("AURA VoIP call")
            .setContentText("Microphone active for your owner-hosted AURA AI session")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        if (!acquireCommunicationAudioFocus()) {
            AuraVoipCallManager.end()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun acquireCommunicationAudioFocus(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audioManager = manager
        previousAudioMode = manager.mode
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            focusAcquired = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focusAcquired = manager.requestAudioFocus(
                focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (focusAcquired) {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            focusRequest = null
        }
        return focusAcquired
    }

    override fun onDestroy() {
        audioManager?.let { manager ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { runCatching { manager.abandonAudioFocusRequest(it) } }
            } else {
                @Suppress("DEPRECATION")
                runCatching { manager.abandonAudioFocus(focusListener) }
            }
            manager.mode = previousAudioMode
        }
        focusRequest = null
        focusAcquired = false
        audioManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "aura_voip_active_call"
        const val NOTIFICATION_ID = 21_041
    }
}
