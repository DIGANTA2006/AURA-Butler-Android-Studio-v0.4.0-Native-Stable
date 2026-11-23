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
    }
}
