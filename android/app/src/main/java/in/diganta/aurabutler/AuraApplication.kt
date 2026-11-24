package `in`.diganta.aurabutler

import android.app.Application
import `in`.diganta.aurabutler.background.ReminderWorker
import `in`.diganta.aurabutler.background.AnalyzerDigestWorker
import `in`.diganta.aurabutler.analyzer.ProactiveAlertManager
import `in`.diganta.aurabutler.screen.ScreenCaptureService
import `in`.diganta.aurabutler.background.WakeWordService
import `in`.diganta.aurabutler.voice.AuraVoiceRuntime
import `in`.diganta.aurabutler.runtime.AuraRuntimeStateMachine
import `in`.diganta.aurabutler.runtime.LocalCrashJournal
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard
import `in`.diganta.aurabutler.voice.VoiceDiagnostics
import `in`.diganta.aurabutler.memory.EmbeddingModelFiles
import `in`.diganta.aurabutler.data.SecureStore

class AuraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalCrashJournal.install(this)
        StudentBudgetGuard.initialize(this)
        AuraRuntimeStateMachine.startIfNeeded("application created")
        AuraVoiceRuntime.initialize(this)
        VoiceDiagnostics.recoverInterruptedNativeStage(this)
        ReminderWorker.createChannel(this)
        WakeWordService.createChannel(this)
        AnalyzerDigestWorker.createChannel(this)
        AnalyzerDigestWorker.schedule(this)
        ProactiveAlertManager.createChannel(this)
        ScreenCaptureService.createChannel(this)

        // The quantized MiniLM model is fetched only on unmetered Wi-Fi
        // while battery is not low; no model bytes are bundled into the APK.
        if (SecureStore(this).loadSettings().semanticMemoryEnabled) {
            EmbeddingModelFiles.enqueueIfMissing(this)
        }
    }
}
