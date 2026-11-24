package `in`.diganta.aurabutler.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.AddAlert
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Fastfood
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.ScreenSearchDesktop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import `in`.diganta.aurabutler.screen.ScreenCaptureService
import `in`.diganta.aurabutler.ButlerViewModel
import `in`.diganta.aurabutler.background.WakeWordService
import `in`.diganta.aurabutler.domain.ActionSafetyPolicy
import `in`.diganta.aurabutler.model.ActionHistoryItem
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.AppTab
import `in`.diganta.aurabutler.model.AuraUiState
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.intelligence.ProactiveEngine
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.ReminderItem
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.ui.theme.AuraGold
import `in`.diganta.aurabutler.ui.theme.AuraGreen
import `in`.diganta.aurabutler.voice.Speaker
import `in`.diganta.aurabutler.voice.VoiceTimingDiagnostics
import `in`.diganta.aurabutler.voice.VoiceManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuraApp(viewModel: ButlerViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val snackbarHost = remember { SnackbarHostState() }
    val speaker = remember { Speaker(context) }
    val voiceManager = remember { VoiceManager(context) }
    var clearDialogVisible by remember { mutableStateOf(false) }
    var notificationAction by remember { mutableStateOf<ButlerAction?>(null) }
    var cameraAction by remember { mutableStateOf<ButlerAction?>(null) }
    var pendingWakeSettings by remember { mutableStateOf<ButlerSettings?>(null) }
    var pendingImageAction by remember { mutableStateOf<ButlerAction?>(null) }
    var pendingScreenAction by remember { mutableStateOf<ButlerAction?>(null) }
    var pendingScreenPrompt by remember { mutableStateOf("") }
    var pendingScreenMode by remember { mutableStateOf("analyze") }
    var clearAnalyzerDialogVisible by remember { mutableStateOf(false) }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.start(state.settings.speechLanguage)
        } else {
            viewModel.showMessage("Microphone permission is required for voice input.")
        }
    }

    val wakePermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val settings = pendingWakeSettings
        pendingWakeSettings = null
        val microphoneGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        if (settings != null && microphoneGranted) {
            viewModel.saveSettings(settings)
            WakeWordService.setEnabled(context, true)
        } else {
            viewModel.showMessage(
                "Microphone permission is required for “Aura” wake listening."
            )
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = notificationAction
        notificationAction = null
        if (granted && action != null) {
            viewModel.approveAction(action)
        } else {
            viewModel.showMessage(
                "Notification permission is required before AURA can deliver reminders."
            )
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = cameraAction
        cameraAction = null
        if (granted && action != null) {
            viewModel.approveAction(action)
        } else {
            viewModel.showMessage(
                "Camera permission is required to control the flashlight."
            )
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val action = pendingImageAction
        pendingImageAction = null
        if (uri != null && action != null) {
            viewModel.beginPrivateAnalysisAction(action)
            viewModel.analyzeImageUri(
                uri = uri,
                prompt = action.payload["prompt"].orEmpty()
            )
        } else if (action != null) {
            viewModel.showMessage("Image selection was cancelled.")
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.attachDocumentUri(uri = uri, prompt = state.input)
        } else {
            viewModel.showMessage("File selection was cancelled.")
        }
    }

    val screenCapturePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingScreenAction
        pendingScreenAction = null
        if (
            action != null &&
            result.resultCode == Activity.RESULT_OK &&
            result.data != null
        ) {
            viewModel.beginPrivateAnalysisAction(action)
            pendingScreenPrompt = action.payload["prompt"].orEmpty()
            pendingScreenMode = action.payload["mode"].orEmpty().ifBlank { "analyze" }
            val captureWidth = (configuration.screenWidthDp * density.density).roundToInt()
            val captureHeight = (configuration.screenHeightDp * density.density).roundToInt()
            val captureDensityDpi = (density.density * 160f).roundToInt()
            ScreenCaptureService.start(
                context = context,
                resultCode = result.resultCode,
                resultData = result.data!!,
                width = captureWidth,
                height = captureHeight,
                density = captureDensityDpi,
                captureDelayMs = 5_000L
            )
            // Put AURA behind the target app instead of immediately capturing its
            // own consent/result screen. During the countdown the owner can switch
            // apps normally, including from Android Studio screen mirroring.
            (context as? Activity)?.moveTaskToBack(true)
        } else if (action != null) {
            viewModel.showMessage("Screen sharing was cancelled. Nothing was captured.")
        }
    }

    SideEffect {
        voiceManager.onListeningChanged = viewModel::setListening
        voiceManager.onResult = viewModel::submitVoiceText
        voiceManager.onErrorMessage = viewModel::showMessage
        voiceManager.onStatusMessage = viewModel::showMessage
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    ScreenCaptureService.ACTION_CAPTURE_COMPLETE -> {
                        val path = intent.getStringExtra(
                            ScreenCaptureService.EXTRA_PATH
                        ).orEmpty()
                        if (path.isNotBlank()) {
                            if (pendingScreenMode == "save") {
                                viewModel.saveScreenshotFile(path)
                            } else {
                                viewModel.analyzeScreenFile(
                                    path = path,
                                    prompt = pendingScreenPrompt
                                )
                            }
                            pendingScreenPrompt = ""
                            pendingScreenMode = "analyze"
                        }
                    }

                    ScreenCaptureService.ACTION_CAPTURE_FAILED -> {
                        val message = intent.getStringExtra(
                            ScreenCaptureService.EXTRA_ERROR
                        ).orEmpty().ifBlank { "Screen capture failed." }
                        pendingScreenPrompt = ""
                        pendingScreenMode = "analyze"
                        viewModel.showMessage(message)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_CAPTURE_COMPLETE)
            addAction(ScreenCaptureService.ACTION_CAPTURE_FAILED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()
            speaker.shutdown()
        }
    }

    LaunchedEffect(state.transientMessage) {
        state.transientMessage?.let {
            snackbarHost.showSnackbar(it)
            viewModel.consumeTransientMessage()
        }
    }

    LaunchedEffect(state.speechSequence) {
        state.speechText?.let { text ->
            if (state.settings.wakeWordEnabled) {
                // Route speech through the service so it can release the wake
                // model before Piper allocates its native model.
                WakeWordService.speak(context, text)
            } else {
                speaker.speak(text, state.settings.speechLanguage) {
                    viewModel.showMessage(
                        "AURA could not start either local voice. Raise media volume, then use Test AURA voice in Settings."
                    )
                }
            }
        }
    }

    fun requestVoice() {
        if (state.settings.wakeWordEnabled) {
            WakeWordService.captureNow(context)
            viewModel.showMessage("Listen for the tone, then speak your command.")
            return
        }
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            voiceManager.start(state.settings.speechLanguage)
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun saveSettings(settings: ButlerSettings) {
        if (!settings.wakeWordEnabled) {
            viewModel.saveSettings(settings)
            WakeWordService.setEnabled(context, false)
            return
        }
        val missingPermissions = buildList {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            pendingWakeSettings = settings
            wakePermissions.launch(missingPermissions.toTypedArray())
        } else {
            viewModel.saveSettings(settings)
            WakeWordService.setEnabled(context, true)
        }
    }

    fun approve(action: ButlerAction) {
        if (action.kind == ActionKind.ANALYZE_IMAGE) {
            pendingImageAction = action
            imagePicker.launch("image/*")
            return
        }
        if (
            action.kind == ActionKind.ANALYZE_SCREEN ||
            action.kind == ActionKind.CAPTURE_SCREEN
        ) {
            pendingScreenAction = action
            val manager = context.getSystemService(
                MediaProjectionManager::class.java
            )
            screenCapturePermission.launch(manager.createScreenCaptureIntent())
            return
        }
        if (action.kind == ActionKind.REMINDER && !state.settings.backgroundReminders) {
            viewModel.showMessage(
                "Enable background reminders in Settings before scheduling this reminder."
            )
        } else if (
            action.kind == ActionKind.REMINDER &&
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (
            action.kind == ActionKind.FLASHLIGHT &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraAction = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            viewModel.approveAction(action)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(AuraGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "A",
                                color = AuraGold,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AURA",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Private-first butler • local + private cloud",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (state.selectedTab == AppTab.BUTLER) {
                        IconButton(onClick = { clearDialogVisible = true }) {
                            Icon(Icons.Rounded.ClearAll, contentDescription = "Clear conversation")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    val (label, icon) = when (tab) {
                        AppTab.BUTLER -> "Butler" to Icons.Rounded.AutoAwesome
                        AppTab.ANALYZER -> "Analyze" to Icons.Rounded.Analytics
                        AppTab.PLANS -> "Plans" to Icons.AutoMirrored.Rounded.EventNote
                        AppTab.MEMORY -> "Memory" to Icons.Rounded.Psychology
                        AppTab.SETTINGS -> "Settings" to Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            when (state.selectedTab) {
                AppTab.BUTLER -> ButlerScreen(
                    state = state,
                    onInput = viewModel::setInput,
                    onSend = viewModel::submitCurrentInput,
                    onQuickPrompt = viewModel::submitText,
                    onVoice = ::requestVoice,
                    onStopVoice = voiceManager::stop,
                    onAttach = { documentPicker.launch(arrayOf("*/*")) },
                    onApprove = ::approve,
                    onReject = viewModel::rejectAction,
                    onAcceptSuggestion = viewModel::acceptProactiveSuggestion,
                    onDismissSuggestion = viewModel::dismissProactiveSuggestion
                )

                AppTab.ANALYZER -> AnalyzerScreen(
                    state = state,
                    onPrompt = viewModel::submitText,
                    onRefresh = viewModel::refreshAnalyzerStatus,
                    onOpenNotificationAccess = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                    },
                    onClearHistory = { clearAnalyzerDialogVisible = true }
                )

                AppTab.PLANS -> PlansScreen(
                    reminders = state.reminders,
                    history = state.actionHistory,
                    onRemoveReminder = viewModel::removeReminder
                )

                AppTab.MEMORY -> MemoryScreen(
                    memories = state.memories,
                    onRemove = viewModel::removeMemory
                )

                AppTab.SETTINGS -> SettingsScreen(
                    settings = state.settings,
                    ownerVoiceEnrolled = state.ownerVoiceEnrolled,
                    ownerVoiceEnrollmentBusy = state.ownerVoiceEnrollmentBusy,
                    ownerVoiceStatus = state.ownerVoiceStatus,
                    onEnrollOwnerVoice = viewModel::enrollOwnerVoice,
                    onClearOwnerVoice = viewModel::clearOwnerVoice,
                    onSave = ::saveSettings,
                    onTestVoice = {
                        VoiceTimingDiagnostics.noteAiLatency(context, 0L)
                        viewModel.showMessage(
                            "Preparing AURA's local voice. The timing card in Settings updates when speech finishes."
                        )
                        val testText = when (state.settings.speechLanguage.lowercase()) {
                            "bn" -> "আমি অরা। আপনার সেবায় প্রস্তুত। আমার বাংলা কণ্ঠ কাজ করছে।"
                            "hi" -> "मैं ऑरा हूँ। आपकी सेवा में तैयार हूँ। मेरी हिंदी आवाज़ काम कर रही है।"
                            else -> "At your service, ${state.settings.formOfAddress}. AURA voice is working."
                        }
                        if (state.settings.wakeWordEnabled) {
                            WakeWordService.speak(context, testText)
                        } else {
                            speaker.speak(testText, state.settings.speechLanguage) {
                                viewModel.showMessage(
                                    "Voice test failed. Raise media volume and check that Android Text-to-Speech is enabled."
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    if (clearDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearDialogVisible = false },
            icon = { Icon(Icons.Rounded.ClearAll, contentDescription = null) },
            title = { Text("Start a fresh conversation?") },
            text = {
                Text("This clears chat history and unapproved cards. Personal memory is kept.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearDialogVisible = false
                        viewModel.clearConversation()
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (clearAnalyzerDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearAnalyzerDialogVisible = false },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete notification analysis history?") },
            text = {
                Text(
                    "This permanently removes AURA's encrypted notification previews from this phone. It does not delete notifications inside WhatsApp, Gmail or other apps."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearAnalyzerDialogVisible = false
                        viewModel.clearNotificationHistory()
                    }
                ) {
                    Text("Delete encrypted history")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAnalyzerDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ButlerScreen(
    state: AuraUiState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onQuickPrompt: (String) -> Unit,
    onVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onAttach: () -> Unit,
    onApprove: (ButlerAction) -> Unit,
    onReject: (ButlerAction) -> Unit,
    onAcceptSuggestion: (String) -> Unit,
    onDismissSuggestion: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val totalItems = state.messages.size + state.pendingActions.size +
        state.proactiveSuggestions.size + if (state.isBusy) 1 else 0
    val streamingScrollBucket = state.streamingPreview.length / 96

    LaunchedEffect(totalItems) {
        if (totalItems <= 0 || listState.isScrollInProgress) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = totalItems <= 2 || lastVisible >= (totalItems - 3).coerceAtLeast(0)
        if (nearBottom) listState.animateScrollToItem(totalItems - 1)
    }

    LaunchedEffect(streamingScrollBucket) {
        if (!state.isBusy || totalItems <= 0 || listState.isScrollInProgress) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= (totalItems - 3).coerceAtLeast(0)
        if (nearBottom) {
            listState.scrollToItem(totalItems - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                QuickChip(
                    "Weather",
                    Icons.Rounded.Language
                ) { onQuickPrompt("Tell me the weather at home.") }
            }
            item {
                QuickChip(
                    "Latest news",
                    Icons.Rounded.Language
                ) { onQuickPrompt("Tell me the latest India headlines.") }
            }
            item {
                QuickChip(
                    "Phone status",
                    Icons.Rounded.Memory
                ) { onQuickPrompt("Tell me my phone status.") }
            }
            item {
                QuickChip(
                    "Calculator",
                    Icons.Rounded.AutoAwesome
                ) { onQuickPrompt("Calculate (12 + 8) * 3.") }
            }
            item {
                QuickChip(
                    "Plan my day",
                    Icons.Rounded.Schedule
                ) { onQuickPrompt("Please plan my day and ask only for the missing details.") }
            }
            item {
                QuickChip(
                    "Execute a plan",
                    Icons.Rounded.Schedule
                ) { onQuickPrompt("plan and execute: Organize my day, identify the most important tasks, and ask for approval before any device or external action.") }
            }
            item {
                QuickChip(
                    "Order food",
                    Icons.Rounded.Fastfood
                ) { onQuickPrompt("Help me choose and order food for dinner.") }
            }
            item {
                QuickChip(
                    "Book travel",
                    Icons.Rounded.Train
                ) { onQuickPrompt("Help me book a ticket. Ask for route and date.") }
            }
            item {
                QuickChip(
                    "Remind me",
                    Icons.Rounded.AddAlert
                ) { onQuickPrompt("Create a reminder for me. Ask what and when.") }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(message)
            }
            items(state.proactiveSuggestions, key = { it.id }) { suggestion ->
                ProactiveSuggestionCard(
                    suggestion = suggestion,
                    onAccept = { onAcceptSuggestion(suggestion.id) },
                    onDismiss = { onDismissSuggestion(suggestion.id) }
                )
            }
            items(state.pendingActions, key = { it.id }) { action ->
                ActionApprovalCard(
                    action = action,
                    onApprove = { onApprove(action) },
                    onReject = { onReject(action) }
                )
            }
            if (state.isBusy) {
                item { ThinkingBubble(state.streamingPreview) }
            }
        }

        Composer(
            input = state.input,
            busy = state.isBusy,
            listening = state.isListening,
            onInput = onInput,
            onSend = onSend,
            onVoice = onVoice,
            onStopVoice = onStopVoice,
            onAttach = onAttach
        )
    }
}

@Composable
private fun ProactiveSuggestionCard(
    suggestion: ProactiveEngine.ProactiveSuggestion,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    suggestion.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    suggestion.priority.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                suggestion.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (!suggestion.lastActionError.isNullOrBlank()) {
                Text(
                    suggestion.lastActionError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                Button(onClick = onAccept) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun QuickChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val fromUser = message.sender == Sender.USER
    val segments = remember(message.text) { splitChatSegments(message.text) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.90f),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (fromUser) 20.dp else 5.dp,
                bottomEnd = if (fromUser) 5.dp else 20.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (fromUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                if (!fromUser) {
                    Text(
                        "AURA",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(3.dp))
                }

                segments.forEachIndexed { index, segment ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    when (segment) {
                        is ChatSegment.Prose -> SelectionContainer {
                            Text(
                                segment.text,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        is ChatSegment.Code -> {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 10.dp
                                    )
                                ) {
                                    if (segment.language.isNotBlank()) {
                                        Text(
                                            segment.language,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(5.dp))
                                    }
                                    SelectionContainer {
                                        Text(
                                            segment.text,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface ChatSegment {
    data class Prose(val text: String) : ChatSegment
    data class Code(val language: String, val text: String) : ChatSegment
}

private fun splitChatSegments(raw: String): List<ChatSegment> {
    if (!raw.contains("```")) {
        return listOf(ChatSegment.Prose(raw))
    }

    val result = mutableListOf<ChatSegment>()
    val buffer = StringBuilder()
    var inCode = false
    var language = ""

    fun flush() {
        val value = buffer.toString().trim('\n')
        buffer.setLength(0)
        if (value.isBlank()) return
        result += if (inCode) {
            ChatSegment.Code(language = language, text = value)
        } else {
            ChatSegment.Prose(value)
        }
    }

    raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            flush()
            if (!inCode) {
                language = trimmed.removePrefix("```").trim().take(32)
                inCode = true
            } else {
                inCode = false
                language = ""
            }
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
    }
    flush()

    return result.ifEmpty { listOf(ChatSegment.Prose(raw)) }
}

@Composable
private fun ThinkingBubble(streamingPreview: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (streamingPreview.isBlank()) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(9.dp))
                    Text("Considering the best way to handle that…")
                } else {
                    Text(
                        streamingPreview,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionApprovalCard(
    action: ButlerAction,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val check = remember(action) { ActionSafetyPolicy.check(action) }
    val riskColor = when (action.risk) {
        ActionRisk.LOW -> Color(0xFF2E6B4E)
        ActionRisk.MEDIUM -> Color(0xFF8A6500)
        ActionRisk.HIGH -> MaterialTheme.colorScheme.error
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(actionIcon(action.kind), contentDescription = null)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(action.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Approval required",
                        style = MaterialTheme.typography.labelMedium,
                        color = riskColor
                    )
                }
                Icon(Icons.Rounded.Security, contentDescription = null, tint = riskColor)
            }
            Spacer(Modifier.height(12.dp))
            Text(action.summary)
            if (check.targetLabel.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Target: ${check.targetLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                check.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (check.allowed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onReject) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onApprove, enabled = check.allowed) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (action.kind) {
                            ActionKind.REMINDER -> "Schedule"
                            ActionKind.ANALYZE_SCREEN,
                            ActionKind.ANALYZE_IMAGE -> "Analyze"
                            ActionKind.CAPTURE_SCREEN -> "Capture"
                            ActionKind.CLEAR_AURA_CACHE -> "Clean"
                            ActionKind.COOLING_ASSIST -> "Assist"
                            else -> "Continue"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    busy: Boolean,
    listening: Boolean,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onAttach: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            enabled = !busy,
            placeholder = {
                Text(if (listening) "Listening…" else "Ask AURA to handle something")
            },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            leadingIcon = {
                IconButton(onClick = onAttach, enabled = !busy) {
                    Icon(
                        Icons.Rounded.AttachFile,
                        contentDescription = "Attach file",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            trailingIcon = {
                IconButton(onClick = if (listening) onStopVoice else onVoice) {
                    Icon(
                        if (listening) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = if (listening) "Stop listening" else "Voice input",
                        tint = if (listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.size(52.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun AnalyzerScreen(
    state: AuraUiState,
    onPrompt: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onClearHistory: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeading(
                title = "Unified Analyzer",
                subtitle = "Private notification triage, screen and image understanding, detailed news, research and temporal memory."
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.notificationAccessGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        if (state.notificationAccessGranted) {
                            "Notification access granted"
                        } else {
                            "Notification access required"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.notificationAccessGranted) {
                            "${state.notificationVaultCount} encrypted notification preview" +
                                if (state.notificationVaultCount == 1) {
                                    " is stored on this phone."
                                } else {
                                    "s are stored on this phone."
                                }
                        } else {
                            "Android must grant AURA access before it can read future WhatsApp, mail, Slack, Jira and other notification previews."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenNotificationAccess,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.notificationAccessGranted) {
                                "Review notification access"
                            } else {
                                "Grant notification access"
                            }
                        )
                    }
                }
            }
        }

        item { SectionTitle("Messages, mail and notifications", Icons.Rounded.Notifications) }
        item {
            AnalyzerActionGrid(
                actions = listOf(
                    Triple(
                        "Important",
                        Icons.Rounded.NotificationsActive,
                        "Read my important notifications in detail."
                    ),
                    Triple(
                        "WhatsApp",
                        Icons.Rounded.Sms,
                        "Summarize my WhatsApp messages in detail."
                    ),
                    Triple(
                        "Mail",
                        Icons.Rounded.Article,
                        "Summarize my mail notifications in detail."
                    ),
                    Triple(
                        "All digest",
                        Icons.Rounded.Analytics,
                        "Give me a detailed notification digest."
                    )
                ),
                onPrompt = onPrompt
            )
        }

        item { SectionTitle("Screen, image and music", Icons.Rounded.ImageSearch) }
        item {
            AnalyzerActionGrid(
                actions = listOf(
                    Triple(
                        "Analyze screen",
                        Icons.Rounded.ScreenSearchDesktop,
                        "Analyze this screen."
                    ),
                    Triple(
                        "Analyze image",
                        Icons.Rounded.ImageSearch,
                        "Choose an image and analyze it."
                    ),
                    Triple(
                        "Save screenshot",
                        Icons.Rounded.ScreenSearchDesktop,
                        "Take a screenshot."
                    ),
                    Triple(
                        "Translate screen",
                        Icons.Rounded.Language,
                        "Translate the text on screen to English."
                    ),
                    Triple(
                        "Summarize document",
                        Icons.Rounded.Article,
                        "Summarize the document on screen."
                    ),
                    Triple(
                        "Identify song",
                        Icons.Rounded.MusicNote,
                        "Identify this song."
                    )
                ),
                onPrompt = onPrompt
            )
        }

        item { SectionTitle("Current information", Icons.Rounded.Language) }
        item {
            AnalyzerActionGrid(
                actions = listOf(
                    Triple(
                        "Detailed news",
                        Icons.Rounded.Article,
                        "Tell me today's important news in detail with sources."
                    ),
                    Triple(
                        "Technology",
                        Icons.Rounded.Language,
                        "Give me detailed technology news with sources."
                    ),
                    Triple(
                        "Research",
                        Icons.Rounded.Analytics,
                        "Deep research today's most important AI developments."
                    )
                ),
                onPrompt = onPrompt
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Privacy boundaries", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Notification previews are encrypted locally. Cloud summaries are optional and redact secret-like text. Screen and image analysis always requires an explicit selection or Android screen-sharing approval. AURA does not silently record the screen or camera.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh Analyzer status")
            }
        }
        item {
            TextButton(
                onClick = onClearHistory,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Delete encrypted notification history")
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun AnalyzerActionGrid(
    actions: List<Triple<String, ImageVector, String>>,
    onPrompt: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowActions.forEach { (label, icon, prompt) ->
                    OutlinedButton(
                        onClick = { onPrompt(prompt) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(icon, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(label, maxLines = 1)
                    }
                }
                if (rowActions.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PlansScreen(
    reminders: List<ReminderItem>,
    history: List<ActionHistoryItem>,
    onRemoveReminder: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeading(
                title = "Plans & activity",
                subtitle = "Upcoming reminders and every action you approved or cancelled."
            )
        }
        item { SectionTitle("Upcoming reminders", Icons.Rounded.Notifications) }
        if (reminders.isEmpty()) {
            item { EmptyCard("No upcoming reminders.") }
        } else {
            items(reminders.sortedBy { it.triggerAtEpochMs }, key = { it.id }) { reminder ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(reminder.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                formatTime(reminder.triggerAtEpochMs),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { onRemoveReminder(reminder.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Cancel reminder")
                        }
                    }
                }
            }
        }
        item { SectionTitle("Action history", Icons.Rounded.History) }
        if (history.isEmpty()) {
            item { EmptyCard("No actions yet. AURA records the result of each approval card.") }
        } else {
            items(history.asReversed(), key = { it.id }) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (item.approved) Icons.Rounded.CheckCircle else Icons.Rounded.Close,
                            contentDescription = null,
                            tint = if (item.approved) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(item.title, fontWeight = FontWeight.Medium)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                            Text(
                                formatTime(item.createdAtEpochMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryScreen(
    memories: List<MemoryItem>,
    onRemove: (MemoryItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeading(
                title = "Personal memory",
                subtitle = "Useful preferences kept in encrypted storage on this phone."
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    Icon(Icons.Rounded.Lock, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "AURA is instructed never to save passwords, OTPs, PINs, payment details, identity numbers, or other secrets.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (memories.isEmpty()) {
            item {
                EmptyCard(
                    "Nothing saved yet. Try: “Remember that I prefer window seats.”"
                )
            }
        } else {
            items(memories, key = { it.id }) { memory ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Memory, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(memory.label, fontWeight = FontWeight.SemiBold)
                            Text(memory.value)
                        }
                        IconButton(onClick = { onRemove(memory) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Forget")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: ButlerSettings,
    ownerVoiceEnrolled: Boolean,
    ownerVoiceEnrollmentBusy: Boolean,
    ownerVoiceStatus: String,
    onEnrollOwnerVoice: () -> Unit,
    onClearOwnerVoice: () -> Unit,
    onSave: (ButlerSettings) -> Unit,
    onTestVoice: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val context = LocalContext.current
    var lastVoiceTiming by remember {
        mutableStateOf(VoiceTimingDiagnostics.lastSummary(context))
    }

    LaunchedEffect(Unit) {
        while (true) {
            val latest = VoiceTimingDiagnostics.lastSummary(context)
            if (latest != lastVoiceTiming) {
                lastVoiceTiming = latest
            }
            delay(1_000L)
        }
    }

    val voicePackName = remember {
        runCatching {
            context.assets.open("voice/piper/VOICE_NAME.txt")
                .bufferedReader()
                .use { it.readText().trim() }
        }.getOrDefault("Bundled Piper offline voice")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageHeading(
                title = "Private hybrid setup",
                subtitle = "Voice, wake word, commands and memory stay on-device. Your private Qwen and Gemma cloud models are used only when their capabilities are needed."
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Offline voice pack installed", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(voicePackName, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Whisper Tiny hears English, Bengali, Hindi, and mixed commands. " +
                            "Replies use an installed Android voice matching English, Bengali or Hindi; Piper remains the offline English fallback.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item { SectionTitle("Voice", Icons.Rounded.Mic) }
        item {
            SwitchRow(
                title = "Wake on “Aura”, “Hey Aura” or “OK Aura”",
                subtitle = "Runs a low-power local wake listener. When Owner Voice Lock is enabled, the wake phrase can be heard from anyone, but the command is discarded unless the following speech matches your enrolled voice.",
                checked = draft.wakeWordEnabled,
                onChecked = { draft = draft.copy(wakeWordEnabled = it) }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Owner Voice Lock", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ownerVoiceEnrolled) {
                            "Voice profile registered locally. Spoken commands can be restricted to your voice before Whisper transcription or action routing."
                        } else {
                            "Register five quality-checked samples of your voice. AURA stores only an encrypted speaker embedding; raw enrollment audio is not saved."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (ownerVoiceStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ownerVoiceStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onEnrollOwnerVoice,
                        enabled = !ownerVoiceEnrollmentBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (ownerVoiceEnrollmentBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Registering voice…")
                        } else {
                            Icon(Icons.Rounded.Mic, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (ownerVoiceEnrolled) "Re-register my voice" else "Register my voice")
                        }
                    }
                    if (ownerVoiceEnrolled) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onClearOwnerVoice,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Remove owner voice profile")
                        }
                    }
                }
            }
        }
        item {
            SwitchRow(
                title = "Only accept my enrolled voice",
                subtitle = if (ownerVoiceEnrolled) {
                    "Checks every spoken command locally against your encrypted voice profile. Unknown voices and noise are ignored before command execution."
                } else {
                    "Register your voice first, then enable this lock."
                },
                checked = draft.ownerVoiceLockEnabled && ownerVoiceEnrolled,
                enabled = ownerVoiceEnrolled && !ownerVoiceEnrollmentBusy,
                onChecked = { draft = draft.copy(ownerVoiceLockEnabled = it && ownerVoiceEnrolled) }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(
                    "Voice Lock is an on-device speaker-verification gate, not a replacement for AURA's confirmation cards. Sensitive actions still require the existing safety checks.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            SwitchRow(
                title = "Speak replies",
                subtitle = "Android TTS automatically selects English, Bengali or Hindi. Piper remains the private offline English fallback.",
                checked = draft.spokenReplies,
                onChecked = { draft = draft.copy(spokenReplies = it) }
            )
        }
        item {
            Column {
                Text("Whisper language", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "auto" to "Auto",
                            "en" to "English",
                            "bn" to "Bengali",
                            "hi" to "Hindi"
                        )
                    ) { (code, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(speechLanguage = code) },
                            label = {
                                Text(if (draft.speechLanguage == code) "✓ $label" else label)
                            }
                        )
                    }
                }
            }
        }
        item { SectionTitle("Optional AI reasoning", Icons.Rounded.AutoAwesome) }
        item {
            SwitchRow(
                title = "Use optional AI for complex or programming questions",
                subtitle = "Local commands, encrypted memory, weather, news and Wikipedia are always tried first.",
                checked = draft.aiEnabled,
                onChecked = { draft = draft.copy(aiEnabled = it) }
            )
        }
        item {
            Column {
                Text("AI provider route", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "automatic" to "Automatic",
                            "local" to "Private Qwen",
                            "gemini" to "Gemini",
                            "groq" to "Groq"
                        )
                    ) { (provider, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(aiProvider = provider) },
                            label = {
                                Text(
                                    if (draft.aiProvider == provider) {
                                        "✓ $label"
                                    } else {
                                        label
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Automatic uses private Qwen first. Gemini/Groq are used only when you explicitly select them or enable the public-fallback switch below.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            SwitchRow(
                title = "Allow public AI fallback",
                subtitle = "OFF by default. When enabled, Automatic mode may send a request to configured Gemini/Groq only if private Qwen cannot answer. Selecting Gemini or Groq directly is also explicit consent for that provider.",
                checked = draft.allowPublicAiFallback,
                onChecked = { draft = draft.copy(allowPublicAiFallback = it) }
            )
        }
        item {
            SwitchRow(
                title = "Use private Qwen AI",
                subtitle = "Uses your OpenAI-compatible Qwen3 8B endpoint. HTTPS cloud endpoints work directly from the phone; localhost still requires the ADB reverse bridge.",
                checked = draft.localAiEnabled,
                onChecked = { draft = draft.copy(localAiEnabled = it) }
            )
        }
        item {
            OutlinedTextField(
                value = draft.localAiEndpoint,
                onValueChange = { draft = draft.copy(localAiEndpoint = it.take(500)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private Qwen endpoint") },
                supportingText = {
                    Text("Default: your Modal Qwen3 8B /v1/chat/completions endpoint.")
                },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.localAiModel,
                onValueChange = { draft = draft.copy(localAiModel = it.take(120)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private Qwen model") },
                supportingText = { Text("Cloud default: Qwen/Qwen3-8B.") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.localAiApiKey,
                onValueChange = { draft = draft.copy(localAiApiKey = it.take(260)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("AURA cloud / private server token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            SwitchRow(
                title = "Use private Gemma screen vision",
                subtitle = "Sends only the image or screen frame you explicitly approve to your private Gemma 3 4B Modal endpoint.",
                checked = draft.privateVisionEnabled,
                onChecked = { draft = draft.copy(privateVisionEnabled = it) }
            )
        }
        item {
            OutlinedTextField(
                value = draft.privateVisionEndpoint,
                onValueChange = { draft = draft.copy(privateVisionEndpoint = it.take(500)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private Gemma vision endpoint") },
                supportingText = { Text("Default: your Modal Gemma 3 4B /v1/chat/completions endpoint.") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.privateVisionModel,
                onValueChange = { draft = draft.copy(privateVisionModel = it.take(120)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Private Gemma vision model") },
                supportingText = { Text("Cloud default: google/gemma-3-4b-it.") },
                singleLine = true
            )
        }
        item {
            SwitchRow(
                title = "Share private memory and recent chat with AI",
                subtitle = "Off by default. When disabled, optional AI receives only the current request. Turn it on only for a provider you trust.",
                checked = draft.allowAiPrivateContext,
                onChecked = { draft = draft.copy(allowAiPrivateContext = it) }
            )
        }
        item {
            Column {
                Text("Butler response style", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "concise" to "Concise",
                            "professional" to "Professional",
                            "detailed" to "Detailed"
                        )
                    ) { (style, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(responseStyle = style) },
                            label = {
                                Text(if (draft.responseStyle == style) "✓ $label" else label)
                            }
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = draft.geminiApiKey,
                onValueChange = {
                    draft = draft.copy(geminiApiKey = it.take(260))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API key") },
                supportingText = {
                    Text(
                        "Use a Google AI Studio project key. Stored encrypted on this phone; never embedded in the APK."
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            Column {
                Text("Gemini model", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "gemini-3.5-flash-lite" to "Fast / low cost",
                            "gemini-3.6-flash" to "Quality / coding"
                        )
                    ) { (model, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(geminiModel = model) },
                            label = {
                                Text(
                                    if (draft.geminiModel == model) {
                                        "✓ $label"
                                    } else {
                                        label
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = draft.groqApiKey,
                onValueChange = { draft = draft.copy(groqApiKey = it.take(220)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Groq API key (optional fallback)") },
                supportingText = {
                    Text("Leave blank when you want Gemini only.")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            Column {
                Text("Groq model", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "openai/gpt-oss-20b" to "Fast",
                            "openai/gpt-oss-120b" to "Higher quality"
                        )
                    ) { (model, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(aiModel = model) },
                            label = {
                                Text(if (draft.aiModel == model) "✓ $label" else label)
                            }
                        )
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Private-key note", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Google AI Pro and Gemini API quota are separate products. AURA uses the API key and quota shown in Google AI Studio. This direct-key mode is intended for your private sideloaded phone build.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Wake listener check", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "After saving wake listening, the notification must show AURA's private wake listener. " +
                            "Say “Aura”, “Hey Aura” or “OK Aura”, wait for the tone, then speak the command. With Owner Voice Lock ON, non-owner speech is rejected locally.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item { SectionTitle("Unified Analyzer", Icons.Rounded.Analytics) }
        item {
            SwitchRow(
                title = "Enable Unified Analyzer",
                subtitle = "Turns on notification triage, detailed news, research, image analysis and temporal memory commands.",
                checked = draft.analyzerEnabled,
                onChecked = { draft = draft.copy(analyzerEnabled = it) }
            )
        }
        item {
            SwitchRow(
                title = "Capture notification previews",
                subtitle = "After Android grants notification access, store bounded encrypted previews for WhatsApp, mail, Slack, Jira and other apps.",
                checked = draft.notificationCaptureEnabled,
                onChecked = {
                    draft = draft.copy(notificationCaptureEnabled = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Detailed cloud summaries",
                subtitle = "Send only selected, secret-redacted notification text to Gemini when you explicitly request a detailed summary.",
                checked = draft.cloudAnalyzerSummaries,
                onChecked = {
                    draft = draft.copy(cloudAnalyzerSummaries = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Proactive important alerts",
                subtitle = "Locally classify urgent, deadline, security, payment, appointment and college notifications and show a private AURA alert.",
                checked = draft.proactiveAnalyzerAlerts,
                onChecked = {
                    draft = draft.copy(proactiveAnalyzerAlerts = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Tell me important messages immediately",
                subtitle = "For locally classified important WhatsApp, mail, SMS, Slack, Jira and messaging notifications, AURA speaks an alert immediately. This classifier does not require cloud AI.",
                checked = draft.importantMessageVoiceAlerts,
                onChecked = {
                    draft = draft.copy(importantMessageVoiceAlerts = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Read message details while phone is locked",
                subtitle = "OFF is safer: on the lock screen AURA says only the app and sender. Turn this ON only if you want the message preview spoken aloud while locked.",
                checked = draft.importantMessageSpeakDetailsWhenLocked,
                enabled = draft.importantMessageVoiceAlerts,
                onChecked = {
                    draft = draft.copy(importantMessageSpeakDetailsWhenLocked = it)
                }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    "Important-message attention runs from Android Notification Access. AURA filters promotional noise, masks secret-like text, avoids speaking during an active call, and keeps the visual alert even when voice announcement is skipped.",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        item {
            SwitchRow(
                title = "Scheduled private digests",
                subtitle = "Use Android WorkManager to create inexact battery-aware important-notification digests in the background.",
                checked = draft.scheduledAnalyzerDigests,
                onChecked = {
                    draft = draft.copy(scheduledAnalyzerDigests = it)
                }
            )
        }
        item {
            Column {
                Text("Notification history retention", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(1, 3, 7, 14, 30)) { days ->
                        AssistChip(
                            onClick = {
                                draft = draft.copy(notificationRetentionDays = days)
                            },
                            label = {
                                Text(
                                    if (draft.notificationRetentionDays == days) {
                                        "✓ $days day" + if (days == 1) "" else "s"
                                    } else {
                                        "$days day" + if (days == 1) "" else "s"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            SwitchRow(
                title = "Screen and image analysis",
                subtitle = "Private Gemma 3 analyzes approved images first. Gemini fallback is allowed only by explicit provider selection or the public-fallback switch.",
                checked = draft.screenAnalysisEnabled,
                onChecked = {
                    draft = draft.copy(screenAnalysisEnabled = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Private deep research",
                subtitle = "Uses private SearXNG inside Modal for multi-source search, then Qwen3 8B for planning, synthesis and citation checking. Gemini fallback requires explicit public-provider consent.",
                checked = draft.researchEnabled,
                onChecked = { draft = draft.copy(researchEnabled = it) }
            )
        }
        item {
            OutlinedTextField(
                value = draft.researchEndpoint,
                onValueChange = { draft = draft.copy(researchEndpoint = it) },
                label = { Text("Private research endpoint") },
                supportingText = { Text("Default: your Modal ResearchGateway /v1/research endpoint.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Column {
                Text("Research depth", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(
                            "quick" to "Quick",
                            "standard" to "Standard",
                            "deep" to "Deep",
                            "exhaustive" to "Exhaustive"
                        )
                    ) { (mode, label) ->
                        AssistChip(
                            onClick = { draft = draft.copy(researchMode = mode) },
                            label = {
                                Text(if (draft.researchMode == mode) "✓ $label" else label)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Standard is recommended for daily use. Deep adds a verification pass. Exhaustive uses more searches, sources and GPU time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            SwitchRow(
                title = "Temporal memory history",
                subtitle = "Keep encrypted previous versions when a saved preference or fact changes instead of erasing the timeline.",
                checked = draft.temporalMemoryEnabled,
                onChecked = {
                    draft = draft.copy(temporalMemoryEnabled = it)
                }
            )
        }
        item { SectionTitle("Live news and weather", Icons.Rounded.Language) }
        item {
            OutlinedTextField(
                value = draft.gnewsApiKey,
                onValueChange = {
                    draft = draft.copy(
                        gnewsApiKey = it.take(260)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("GNews free API key (optional)") },
                supportingText = {
                    Text(
                        "Stored encrypted. AURA uses the GNews API when configured and automatically falls back to a keyless India news RSS feed. The free plan may deliver delayed stories."
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Weather API",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Open-Meteo needs no key for this private build. Ask for current weather or a 3-to-16-day forecast, including a 10-day forecast."
                    )
                }
            }
        }
        item { SectionTitle("Smart interaction", Icons.Rounded.AutoAwesome) }
        item {
            SwitchRow(
                title = "Correct command spelling",
                subtitle = "Conservatively repairs common voice and typing mistakes such as Wataop, vollume, onscren, translet, Pasuri and sumarize without rewriting long general questions.",
                checked = draft.smartCommandCorrection,
                onChecked = { draft = draft.copy(smartCommandCorrection = it) }
            )
        }
        item {
            SwitchRow(
                title = "AI command repair",
                subtitle = "When a short command remains unclear, Gemini may correct only the wording and return it to AURA's local parser and safety policy. Requires optional AI and a Gemini key.",
                checked = draft.aiCommandRepairEnabled,
                onChecked = {
                    draft = draft.copy(aiCommandRepairEnabled = it)
                }
            )
        }
        item {
            SwitchRow(
                title = "Automatically play exact YouTube result",
                subtitle = "Resolve a spoken song or video title to one YouTube video and open it directly. Falls back to search results when confidence or API access is insufficient.",
                checked = draft.autoPlayYouTube,
                onChecked = {
                    draft = draft.copy(autoPlayYouTube = it)
                }
            )
        }
        item {
            OutlinedTextField(
                value = draft.youtubeApiKey,
                onValueChange = {
                    draft = draft.copy(
                        youtubeApiKey = it.take(260)
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("YouTube Data API key") },
                supportingText = {
                    Text(
                        "Enable YouTube Data API v3 in your Google Cloud project. Stored encrypted. When blank, AURA may try the Gemini project key and otherwise opens search results."
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            SwitchRow(
                title = "Dry butler banter",
                subtitle = "Adds occasional original, restrained humour to normal device commands. Serious, urgent, security, payment, medical, research and news replies stay direct.",
                checked = draft.butlerBanterEnabled,
                onChecked = { draft = draft.copy(butlerBanterEnabled = it) }
            )
        }
        item {
            Column {
                Text("Banter level", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0 to "Off", 1 to "Subtle", 2 to "Playful")) { option ->
                        AssistChip(
                            onClick = {
                                draft = draft.copy(butlerBanterLevel = option.first)
                            },
                            label = {
                                Text(
                                    if (draft.butlerBanterLevel == option.first) {
                                        "✓ ${option.second}"
                                    } else {
                                        option.second
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Full-mail and enterprise connectors", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "This build analyzes Gmail, Slack and Jira notification previews. Reading complete historical Gmail mailboxes or autonomously changing Slack/Jira requires separate OAuth clients and approved service credentials; AURA never asks for account passwords.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item { SectionTitle("How AURA addresses you", Icons.Rounded.AutoAwesome) }
        item {
            OutlinedTextField(
                value = draft.preferredName,
                onValueChange = { draft = draft.copy(preferredName = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your name") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.formOfAddress,
                onValueChange = { draft = draft.copy(formOfAddress = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Form of address") },
                supportingText = { Text("Example: Diganta") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = draft.homeCity,
                onValueChange = { draft = draft.copy(homeCity = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Home city or area") },
                supportingText = { Text("Used only when a request needs local context.") },
                singleLine = true
            )
        }
        item { SectionTitle("Behaviour", Icons.Rounded.Settings) }
        item {
            SwitchRow(
                title = "Background reminders",
                subtitle = "Allow approved reminders to notify you after the app closes.",
                checked = draft.backgroundReminders,
                onChecked = { draft = draft.copy(backgroundReminders = it) }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Android 11 background voice", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "When wake listening is on, Android must show “AURA private wake listening.” " +
                            "This is the safe foreground-microphone rule. On Vivo, also set AURA’s battery use to Unrestricted so the system does not stop it.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onTestVoice,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Test AURA voice")
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Last voice timing",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        lastVoiceTiming.ifBlank {
                            "No completed speech sample yet. Tap Test AURA voice, wait for it to finish, then this card will show the real AI, startup and speaking time."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save on-device settings")
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun actionIcon(kind: ActionKind): ImageVector = when (kind) {
    ActionKind.OPEN_URL -> Icons.Rounded.Language
    ActionKind.OPEN_APP -> Icons.Rounded.AutoAwesome
    ActionKind.OPEN_SYSTEM_SETTING -> Icons.Rounded.Settings
    ActionKind.PLAY_MEDIA -> Icons.Rounded.AutoAwesome
    ActionKind.COMPOSE_EMAIL -> Icons.Rounded.Share
    ActionKind.WHATSAPP_MESSAGE -> Icons.Rounded.Sms
    ActionKind.DIAL_PHONE -> Icons.Rounded.Call
    ActionKind.MAP_ROUTE -> Icons.Rounded.Directions
    ActionKind.CALENDAR_EVENT -> Icons.Rounded.CalendarMonth
    ActionKind.SET_ALARM -> Icons.Rounded.Schedule
    ActionKind.SET_TIMER -> Icons.Rounded.Schedule
    ActionKind.DRAFT_MESSAGE -> Icons.Rounded.Sms
    ActionKind.SHARE_TEXT -> Icons.Rounded.Share
    ActionKind.SYSTEM_VOLUME -> Icons.Rounded.MoreHoriz
    ActionKind.SYSTEM_BRIGHTNESS -> Icons.Rounded.AutoAwesome
    ActionKind.FLASHLIGHT -> Icons.Rounded.AutoAwesome
    ActionKind.OPEN_CAMERA -> Icons.Rounded.AutoAwesome
    ActionKind.FOOD_SEARCH -> Icons.Rounded.Fastfood
    ActionKind.TRAVEL_SEARCH -> Icons.Rounded.Route
    ActionKind.SHOPPING_SEARCH -> Icons.Rounded.ShoppingBag
    ActionKind.ANALYZE_SCREEN -> Icons.Rounded.ScreenSearchDesktop
    ActionKind.CAPTURE_SCREEN -> Icons.Rounded.ScreenSearchDesktop
    ActionKind.ANALYZE_IMAGE -> Icons.Rounded.ImageSearch
    ActionKind.IDENTIFY_SONG -> Icons.Rounded.MusicNote
    ActionKind.OPEN_APP_STORAGE -> Icons.Rounded.Settings
    ActionKind.CLEAR_AURA_CACHE -> Icons.Rounded.DeleteOutline
    ActionKind.COOLING_ASSIST -> Icons.Rounded.Memory
    ActionKind.REMINDER -> Icons.Rounded.AddAlert
}

private fun formatTime(epochMs: Long): String = runCatching {
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}.getOrDefault("Unknown time")
