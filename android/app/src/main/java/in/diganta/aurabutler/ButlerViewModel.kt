package `in`.diganta.aurabutler

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.diganta.aurabutler.background.ReminderWorker
import `in`.diganta.aurabutler.analyzer.NotificationAccessState
import `in`.diganta.aurabutler.analyzer.NotificationVault
import `in`.diganta.aurabutler.background.WakeWordService
import `in`.diganta.aurabutler.data.AiResponseFormatter
import `in`.diganta.aurabutler.data.GeminiAiProvider
import `in`.diganta.aurabutler.data.GeminiAnalyzerProvider
import `in`.diganta.aurabutler.data.GroqAiProvider
import `in`.diganta.aurabutler.data.HybridAiProvider
import `in`.diganta.aurabutler.data.KeylessLiveInfoProvider
import `in`.diganta.aurabutler.data.OpenAiCompatibleProvider
import `in`.diganta.aurabutler.data.PrivateVisionProvider
import `in`.diganta.aurabutler.data.PrivateDocumentProvider
import `in`.diganta.aurabutler.data.SecureStore
import `in`.diganta.aurabutler.voice.OwnerVoiceGuard
import `in`.diganta.aurabutler.domain.ActionExecutor
import `in`.diganta.aurabutler.domain.ButlerPersona
import `in`.diganta.aurabutler.domain.DirectActionPolicy
import `in`.diganta.aurabutler.domain.ExecutionResult
import `in`.diganta.aurabutler.domain.LocalButlerEngine
import `in`.diganta.aurabutler.domain.ProductionButlerEngine
import `in`.diganta.aurabutler.planning.AndroidPlanningRuntime
import `in`.diganta.aurabutler.planning.TaskPlanner
import `in`.diganta.aurabutler.planning.ProductionTaskExecutor
import `in`.diganta.aurabutler.intelligence.AndroidProactiveActionHandler
import `in`.diganta.aurabutler.intelligence.AndroidProactiveContextProvider
import `in`.diganta.aurabutler.intelligence.EncryptedProactivePersistence
import `in`.diganta.aurabutler.intelligence.ProactiveEngine
import `in`.diganta.aurabutler.intelligence.AndroidProactiveScheduler
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionHistoryItem
import `in`.diganta.aurabutler.model.AppTab
import `in`.diganta.aurabutler.model.AuraUiState
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.ReminderItem
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.voice.SpeechTextSanitizer
import `in`.diganta.aurabutler.voice.VoiceDiagnostics
import `in`.diganta.aurabutler.voice.VoiceTimingDiagnostics
import `in`.diganta.aurabutler.screen.ImageInputReader
import `in`.diganta.aurabutler.document.DocumentInputReader
import `in`.diganta.aurabutler.runtime.ActionAuditLog
import `in`.diganta.aurabutler.runtime.CapabilityPolicy
import `in`.diganta.aurabutler.runtime.PolicyDecision
import `in`.diganta.aurabutler.runtime.SafeEndpointValidator
import `in`.diganta.aurabutler.runtime.ResponseQualityGate
import `in`.diganta.aurabutler.runtime.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import `in`.diganta.aurabutler.data.ButlerSystemPrompt
import `in`.diganta.aurabutler.data.DeepResearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ButlerViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SecureStore(application)
    private val localButler = LocalButlerEngine(
        liveInfo = KeylessLiveInfoProvider(application),
        generalAi = HybridAiProvider()
    )
    private val butler = ProductionButlerEngine(
        context = application,
        store = store,
        delegate = localButler
    )
    private val actionExecutor = ActionExecutor(application)
    private val capabilityPolicy = CapabilityPolicy()
    private val audit = ActionAuditLog(application)
    private val notificationVault = NotificationVault(application)
    private val privateVisionAnalyzer = PrivateVisionProvider()
    private val privateDocumentAnalyzer = PrivateDocumentProvider()
    private val geminiAnalyzer = GeminiAnalyzerProvider()

    // Access this lazy property only inside Dispatchers.IO: its constructor
    // restores encrypted plan checkpoints from SQLite.
    private val planningRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidPlanningRuntime(getApplication())
    }
    private val planningRuntime by planningRuntimeDelegate

    @Volatile
    private var proactiveEngineInstance: ProactiveEngine? = null

    private val proactiveEngineLock = Any()

    private val initialState = AuraUiState(
        messages = store.loadMessages().ifEmpty { AuraUiState().messages },
        memories = store.loadMemories(),
        reminders = store.loadReminders().filter {
            it.triggerAtEpochMs > System.currentTimeMillis() - 60_000
        },
        actionHistory = store.loadHistory(),
        pendingActions = store.loadPendingActions(),
        settings = store.loadSettings(),
        transientMessage = VoiceDiagnostics.consumeRecoveryMessage(application),
        notificationAccessGranted = NotificationAccessState.isGranted(application),
        notificationVaultCount = notificationVault.count(),
        ownerVoiceEnrolled = OwnerVoiceGuard.isEnrolled(application),
        ownerVoiceStatus = if (OwnerVoiceGuard.isEnrolled(application)) {
            "Owner voice profile is registered locally."
        } else {
            "Owner voice is not registered yet."
        }
    )
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<AuraUiState> = _uiState.asStateFlow()

    init {
        // loadPendingActions() filters expired/legacy entries. Persist the clean
        // list immediately so stale approvals cannot survive indefinitely on disk.
        store.savePendingActions(initialState.pendingActions)
        if (_uiState.value.settings.proactiveSuggestionsEnabled) {
            AndroidProactiveScheduler.ensureScheduled(getApplication())
        } else {
            AndroidProactiveScheduler.cancel(getApplication())
        }
        refreshProactiveSuggestions()
    }

    fun setInput(value: String) {
        _uiState.update { it.copy(input = value.take(12_000)) }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == AppTab.ANALYZER) refreshAnalyzerStatus()
    }

    fun submitCurrentInput() {
        val text = _uiState.value.input.trim()
        if (text.isNotBlank()) submitText(text)
    }

    fun submitVoiceText(text: String) {
        setInput(text)
        submitText(text)
    }

    fun submitText(text: String) {
        if (_uiState.value.isBusy || text.isBlank()) return
        val before = _uiState.value
        val userMessage = ChatMessage(sender = Sender.USER, text = text.trim())
        val withUser = before.messages + userMessage

        _uiState.update {
            it.copy(
                messages = withUser,
                input = "",
                isBusy = true,
                streamingPreview = "",
                selectedTab = AppTab.BUTLER,
                transientMessage = null
            )
        }
        store.saveMessages(withUser)

        viewModelScope.launch {
            try {
                val requestContext = ConversationContext(
                    recentMessages = before.messages.takeLast(16),
                    memories = before.memories,
                    settings = before.settings
                )
                val planCommand = parsePlanningCommand(text.trim())
                val reply = if (planCommand != null) {
                    withContext(Dispatchers.IO) {
                        executePlanningCommand(planCommand, requestContext)
                    }
                } else {
                    butler.chat(
                        message = text.trim(),
                        context = requestContext,
                        onPartial = { delta ->
                            if (delta.isNotBlank()) {
                                _uiState.update { current ->
                                    current.copy(
                                        streamingPreview = (
                                            current.streamingPreview + delta
                                        ).takeLast(12_000)
                                    )
                                }
                            }
                        }
                    )
                }
                VoiceTimingDiagnostics.noteAiLatency(
                    getApplication(),
                    reply.latencyMs
                )

                val directMessages = mutableListOf<String>()
                val directHistory = mutableListOf<ActionHistoryItem>()
                val directReminders = mutableListOf<ReminderItem>()
                val approvalActions = mutableListOf<ButlerAction>()

                reply.pendingActions.forEach { action ->
                    if (!DirectActionPolicy.canExecuteImmediately(action)) {
                        approvalActions += action
                        return@forEach
                    }

                    if (
                        capabilityPolicy.evaluate(action, confirmed = true) ==
                        PolicyDecision.DENY
                    ) {
                        val reason =
                            "AURA blocked ${action.title} under the local safety policy."
                        directMessages += reason
                        directHistory += ActionHistoryItem(
                            title = action.title,
                            detail = reason,
                            approved = false
                        )
                        audit.record(
                            "action.direct.denied",
                            mapOf("kind" to action.kind.name, "title" to action.title)
                        )
                        return@forEach
                    }

                    when (val result = actionExecutor.execute(action)) {
                        is ExecutionResult.Success -> {
                            directMessages += result.detail
                            result.reminder?.let(directReminders::add)
                            directHistory += ActionHistoryItem(
                                title = action.title,
                                detail = result.detail,
                                approved = true
                            )
                            audit.record(
                                "action.direct.completed",
                                mapOf(
                                    "kind" to action.kind.name,
                                    "title" to action.title,
                                    "detail" to result.detail,
                                    "source" to "foreground"
                                )
                            )
                        }

                        is ExecutionResult.Failure -> {
                            directMessages += result.reason
                            directHistory += ActionHistoryItem(
                                title = action.title,
                                detail = result.reason,
                                approved = false
                            )
                            audit.record(
                                "action.direct.failed",
                                mapOf(
                                    "kind" to action.kind.name,
                                    "title" to action.title,
                                    "reason" to result.reason,
                                    "source" to "foreground"
                                )
                            )
                        }
                    }
                }

                val rawAssistantText = resolvedAssistantText(
                    original = reply.assistantMessage,
                    hadActions = reply.pendingActions.isNotEmpty(),
                    directMessages = directMessages,
                    approvalActions = approvalActions
                )
                val rawSpeechText = resolvedAssistantText(
                    original = reply.speechText,
                    hadActions = reply.pendingActions.isNotEmpty(),
                    directMessages = directMessages,
                    approvalActions = approvalActions
                )
                val assistantText = ResponseQualityGate.forDisplay(
                    ButlerPersona.style(
                        text = rawAssistantText,
                        intent = reply.intent,
                        settings = before.settings,
                        request = text
                    ),
                    before.settings.responseStyle
                )
                val speechText = SpeechTextSanitizer.clean(
                    ResponseQualityGate.forDisplay(
                        ButlerPersona.style(
                            text = rawSpeechText,
                            intent = reply.intent,
                            settings = before.settings,
                            request = text
                        ),
                        before.settings.responseStyle
                    )
                )
                val assistant = ChatMessage(
                    sender = Sender.AURA,
                    text = assistantText,
                    provider = reply.provider,
                    intent = reply.intent,
                    latencyMs = reply.latencyMs
                )

                _uiState.update { current ->
                    val mergedMemories = mergeMemories(current.memories, reply.memoryWrites)
                    current.copy(
                        messages = current.messages + assistant,
                        pendingActions = (
                            current.pendingActions + approvalActions
                            ).takeLast(12),
                        memories = mergedMemories,
                        reminders = (
                            current.reminders + directReminders
                            ).takeLast(500),
                        actionHistory = (
                            current.actionHistory + directHistory
                            ).takeLast(100),
                        isBusy = false,
                        streamingPreview = "",
                        transientMessage = directMessages.lastOrNull(),
                        speechText = if (current.settings.spokenReplies) speechText else null,
                        speechSequence = current.speechSequence + 1
                    )
                }
                store.saveMessages(_uiState.value.messages)
                store.saveMemories(_uiState.value.memories)
                store.saveReminders(_uiState.value.reminders)
                store.saveHistory(_uiState.value.actionHistory)
                store.savePendingActions(_uiState.value.pendingActions)
                refreshProactiveSuggestions()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val assistant = ChatMessage(
                    sender = Sender.AURA,
                    text = error.message ?: "I could not complete that request."
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistant,
                        isBusy = false,
                        streamingPreview = ""
                    )
                }
                store.saveMessages(_uiState.value.messages)
            }
        }
    }

    fun approveAction(action: ButlerAction) {
        if (action.isExpired()) {
            _uiState.update {
                it.copy(
                    pendingActions = it.pendingActions.filterNot { item -> item.id == action.id },
                    transientMessage = "That approval expired. Ask AURA to prepare the action again."
                )
            }
            store.savePendingActions(_uiState.value.pendingActions)
            viewModelScope.launch {
                audit.record(
                    "action.expired",
                    mapOf("kind" to action.kind.name, "title" to action.title)
                )
            }
            resolveProactiveActionAfterApproval(
                action,
                completed = false,
                detail = "Approval expired. The action was not executed."
            )
            resumePlannerAfterApproval(
                action,
                approved = false,
                detail = "Approval expired. The action was not executed."
            )
            return
        }

        if (capabilityPolicy.evaluate(action, confirmed = true) == PolicyDecision.DENY) {
            _uiState.update {
                it.copy(
                    pendingActions = it.pendingActions.filterNot { item -> item.id == action.id },
                    transientMessage = "AURA blocked this action under the local safety policy."
                )
            }
            store.savePendingActions(_uiState.value.pendingActions)
            viewModelScope.launch {
                audit.record(
                    "action.denied",
                    mapOf("kind" to action.kind.name, "title" to action.title)
                )
            }
            resolveProactiveActionAfterApproval(
                action,
                completed = false,
                detail = "The local capability policy denied the action."
            )
            resumePlannerAfterApproval(
                action,
                approved = false,
                detail = "The local capability policy denied the action."
            )
            return
        }

        if (
            action.kind == ActionKind.REMINDER &&
            !_uiState.value.settings.backgroundReminders
        ) {
            _uiState.update {
                it.copy(
                    transientMessage =
                        "Enable background reminders in Settings before scheduling this reminder."
                )
            }
            return
        }

        when (val result = actionExecutor.execute(action)) {
            is ExecutionResult.Success -> {
                _uiState.update { current ->
                    val reminders = result.reminder?.let { current.reminders + it }
                        ?: current.reminders
                    val history = (
                        current.actionHistory + ActionHistoryItem(
                            title = action.title,
                            detail = result.detail,
                            approved = true
                        )
                    ).takeLast(100)
                    current.copy(
                        pendingActions = current.pendingActions.filterNot { it.id == action.id },
                        reminders = reminders,
                        actionHistory = history,
                        transientMessage = result.detail
                    )
                }
                store.saveReminders(_uiState.value.reminders)
                store.saveHistory(_uiState.value.actionHistory)
                store.savePendingActions(_uiState.value.pendingActions)
                viewModelScope.launch {
                    audit.record(
                        "action.approved",
                        mapOf(
                            "kind" to action.kind.name,
                            "title" to action.title,
                            "detail" to result.detail
                        )
                    )
                }
                resolveProactiveActionAfterApproval(
                    action,
                    completed = true,
                    detail = result.detail
                )
                resumePlannerAfterApproval(
                    action,
                    approved = true,
                    detail = result.detail
                )
            }

            is ExecutionResult.Failure -> {
                _uiState.update {
                    it.copy(
                        pendingActions = it.pendingActions.filterNot { item ->
                            item.id == action.id
                        },
                        transientMessage = result.reason
                    )
                }
                store.savePendingActions(_uiState.value.pendingActions)
                viewModelScope.launch {
                    audit.record(
                        "action.failed",
                        mapOf(
                            "kind" to action.kind.name,
                            "title" to action.title,
                            "reason" to result.reason
                        )
                    )
                }
                resolveProactiveActionAfterApproval(
                    action,
                    completed = false,
                    detail = result.reason
                )
                resumePlannerAfterApproval(action, approved = false, detail = result.reason)
            }
        }
    }

    fun beginPrivateAnalysisAction(action: ButlerAction) {
        _uiState.update { current ->
            current.copy(
                pendingActions = current.pendingActions.filterNot { it.id == action.id },
                actionHistory = (
                    current.actionHistory + ActionHistoryItem(
                        title = action.title,
                        detail = "User approved the private selection or screen-sharing step.",
                        approved = true
                    )
                ).takeLast(100),
                transientMessage = "Android will now ask you what to share."
            )
        }
        store.savePendingActions(_uiState.value.pendingActions)
        store.saveHistory(_uiState.value.actionHistory)
    }

    fun analyzeImageUri(
        uri: Uri,
        prompt: String = "Describe this image, read visible text and explain important details."
    ) {
        analyzePreparedImage(
            load = {
                withContext(Dispatchers.IO) {
                    ImageInputReader.fromUri(getApplication(), uri)
                }
            },
            prompt = prompt,
            sourceLabel = "selected image"
        )
    }

    fun attachDocumentUri(
        uri: Uri,
        prompt: String = ""
    ) {
        if (_uiState.value.isBusy) return
        val app = getApplication<Application>()
        if (DocumentInputReader.isImage(app, uri)) {
            analyzeImageUri(
                uri = uri,
                prompt = prompt.ifBlank {
                    "Describe this image, read visible text and explain important details."
                }
            )
            return
        }

        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                isBusy = true,
                streamingPreview = "",
                input = "",
                selectedTab = AppTab.BUTLER,
                transientMessage = "Opening the selected document privately…"
            )
        }
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    DocumentInputReader.fromUri(app, uri, prompt)
                }
                val userText = buildString {
                    append("Attached document: ${document.displayName}")
                    if (prompt.isNotBlank()) append("\nRequest: ${prompt.trim().take(4_000)}")
                }
                val userMessage = ChatMessage(sender = Sender.USER, text = userText)
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages + userMessage,
                        transientMessage = "Analyzing ${document.displayName} with private Qwen…"
                    )
                }
                store.saveMessages(_uiState.value.messages)

                val result = privateDocumentAnalyzer.analyze(
                    document = document,
                    prompt = prompt,
                    settings = settings
                )
                val assistant = ChatMessage(
                    sender = Sender.AURA,
                    text = result.answer,
                    provider = "qwen-private-document",
                    intent = "document_analysis"
                )
                val docMemories = result.chunks.take(10).mapIndexed { index, chunk ->
                    MemoryItem(
                        label = "[DOC] ${document.displayName.take(72)} #${index + 1}",
                        value = chunk
                    )
                }
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages + assistant,
                        memories = mergeMemories(current.memories, docMemories),
                        isBusy = false,
                        streamingPreview = "",
                        transientMessage = buildString {
                            append("Private document analysis complete")
                            if (result.pageCount > 0) append(" • ${result.pageCount} page(s)")
                            append(" • ${result.extractedChars} characters indexed locally")
                        },
                        speechText = if (current.settings.spokenReplies) {
                            SpeechTextSanitizer.clean(result.answer.take(2_600))
                        } else null,
                        speechSequence = current.speechSequence + 1
                    )
                }
                store.saveMessages(_uiState.value.messages)
                store.saveMemories(_uiState.value.memories)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "AURA could not analyze that document."
                _uiState.update { current ->
                    current.copy(
                        messages = current.messages + ChatMessage(
                            sender = Sender.AURA,
                            text = message,
                            provider = "document-error",
                            intent = "document_analysis"
                        ),
                        isBusy = false,
                        streamingPreview = "",
                        transientMessage = message
                    )
                }
                store.saveMessages(_uiState.value.messages)
            }
        }
    }

    fun analyzeScreenFile(
        path: String,
        prompt: String = "Read and explain this screen. Identify visible text, errors, controls and useful next steps."
    ) {
        analyzePreparedImage(
            load = {
                withContext(Dispatchers.IO) {
                    try {
                        ImageInputReader.fromFile(path)
                    } finally {
                        runCatching { java.io.File(path).delete() }
                    }
                }
            },
            prompt = prompt,
            sourceLabel = "screen"
        )
    }

    fun saveScreenshotFile(path: String) {
        if (path.isBlank()) return
        _uiState.update {
            it.copy(
                isBusy = true,
                transientMessage = "Saving screenshot…"
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = java.io.File(path)
                    require(source.isFile) { "Captured screen file is missing." }
                    val fileName = "AURA_${System.currentTimeMillis()}.jpg"
                    val resolver = getApplication<Application>().contentResolver
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/AURA"
                            )
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            values
                        ) ?: error("Android could not create the screenshot entry.")
                        resolver.openOutputStream(uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Android could not open screenshot storage.")
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        uri.toString()
                    } else {
                        val folder = java.io.File(
                            getApplication<Application>().getExternalFilesDir(
                                Environment.DIRECTORY_PICTURES
                            ),
                            "AURA"
                        ).apply { mkdirs() }
                        val target = java.io.File(folder, fileName)
                        source.copyTo(target, overwrite = true)
                        target.absolutePath
                    }
                }.also {
                    runCatching { java.io.File(path).delete() }
                }
            }
            _uiState.update { current ->
                current.copy(
                    isBusy = false,
                    transientMessage = result.fold(
                        onSuccess = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                "Screenshot saved in Pictures/AURA."
                            } else {
                                "Screenshot saved in AURA's private Pictures folder."
                            }
                        },
                        onFailure = { error ->
                            error.message ?: "The screenshot could not be saved."
                        }
                    )
                )
            }
        }
    }

    fun clearNotificationHistory() {
        val removed = runCatching { notificationVault.clear() }.getOrDefault(0)
        _uiState.update {
            it.copy(
                notificationVaultCount = 0,
                transientMessage = "Removed $removed encrypted notification history item" +
                    (if (removed == 1) "." else "s.")
            )
        }
    }

    fun refreshAnalyzerStatus() {
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                notificationAccessGranted = NotificationAccessState.isGranted(app),
                notificationVaultCount = runCatching {
                    notificationVault.count()
                }.getOrDefault(it.notificationVaultCount),
                ownerVoiceEnrolled = OwnerVoiceGuard.isEnrolled(app)
            )
        }
    }

    private fun analyzePreparedImage(
        load: suspend () -> ImageInputReader.PreparedImage,
        prompt: String,
        sourceLabel: String
    ) {
        if (_uiState.value.imageAnalysisBusy) return
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                imageAnalysisBusy = true,
                isBusy = true,
                selectedTab = AppTab.BUTLER,
                transientMessage = "Analyzing the $sourceLabel…"
            )
        }

        viewModelScope.launch {
            val result = runCatchingCancellable {
                val prepared = load()
                var privateError: Exception? = null

                if (privateVisionAnalyzer.isConfigured(settings)) {
                    try {
                        val answer = privateVisionAnalyzer.analyzeImage(
                            imageBytes = prepared.bytes,
                            mimeType = prepared.mimeType,
                            prompt = prompt,
                            settings = settings
                        )
                        return@runCatchingCancellable answer to "gemma-private-vision"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        privateError = error
                    }
                }

                if (
                    settings.aiEnabled &&
                    settings.geminiApiKey.isNotBlank() &&
                    HybridAiProvider.publicProviderAllowed(
                        settings,
                        HybridAiProvider.PROVIDER_GEMINI
                    )
                ) {
                    val answer = geminiAnalyzer.analyzeScreen(
                        imageBytes = prepared.bytes,
                        mimeType = prepared.mimeType,
                        prompt = prompt,
                        settings = settings
                    )
                    answer to "gemini-vision-fallback"
                } else {
                    throw IllegalStateException(
                        privateError?.message
                            ?: "Add your AURA cloud token and enable private Gemma vision in Settings."
                    )
                }
            }
            val outcome = result.getOrElse { error ->
                (error.message ?: "AURA could not analyze the $sourceLabel.") to
                    "vision-error"
            }
            val displayText = AiResponseFormatter.plainText(outcome.first)
            val assistant = ChatMessage(
                sender = Sender.AURA,
                text = displayText,
                provider = outcome.second,
                intent = "analyze_image"
            )
            _uiState.update { current ->
                current.copy(
                    messages = current.messages + assistant,
                    imageAnalysisBusy = false,
                    isBusy = false,
                    transientMessage = if (result.isSuccess) {
                        "$sourceLabel analysis complete."
                    } else {
                        "The $sourceLabel could not be analyzed."
                    },
                    speechText = if (
                        result.isSuccess && current.settings.spokenReplies
                    ) {
                        SpeechTextSanitizer.clean(displayText)
                            .take(2_600)
                    } else {
                        null
                    },
                    speechSequence = current.speechSequence + 1
                )
            }
            store.saveMessages(_uiState.value.messages)
        }
    }

    fun rejectAction(action: ButlerAction) {
        _uiState.update { current ->
            val history = (
                current.actionHistory + ActionHistoryItem(
                    title = action.title,
                    detail = "Cancelled before execution.",
                    approved = false
                )
            ).takeLast(100)
            current.copy(
                pendingActions = current.pendingActions.filterNot { it.id == action.id },
                actionHistory = history,
                transientMessage = "Cancelled. Nothing was changed."
            )
        }
        store.saveHistory(_uiState.value.actionHistory)
        store.savePendingActions(_uiState.value.pendingActions)
        viewModelScope.launch {
            audit.record(
                "action.cancelled",
                mapOf("kind" to action.kind.name, "title" to action.title)
            )
        }
        resolveProactiveActionAfterApproval(
            action,
            completed = false,
            detail = "The user declined the proposed action."
        )
        resumePlannerAfterApproval(
            action,
            approved = false,
            detail = "The user declined the proposed action."
        )
    }

    fun removeMemory(item: MemoryItem) {
        _uiState.update {
            it.copy(
                memories = it.memories.filterNot { memory -> memory.id == item.id },
                transientMessage = "Memory removed."
            )
        }
        store.saveMemories(_uiState.value.memories)
    }

    fun removeReminder(id: String) {
        ReminderWorker.cancel(getApplication(), id)
        _uiState.update {
            it.copy(
                reminders = it.reminders.filterNot { reminder -> reminder.id == id },
                transientMessage = "Reminder cancelled."
            )
        }
        store.saveReminders(_uiState.value.reminders)
    }

    fun enrollOwnerVoice() {
        if (_uiState.value.ownerVoiceEnrollmentBusy) return
        _uiState.update {
            it.copy(
                ownerVoiceEnrollmentBusy = true,
                ownerVoiceStatus = "Preparing private owner voice registration…",
                transientMessage = "Voice registration uses five quality-checked local samples. No raw audio is saved."
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val appContext = getApplication<Application>()
            val wakeWasEnabled = store.loadSettings().wakeWordEnabled
            if (wakeWasEnabled) {
                // WakeWordService owns the microphone while KWS is active. Pause it
                // during enrollment so the owner samples are not competing
                // with the persistent wake AudioRecord, then restore it below.
                WakeWordService.setEnabled(appContext, false)
                kotlinx.coroutines.delay(450L)
            }

            val result = try {
                OwnerVoiceGuard.enrollFromMicrophone(appContext) { progress ->
                    _uiState.update { state -> state.copy(ownerVoiceStatus = progress) }
                }
            } finally {
                if (wakeWasEnabled) {
                    kotlinx.coroutines.delay(250L)
                    WakeWordService.setEnabled(appContext, true)
                }
            }
            if (result.success) {
                val updatedSettings = store.loadSettings().copy(
                    ownerVoiceLockEnabled = true,
                    ownerVoiceThreshold = OwnerVoiceGuard.DEFAULT_THRESHOLD
                )
                store.saveSettings(updatedSettings)
                _uiState.update {
                    it.copy(
                        settings = updatedSettings,
                        ownerVoiceEnrolled = true,
                        ownerVoiceEnrollmentBusy = false,
                        ownerVoiceStatus = result.message,
                        transientMessage = "Owner voice registered. Voice Lock is now ON."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        ownerVoiceEnrollmentBusy = false,
                        ownerVoiceStatus = result.message,
                        transientMessage = result.message
                    )
                }
            }
        }
    }

    fun clearOwnerVoice() {
        OwnerVoiceGuard.clearEnrollment(getApplication())
        val updatedSettings = store.loadSettings().copy(ownerVoiceLockEnabled = false)
        store.saveSettings(updatedSettings)
        _uiState.update {
            it.copy(
                settings = updatedSettings,
                ownerVoiceEnrolled = false,
                ownerVoiceEnrollmentBusy = false,
                ownerVoiceStatus = "Owner voice profile removed. Voice Lock is OFF.",
                transientMessage = "Owner voice profile removed from this phone."
            )
        }
    }

    fun saveSettings(settings: ButlerSettings) {
        val normalized = settings.copy(
            preferredName = settings.preferredName.trim().ifBlank { "Diganta" },
            formOfAddress = settings.formOfAddress.trim().ifBlank { "Diganta" },
            homeCity = settings.homeCity.trim().ifBlank { "Kadra, Chandrakona Road" },
            homeLatitude = settings.homeLatitude.coerceIn(-90.0, 90.0),
            homeLongitude = settings.homeLongitude.coerceIn(-180.0, 180.0),
            ownerVoiceLockEnabled = settings.ownerVoiceLockEnabled &&
                OwnerVoiceGuard.isEnrolled(getApplication()),
            ownerVoiceThreshold = settings.ownerVoiceThreshold.coerceIn(
                OwnerVoiceGuard.DEFAULT_THRESHOLD,
                0.80f
            ),
            speechLanguage = settings.speechLanguage.takeIf {
                it in setOf("auto", "en", "bn", "hi")
            } ?: "auto",
            aiProvider = HybridAiProvider.normalizeProvider(settings.aiProvider),
            geminiApiKey = settings.geminiApiKey.trim().take(260),
            geminiModel = settings.geminiModel.takeIf {
                it in setOf(
                    GeminiAiProvider.MODEL_FAST,
                    GeminiAiProvider.MODEL_QUALITY
                )
            } ?: GeminiAiProvider.MODEL_FAST,
            groqApiKey = settings.groqApiKey.trim().take(220),
            aiModel = settings.aiModel.takeIf {
                it in setOf(
                    GroqAiProvider.MODEL_FAST,
                    GroqAiProvider.MODEL_QUALITY
                )
            } ?: GroqAiProvider.MODEL_FAST,
            localAiEndpoint = SafeEndpointValidator.normalizeOrDefault(
                settings.localAiEndpoint
            ),
            localAiModel = settings.localAiModel.trim()
                .ifBlank { OpenAiCompatibleProvider.DEFAULT_MODEL }
                .take(120),
            localAiApiKey = settings.localAiApiKey.trim().take(260),
            privateVisionEndpoint = SafeEndpointValidator.normalizeOrDefault(
                settings.privateVisionEndpoint,
                PrivateVisionProvider.DEFAULT_ENDPOINT
            ),
            privateVisionModel = settings.privateVisionModel.trim()
                .ifBlank { PrivateVisionProvider.DEFAULT_MODEL }
                .take(120),
            researchEndpoint = SafeEndpointValidator.normalizeOrDefault(
                settings.researchEndpoint,
                DeepResearchProvider.DEFAULT_ENDPOINT
            ),
            researchMode = DeepResearchProvider.normalizeMode(settings.researchMode),
            responseStyle = ButlerSystemPrompt.normalizeStyle(settings.responseStyle),
            notificationRetentionDays = settings.notificationRetentionDays.coerceIn(1, 30),
            butlerBanterLevel = settings.butlerBanterLevel.coerceIn(0, 2)
        )
        store.saveSettings(normalized)
        _uiState.update {
            it.copy(
                settings = normalized,
                transientMessage = if (
                    normalized.aiEnabled &&
                    HybridAiProvider.configuredProviderLabel(normalized) != "local-only"
                ) {
                    "Settings saved. Local commands stay offline; optional AI route: " +
                        HybridAiProvider.configuredProviderLabel(normalized) + "."
                } else {
                    "Settings saved. AURA will use local memory, phone commands and keyless internet tools."
                }
            )
        }
        refreshProactiveSuggestions()
    }

    fun refreshFromStore() {
        _uiState.update {
            it.copy(
                messages = store.loadMessages().ifEmpty { it.messages },
                memories = store.loadMemories(),
                reminders = store.loadReminders(),
                pendingActions = store.loadPendingActions(),
                actionHistory = store.loadHistory(),
                settings = store.loadSettings(),
                notificationAccessGranted = NotificationAccessState.isGranted(
                    getApplication()
                ),
                notificationVaultCount = runCatching {
                    notificationVault.count()
                }.getOrDefault(it.notificationVaultCount)
            )
        }
        refreshProactiveSuggestions()
    }

    fun clearConversation() {
        val greeting = ChatMessage(
            sender = Sender.AURA,
            text = "A fresh conversation is ready. What may I take care of?"
        )
        _uiState.update { it.copy(messages = listOf(greeting), pendingActions = emptyList()) }
        store.clearConversation()
        store.saveMessages(listOf(greeting))
        store.savePendingActions(emptyList())
    }

    fun setListening(value: Boolean) {
        _uiState.update { it.copy(isListening = value) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(transientMessage = message) }
    }

    fun consumeTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    private suspend fun proactiveEngine(): ProactiveEngine = withContext(Dispatchers.IO) {
        val application = getApplication<Application>()
        val currentSettings = _uiState.value.settings
        val semantic = if (currentSettings.semanticMemoryEnabled) butler.semanticMemoryForProactive() else null
        val existing = proactiveEngineInstance
        if (existing != null) {
            existing.updateSettings(currentSettings)
            existing.updateSemanticMemory(semantic)
            existing
        } else {
            synchronized(proactiveEngineLock) {
                proactiveEngineInstance?.let { current ->
                    current.updateSettings(currentSettings)
                    current.updateSemanticMemory(semantic)
                    return@withContext current
                }
                ProactiveEngine(
                    aiProvider = HybridAiProvider(),
                    semanticMemory = semantic,
                    settings = currentSettings,
                    contextProvider = AndroidProactiveContextProvider(application) {
                        val state = _uiState.value
                        ConversationContext(
                            recentMessages = state.messages.takeLast(16),
                            memories = state.memories,
                            settings = state.settings
                        )
                    },
                    persistence = EncryptedProactivePersistence(application),
                    actionHandler = AndroidProactiveActionHandler(application, planningRuntime)
                ).also { engine ->
                    proactiveEngineInstance = engine
                }
            }
        }
    }

    fun refreshProactiveSuggestions() {
        if (!_uiState.value.settings.proactiveSuggestionsEnabled) {
            AndroidProactiveScheduler.cancel(getApplication())
            _uiState.update { it.copy(proactiveSuggestions = emptyList()) }
            return
        }
        AndroidProactiveScheduler.ensureScheduled(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = proactiveEngine()
                val state = _uiState.value
                val context = ConversationContext(
                    recentMessages = state.messages.takeLast(16),
                    memories = state.memories,
                    settings = state.settings
                )
                engine.refreshNow()
                val suggestions = engine.getSuggestions(context)
                _uiState.update { it.copy(proactiveSuggestions = suggestions) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        proactiveSuggestions = emptyList(),
                        transientMessage = "Proactive suggestions are temporarily unavailable: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
            }
        }
    }

    fun acceptProactiveSuggestion(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = proactiveEngine()
                val state = _uiState.value
                val context = ConversationContext(
                    recentMessages = state.messages.takeLast(16),
                    memories = state.memories,
                    settings = state.settings
                )
                val result = engine.acceptSuggestionDetailed(id, context)
                if (result.pendingApproval != null) {
                    _uiState.update { current ->
                        current.copy(
                            pendingActions = (
                                current.pendingActions.filterNot { it.id == result.pendingApproval.id } +
                                    result.pendingApproval
                            ).takeLast(12),
                            transientMessage = "Review and approve the suggested action before AURA executes it."
                        )
                    }
                    store.savePendingActions(_uiState.value.pendingActions)
                } else if (result.completed && !result.detail.isNullOrBlank()) {
                    val message = ChatMessage(
                        sender = Sender.AURA,
                        text = result.detail,
                        provider = "proactive",
                        intent = "proactive_accepted"
                    )
                    _uiState.update { current ->
                        current.copy(
                            messages = current.messages + message,
                            transientMessage = result.detail
                        )
                    }
                    store.saveMessages(_uiState.value.messages)
                } else if (!result.error.isNullOrBlank()) {
                    _uiState.update { it.copy(transientMessage = result.error) }
                }
                _uiState.update { it.copy(proactiveSuggestions = engine.getSuggestions(context)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(transientMessage = "The proactive action could not be completed: " +
                        (error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    fun dismissProactiveSuggestion(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = proactiveEngine()
                engine.dismissSuggestion(id)
                val state = _uiState.value
                _uiState.update {
                    it.copy(proactiveSuggestions = engine.getSuggestions(
                        ConversationContext(
                            recentMessages = state.messages.takeLast(16),
                            memories = state.memories,
                            settings = state.settings
                        )
                    ))
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(transientMessage = error.message ?: "Could not dismiss suggestion.") }
            }
        }
    }

    private fun resolveProactiveActionAfterApproval(
        action: ButlerAction,
        completed: Boolean,
        detail: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = proactiveEngine()
                if (engine.resolveActionApproval(action.id, completed, detail)) {
                    val state = _uiState.value
                    val context = ConversationContext(
                        recentMessages = state.messages.takeLast(16),
                        memories = state.memories,
                        settings = state.settings
                    )
                    _uiState.update { it.copy(proactiveSuggestions = engine.getSuggestions(context)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(transientMessage = "Could not update the proactive suggestion: " +
                        (error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private sealed interface PlanningCommand {
        data class Create(val goal: String) : PlanningCommand
        data class Execute(val goal: String) : PlanningCommand
        data class Resume(val planId: String) : PlanningCommand
    }

    private fun parsePlanningCommand(message: String): PlanningCommand? {
        val normalized = message.trim()
        val patterns = listOf(
            "plan and execute:" to { value: String -> PlanningCommand.Execute(value) },
            "create and execute plan:" to { value: String -> PlanningCommand.Execute(value) },
            "create plan:" to { value: String -> PlanningCommand.Create(value) },
            "plan:" to { value: String -> PlanningCommand.Create(value) }
        )
        patterns.firstOrNull { (prefix, _) ->
            normalized.startsWith(prefix, ignoreCase = true)
        }?.let { (prefix, factory) ->
            val value = normalized.substring(prefix.length).trim()
            return value.takeIf(String::isNotBlank)?.let(factory)
        }

        val resumePrefix = "resume plan:"
        if (normalized.startsWith(resumePrefix, ignoreCase = true)) {
            val id = normalized.substring(resumePrefix.length).trim()
            return id.takeIf { it.matches(Regex("[A-Za-z0-9._-]{8,100}")) }
                ?.let { PlanningCommand.Resume(it) }
        }
        return null
    }

    private suspend fun executePlanningCommand(
        command: PlanningCommand,
        context: ConversationContext
    ): ButlerReply {
        val planner = planningRuntime.planner
        return when (command) {
            is PlanningCommand.Create -> {
                val plan = planner.createPlanFromGoal(command.goal, context)
                ButlerReply(
                    assistantMessage = "Created durable plan: ${plan.title}\n" +
                        "Plan ID: ${plan.id}\n" +
                        "Tasks: ${plan.tasks.size}\n" +
                        "Nothing has been executed. Use 'plan and execute: <goal>' to start it.",
                    speechText = "Your plan has been created. Nothing has been executed yet.",
                    pendingActions = emptyList(),
                    memoryWrites = emptyList(),
                    provider = "local-planner",
                    intent = "plan_created"
                )
            }
            is PlanningCommand.Execute -> {
                val plan = planner.createPlanFromGoal(command.goal, context)
                val result = planner.executePlan(plan.id, context)
                planningReply(plan.id, plan.title, result)
            }
            is PlanningCommand.Resume -> {
                val plan = planningRuntime.getPlan(command.planId)
                    ?: return ButlerReply(
                        assistantMessage = "I couldn't find plan ${command.planId}.",
                        speechText = "I couldn't find that plan.",
                        pendingActions = emptyList(),
                        memoryWrites = emptyList(),
                        provider = "local-planner",
                        intent = "plan_not_found"
                    )
                val result = planner.executePlan(plan.id, context)
                planningReply(plan.id, plan.title, result)
            }
        }
    }

    private fun planningReply(
        planId: String,
        title: String,
        result: TaskPlanner.ExecutionResult
    ): ButlerReply {
        val approval = result.pendingApproval
        val message = buildString {
            append("Plan: ").append(title).append("\n")
            append("Plan ID: ").append(planId).append("\n")
            append("Completed tasks: ").append(result.completedTasks)
                .append(" | Failed tasks: ").append(result.failedTasks).append("\n")
            if (approval != null) {
                append("Paused for your approval: ").append(approval.title)
                    .append(". Review the approval card below.")
            } else {
                append(result.finalOutput ?: result.errorSummary ?: "The plan has no further work.")
            }
        }
        return ButlerReply(
            assistantMessage = message,
            speechText = if (approval != null) {
                "The plan is paused and waiting for your approval."
            } else {
                result.finalOutput?.take(2_000) ?: "The plan run has finished."
            },
            pendingActions = listOfNotNull(approval),
            memoryWrites = emptyList(),
            provider = "local-planner",
            intent = if (approval != null) "plan_approval" else "plan_result"
        )
    }

    private fun resumePlannerAfterApproval(
        action: ButlerAction,
        approved: Boolean,
        detail: String
    ) {
        val planId = action.payload[ProductionTaskExecutor.PLAN_ID_ARGUMENT] ?: return
        val taskId = action.payload[ProductionTaskExecutor.TASK_ID_ARGUMENT] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val planner = planningRuntime.planner
                val resolved = planner.resolveApproval(
                    planId = planId,
                    taskId = taskId,
                    actionId = action.id,
                    approved = approved,
                    executionDetail = detail
                )
                if (!resolved) return@launch

                val current = _uiState.value
                val context = ConversationContext(
                    recentMessages = current.messages.takeLast(16),
                    memories = current.memories,
                    settings = current.settings
                )
                val result = planner.executePlan(planId, context)
                val plan = planningRuntime.getPlan(planId)
                val approval = result.pendingApproval
                if (approval != null) {
                    _uiState.update { state ->
                        state.copy(
                            pendingActions = (state.pendingActions.filterNot { it.id == approval.id } +
                                approval).takeLast(12),
                            transientMessage = "The plan needs another approval."
                        )
                    }
                    store.savePendingActions(_uiState.value.pendingActions)
                } else {
                    val statusText = if (result.success) "Plan completed." else "Plan stopped."
                    val message = ChatMessage(
                        sender = Sender.AURA,
                        text = buildString {
                            append(statusText).append("\n")
                            append("Plan ID: ").append(planId).append("\\n")
                            append(result.finalOutput ?: result.errorSummary ?: plan?.status?.name.orEmpty())
                        },
                        provider = "local-planner",
                        intent = "plan_resumed"
                    )
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + message,
                            transientMessage = statusText,
                            speechText = if (state.settings.spokenReplies) {
                                SpeechTextSanitizer.clean(message.text).take(2_600)
                            } else null,
                            speechSequence = state.speechSequence + 1
                        )
                    }
                    store.saveMessages(_uiState.value.messages)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(transientMessage = "The saved plan could not resume: " +
                        (error.message ?: error.javaClass.simpleName))
                }
            }
        }
    }

    private fun resolvedAssistantText(
        original: String,
        hadActions: Boolean,
        directMessages: List<String>,
        approvalActions: List<ButlerAction>
    ): String {
        val parts = mutableListOf<String>()

        if (!hadActions || approvalActions.isNotEmpty()) {
            original.trim().takeIf(String::isNotBlank)?.let(parts::add)
        }
        parts += directMessages.filter(String::isNotBlank)

        if (approvalActions.isNotEmpty()) {
            parts += "I prepared ${approvalActions.size} approval card. Review it below."
        }

        return parts
            .distinct()
            .joinToString(" ")
            .ifBlank { original }
    }

    override fun onCleared() {
        proactiveEngineInstance?.stop()
        proactiveEngineInstance = null
        if (planningRuntimeDelegate.isInitialized()) planningRuntime.close()
        super.onCleared()
    }

    private fun mergeMemories(
        current: List<MemoryItem>,
        writes: List<MemoryItem>
    ): List<MemoryItem> {
        var result = current
        writes.forEach { incoming ->
            result = result.filterNot {
                it.label.equals(incoming.label, ignoreCase = true)
            } + incoming
        }
        return result.takeLast(1_500)
    }
}
