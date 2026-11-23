package `in`.diganta.aurabutler.voice

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission

/**
 * Creates stable 16 kHz mono recorders for wake spotting and Whisper.
 *
 * Wake spotting uses the raw MIC source first because some Vivo builds apply
 * aggressive processing to VOICE_RECOGNITION that removes short wake words.
 * Command transcription still prefers VOICE_RECOGNITION for noise reduction.
 */
object VoiceAudioRecorder {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun create(sampleRate: Int): AudioRecord = createForCommand(sampleRate)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun createForWake(sampleRate: Int): AudioRecord {
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
        }
        return createFromSources(sampleRate, sources)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun createForCommand(sampleRate: Int): AudioRecord {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        return createFromSources(sampleRate, sources)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun createForBargeIn(sampleRate: Int): AudioRecord {
        // VOICE_COMMUNICATION lets Android apply its available echo-control
        // path while AURA's own speaker is active.
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        return createFromSources(sampleRate, sources)
    }


    fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> source.toString()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createFromSources(
        sampleRate: Int,
        sources: List<Int>
    ): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) {
            "Android rejected AURA's microphone audio format."
        }

        sources.distinct().forEach { source ->
            val recorder = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum * 3, 9_600)
                )
            }.getOrNull() ?: return@forEach

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                return recorder
            }
            runCatching { recorder.release() }
        }

        error("Android could not initialize AURA's microphone.")
    }
}
