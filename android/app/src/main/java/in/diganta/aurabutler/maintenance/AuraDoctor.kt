package `in`.diganta.aurabutler.maintenance

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.DeepResearchProvider
import `in`.diganta.aurabutler.voice.VoiceDiagnostics
import `in`.diganta.aurabutler.runtime.LocalCrashJournal
import `in`.diganta.aurabutler.runtime.StudentBudgetGuard

object AuraDoctor {
    fun run(context: Context): String {
        val app = context.applicationContext
        val store = SecureStore(app)
        val settings = store.loadSettings()
        val checks = mutableListOf<String>()

        checks += checkAsset(app, "voice/kws/encoder.int8.onnx", "Wake model")
        checks += checkAsset(app, "voice/whisper/encoder.int8.onnx", "Whisper model")
        checks += checkAsset(app, "voice/piper/model.onnx", "Piper voice")

        val microphoneGranted = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        checks += if (microphoneGranted) {
            "PASS: Microphone permission"
        } else {
            "FAIL: Microphone permission is missing"
        }

        val notificationAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            app.getSystemService(NotificationManager::class.java)
                .areNotificationsEnabled()
        }
        checks += if (notificationAllowed) {
            "PASS: Wake notification permission"
        } else {
            "FAIL: Notifications are blocked"
        }

        val powerManager = app.getSystemService(PowerManager::class.java)
        checks += if (powerManager.isIgnoringBatteryOptimizations(app.packageName)) {
            "PASS: Battery optimization exemption"
        } else {
            "WARN: Vivo may stop AURA; set battery use to Unrestricted"
        }

        val wake = VoiceDiagnostics.wakeStatus(app)
        checks += if (wake.active) {
            "PASS: Wake service active (${wake.status})"
        } else if (settings.wakeWordEnabled) {
            "FAIL: Wake is enabled in settings but the service is not active"
        } else {
            "INFO: Wake listening is disabled"
        }

        if (wake.source.isNotBlank()) {
            checks += "INFO: Wake microphone ${wake.source}, RMS ${"%.4f".format(wake.rms)}"
        }

        val crash = LocalCrashJournal.summary(app)
        checks += if (crash.isNullOrBlank()) {
            "PASS: No locally journaled uncaught crash"
        } else {
            "WARN: A previous crash was journaled locally; inspect AURA doctor details"
        }

        val optionalAi = HybridAiProvider.configuredProviderLabel(settings)
        checks += if (optionalAi != "local-only") {
            "PASS: Optional AI route configured ($optionalAi)"
        } else {
            "INFO: Optional AI is disabled; local and keyless tools remain available"
        }
        checks += "INFO: ${StudentBudgetGuard.statusText()}"

        checks += if (settings.aiEnabled && settings.localAiEnabled && settings.localAiApiKey.isNotBlank()) {
            "PASS: Private Qwen endpoint and token are configured"
        } else {
            "INFO: Private Qwen is not fully enabled/configured"
        }

        checks += if (settings.privateVisionEnabled && settings.privateVisionEndpoint.isNotBlank() && settings.localAiApiKey.isNotBlank()) {
            "PASS: Private Gemma screen vision is configured"
        } else {
            "INFO: Private Gemma screen vision is not fully configured"
        }

        checks += if (DeepResearchProvider.isConfigured(settings)) {
            "PASS: Private deep research is configured (${DeepResearchProvider.normalizeMode(settings.researchMode)})"
        } else if (settings.researchEnabled) {
            "INFO: Deep research is enabled but the private Research Gateway/token is incomplete"
        } else {
            "INFO: Deep research is disabled"
        }

        checks += when (settings.speechLanguage.lowercase()) {
            "bn" -> "INFO: Preferred speech language is Bengali"
            "hi" -> "INFO: Preferred speech language is Hindi"
            "en" -> "INFO: Preferred speech language is English"
            else -> "INFO: Speech language is automatic (English/Bengali/Hindi)"
        }

        return buildString {
            appendLine("AURA doctor report")
            checks.forEach(::appendLine)
            append("State data is local. This report does not include microphone audio or API keys.")
        }
    }

    private fun checkAsset(
        context: Context,
        path: String,
        label: String
    ): String = runCatching {
        context.assets.openFd(path).use { descriptor ->
            if (descriptor.length > 1_024) {
                "PASS: $label (${descriptor.length / 1_048_576} MB)"
            } else {
                "FAIL: $label is incomplete"
            }
        }
    }.getOrElse {
        // Compressed assets cannot use openFd, so fall back to reading one byte.
        runCatching {
            context.assets.open(path).use { input ->
                if (input.read() >= 0) "PASS: $label" else "FAIL: $label is empty"
            }
        }.getOrDefault("FAIL: $label is missing")
    }
}
