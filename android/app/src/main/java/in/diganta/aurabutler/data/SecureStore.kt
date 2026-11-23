package `in`.diganta.aurabutler.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import `in`.diganta.aurabutler.memory.PrivateMemoryStore
import `in`.diganta.aurabutler.memory.TemporalMemoryStore
import `in`.diganta.aurabutler.model.ActionHistoryItem
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerSettings
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.IntelligenceMode
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.ReminderItem
import `in`.diganta.aurabutler.model.Sender
import `in`.diganta.aurabutler.runtime.RemoteConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("DEPRECATION")
class SecureStore(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        appContext,
        "aura_private_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    init {
        migrateDualCloudDefaultsIfNeeded()
        migrateProfessionalDefaultsIfNeeded()
    }

    private val memoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrivateMemoryStore(appContext)
    }

    private val temporalMemoryStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TemporalMemoryStore(appContext)
    }

    /**
     * Synchronous, non-network manifest snapshot. AuraApplication refreshes the
     * remote manifest asynchronously; settings reads never block on network I/O.
     */
    private val remoteManifest by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteConfig.getCachedOrDefault(appContext)
    }

    fun loadSettings(): ButlerSettings {
        val manifest = remoteManifest
        val defaults = ButlerSettings(
            localAiEndpoint = manifest.textEndpoint,
            localAiModel = manifest.models.text,
            privateVisionEndpoint = manifest.visionEndpoint,
            privateVisionModel = manifest.models.vision,
            researchEndpoint = manifest.researchEndpoint
        )

        return ButlerSettings(
            preferredName = preferences.getString(KEY_NAME, null)
                ?: defaults.preferredName,
            formOfAddress = preferences.getString(KEY_ADDRESS, null)
                ?: defaults.formOfAddress,
            homeCity = preferences.getString(KEY_HOME_CITY, null)
                ?: defaults.homeCity,
            homeLatitude = java.lang.Double.longBitsToDouble(
                preferences.getLong(
                    KEY_HOME_LATITUDE,
                    java.lang.Double.doubleToRawLongBits(defaults.homeLatitude)
                )
            ),
            homeLongitude = java.lang.Double.longBitsToDouble(
                preferences.getLong(
                    KEY_HOME_LONGITUDE,
                    java.lang.Double.doubleToRawLongBits(defaults.homeLongitude)
                )
            ),
            spokenReplies = preferences.getBoolean(KEY_SPOKEN_REPLIES, true),
            backgroundReminders = preferences.getBoolean(KEY_BACKGROUND_REMINDERS, true),
            wakeWordEnabled = preferences.getBoolean(KEY_WAKE_WORD, false),
            ownerVoiceLockEnabled = preferences.getBoolean(
                KEY_OWNER_VOICE_LOCK,
                defaults.ownerVoiceLockEnabled
            ),
            ownerVoiceThreshold = java.lang.Float.intBitsToFloat(
                preferences.getInt(
                    KEY_OWNER_VOICE_THRESHOLD,
                    java.lang.Float.floatToRawIntBits(defaults.ownerVoiceThreshold)
                )
            ).coerceIn(0.40f, 0.85f),
            speechLanguage = preferences.getString(KEY_SPEECH_LANGUAGE, "auto") ?: "auto",
            aiEnabled = preferences.getBoolean(KEY_AI_ENABLED, false),
            aiProvider = HybridAiProvider.normalizeProvider(
                preferences.getString(
                    KEY_AI_PROVIDER,
                    defaults.aiProvider
                ) ?: defaults.aiProvider
            ),
            allowPublicAiFallback = preferences.getBoolean(
                KEY_ALLOW_PUBLIC_AI_FALLBACK,
                defaults.allowPublicAiFallback
            ),
            geminiApiKey = preferences.getString(KEY_GEMINI_API_KEY, "") ?: "",
            geminiModel = normalizeGeminiModel(
                preferences.getString(
                    KEY_GEMINI_MODEL,
                    defaults.geminiModel
                ) ?: defaults.geminiModel
            ),
            groqApiKey = preferences.getString(KEY_GROQ_API_KEY, "") ?: "",
            aiModel = normalizeAiModel(
                preferences.getString(KEY_AI_MODEL, defaults.aiModel)
                    ?: defaults.aiModel
            ),
            localAiEnabled = preferences.getBoolean(
                KEY_LOCAL_AI_ENABLED,
                defaults.localAiEnabled
            ),
            localAiEndpoint = preferences.getString(
                KEY_LOCAL_AI_ENDPOINT,
                defaults.localAiEndpoint
            ) ?: defaults.localAiEndpoint,
            localAiModel = preferences.getString(
                KEY_LOCAL_AI_MODEL,
                defaults.localAiModel
            ) ?: defaults.localAiModel,
            localAiApiKey = preferences.getString(KEY_LOCAL_AI_API_KEY, "") ?: "",
            privateVisionEnabled = preferences.getBoolean(
                KEY_PRIVATE_VISION_ENABLED,
                defaults.privateVisionEnabled
            ),
            privateVisionEndpoint = preferences.getString(
                KEY_PRIVATE_VISION_ENDPOINT,
                defaults.privateVisionEndpoint
            ) ?: defaults.privateVisionEndpoint,
            privateVisionModel = preferences.getString(
                KEY_PRIVATE_VISION_MODEL,
                defaults.privateVisionModel
            ) ?: defaults.privateVisionModel,
            allowAiPrivateContext = preferences.getBoolean(
                KEY_ALLOW_AI_PRIVATE_CONTEXT,
                defaults.allowAiPrivateContext
            ),
            responseStyle = ButlerSystemPrompt.normalizeStyle(
                preferences.getString(
                    KEY_RESPONSE_STYLE,
                    defaults.responseStyle
                ) ?: defaults.responseStyle
            ),
            analyzerEnabled = preferences.getBoolean(
                KEY_ANALYZER_ENABLED,
                defaults.analyzerEnabled
            ),
            notificationCaptureEnabled = preferences.getBoolean(
                KEY_NOTIFICATION_CAPTURE,
                defaults.notificationCaptureEnabled
            ),
            cloudAnalyzerSummaries = preferences.getBoolean(
                KEY_CLOUD_ANALYZER_SUMMARIES,
                defaults.cloudAnalyzerSummaries
            ),
            proactiveAnalyzerAlerts = preferences.getBoolean(
                KEY_PROACTIVE_ANALYZER_ALERTS,
                defaults.proactiveAnalyzerAlerts
            ),
            importantMessageVoiceAlerts = preferences.getBoolean(
                KEY_IMPORTANT_MESSAGE_VOICE_ALERTS,
                defaults.importantMessageVoiceAlerts
            ),
            importantMessageSpeakDetailsWhenLocked = preferences.getBoolean(
                KEY_IMPORTANT_MESSAGE_DETAILS_LOCKED,
                defaults.importantMessageSpeakDetailsWhenLocked
            ),
            scheduledAnalyzerDigests = preferences.getBoolean(
                KEY_SCHEDULED_ANALYZER_DIGESTS,
                defaults.scheduledAnalyzerDigests
            ),
            notificationRetentionDays = preferences.getInt(
                KEY_NOTIFICATION_RETENTION_DAYS,
                defaults.notificationRetentionDays
            ).coerceIn(1, 30),
            screenAnalysisEnabled = preferences.getBoolean(
                KEY_SCREEN_ANALYSIS,
                defaults.screenAnalysisEnabled
            ),
            researchEnabled = preferences.getBoolean(
                KEY_RESEARCH_ENABLED,
                defaults.researchEnabled
            ),
            researchEndpoint = preferences.getString(
                KEY_RESEARCH_ENDPOINT,
                defaults.researchEndpoint
            ) ?: defaults.researchEndpoint,
            researchMode = DeepResearchProvider.normalizeMode(
                preferences.getString(
                    KEY_RESEARCH_MODE,
                    defaults.researchMode
                ) ?: defaults.researchMode
            ),
            temporalMemoryEnabled = preferences.getBoolean(
                KEY_TEMPORAL_MEMORY,
                defaults.temporalMemoryEnabled
            ),
            smartCommandCorrection = preferences.getBoolean(
                KEY_SMART_COMMAND_CORRECTION,
                defaults.smartCommandCorrection
            ),
            aiCommandRepairEnabled = preferences.getBoolean(
                KEY_AI_COMMAND_REPAIR,
                defaults.aiCommandRepairEnabled
            ),
            autoPlayYouTube = preferences.getBoolean(
                KEY_AUTO_PLAY_YOUTUBE,
                defaults.autoPlayYouTube
            ),
            youtubeApiKey = preferences.getString(
                KEY_YOUTUBE_API_KEY,
                defaults.youtubeApiKey
            ) ?: defaults.youtubeApiKey,
            gnewsApiKey = preferences.getString(
                KEY_GNEWS_API_KEY,
                defaults.gnewsApiKey
            ) ?: defaults.gnewsApiKey,
            butlerBanterEnabled = preferences.getBoolean(
                KEY_BUTLER_BANTER_ENABLED,
                defaults.butlerBanterEnabled
            ),
            butlerBanterLevel = preferences.getInt(
                KEY_BUTLER_BANTER_LEVEL,
                defaults.butlerBanterLevel
            ).coerceIn(0, 2),
            // Intelligence settings (v0.18+)
            intelligenceMode = IntelligenceMode.fromWireName(
                preferences.getString(KEY_INTELLIGENCE_MODE, defaults.intelligenceMode.wireName)
                    ?: defaults.intelligenceMode.wireName
            ),
            proactiveSuggestionsEnabled = preferences.getBoolean(
                KEY_PROACTIVE_SUGGESTIONS_ENABLED,
                defaults.proactiveSuggestionsEnabled
            ),
            proactiveAutoExecute = preferences.getBoolean(
                KEY_PROACTIVE_AUTO_EXECUTE,
                defaults.proactiveAutoExecute
            ),
            semanticMemoryEnabled = preferences.getBoolean(
                KEY_SEMANTIC_MEMORY_ENABLED,
                defaults.semanticMemoryEnabled
            ),
            memoryConsolidationEnabled = preferences.getBoolean(
                KEY_MEMORY_CONSOLIDATION_ENABLED,
                defaults.memoryConsolidationEnabled
            ),
            reasoningMode = preferences.getString(
                KEY_REASONING_MODE,
                defaults.reasoningMode
            ) ?: defaults.reasoningMode,
            maxReasoningSteps = preferences.getInt(
                KEY_MAX_REASONING_STEPS,
                defaults.maxReasoningSteps
            ).coerceIn(1, 20),
            maxPlanSteps = preferences.getInt(
                KEY_MAX_PLAN_STEPS,
                defaults.maxPlanSteps
            ).coerceIn(1, 30),
            proactiveAgentSchedule = preferences.getString(
                KEY_PROACTIVE_AGENT_SCHEDULE,
                defaults.proactiveAgentSchedule
            ) ?: defaults.proactiveAgentSchedule
        )
    }

    fun saveSettings(settings: ButlerSettings) {
        preferences.edit()
            .putString(KEY_NAME, settings.preferredName.trim())
            .putString(KEY_ADDRESS, settings.formOfAddress.trim())
            .putString(KEY_HOME_CITY, settings.homeCity.trim())
            .putLong(
                KEY_HOME_LATITUDE,
                java.lang.Double.doubleToRawLongBits(settings.homeLatitude)
            )
            .putLong(
                KEY_HOME_LONGITUDE,
                java.lang.Double.doubleToRawLongBits(settings.homeLongitude)
            )
            .putBoolean(KEY_SPOKEN_REPLIES, settings.spokenReplies)
            .putBoolean(KEY_BACKGROUND_REMINDERS, settings.backgroundReminders)
            .putBoolean(KEY_WAKE_WORD, settings.wakeWordEnabled)
            .putBoolean(KEY_OWNER_VOICE_LOCK, settings.ownerVoiceLockEnabled)
            .putInt(
                KEY_OWNER_VOICE_THRESHOLD,
                java.lang.Float.floatToRawIntBits(settings.ownerVoiceThreshold.coerceIn(0.40f, 0.85f))
            )
            .putString(KEY_SPEECH_LANGUAGE, settings.speechLanguage)
            .putBoolean(KEY_AI_ENABLED, settings.aiEnabled)
            .putString(
                KEY_AI_PROVIDER,
                HybridAiProvider.normalizeProvider(settings.aiProvider)
            )
            .putBoolean(KEY_ALLOW_PUBLIC_AI_FALLBACK, settings.allowPublicAiFallback)
            .putString(KEY_GEMINI_API_KEY, settings.geminiApiKey.trim())
            .putString(
                KEY_GEMINI_MODEL,
                normalizeGeminiModel(settings.geminiModel)
            )
            .putString(KEY_GROQ_API_KEY, settings.groqApiKey.trim())
            .putString(KEY_AI_MODEL, normalizeAiModel(settings.aiModel))
            .putBoolean(KEY_LOCAL_AI_ENABLED, settings.localAiEnabled)
            .putString(KEY_LOCAL_AI_ENDPOINT, settings.localAiEndpoint.trim())
            .putString(KEY_LOCAL_AI_MODEL, settings.localAiModel.trim())
            .putString(KEY_LOCAL_AI_API_KEY, settings.localAiApiKey.trim())
            .putBoolean(KEY_PRIVATE_VISION_ENABLED, settings.privateVisionEnabled)
            .putString(KEY_PRIVATE_VISION_ENDPOINT, settings.privateVisionEndpoint.trim())
            .putString(KEY_PRIVATE_VISION_MODEL, settings.privateVisionModel.trim())
            .putBoolean(KEY_ALLOW_AI_PRIVATE_CONTEXT, settings.allowAiPrivateContext)
            .putString(
                KEY_RESPONSE_STYLE,
                ButlerSystemPrompt.normalizeStyle(settings.responseStyle)
            )
            .putBoolean(KEY_ANALYZER_ENABLED, settings.analyzerEnabled)
            .putBoolean(
                KEY_NOTIFICATION_CAPTURE,
                settings.notificationCaptureEnabled
            )
            .putBoolean(
                KEY_CLOUD_ANALYZER_SUMMARIES,
                settings.cloudAnalyzerSummaries
            )
            .putBoolean(
                KEY_PROACTIVE_ANALYZER_ALERTS,
                settings.proactiveAnalyzerAlerts
            )
            .putBoolean(
                KEY_IMPORTANT_MESSAGE_VOICE_ALERTS,
                settings.importantMessageVoiceAlerts
            )
            .putBoolean(
                KEY_IMPORTANT_MESSAGE_DETAILS_LOCKED,
                settings.importantMessageSpeakDetailsWhenLocked
            )
            .putBoolean(
                KEY_SCHEDULED_ANALYZER_DIGESTS,
                settings.scheduledAnalyzerDigests
            )
            .putInt(
                KEY_NOTIFICATION_RETENTION_DAYS,
                settings.notificationRetentionDays.coerceIn(1, 30)
            )
            .putBoolean(KEY_SCREEN_ANALYSIS, settings.screenAnalysisEnabled)
            .putBoolean(KEY_RESEARCH_ENABLED, settings.researchEnabled)
            .putString(KEY_RESEARCH_ENDPOINT, settings.researchEndpoint.trim())
            .putString(KEY_RESEARCH_MODE, DeepResearchProvider.normalizeMode(settings.researchMode))
            .putBoolean(KEY_TEMPORAL_MEMORY, settings.temporalMemoryEnabled)
            .putBoolean(
                KEY_SMART_COMMAND_CORRECTION,
                settings.smartCommandCorrection
            )
            .putBoolean(
                KEY_AI_COMMAND_REPAIR,
                settings.aiCommandRepairEnabled
            )
            .putBoolean(
                KEY_AUTO_PLAY_YOUTUBE,
                settings.autoPlayYouTube
            )
            .putString(
                KEY_YOUTUBE_API_KEY,
                settings.youtubeApiKey.trim()
            )
            .putString(
                KEY_GNEWS_API_KEY,
                settings.gnewsApiKey.trim()
            )
            .putBoolean(
                KEY_BUTLER_BANTER_ENABLED,
                settings.butlerBanterEnabled
            )
            .putInt(
                KEY_BUTLER_BANTER_LEVEL,
                settings.butlerBanterLevel.coerceIn(0, 2)
            )
            // Intelligence settings (v0.18+)
            .putString(KEY_INTELLIGENCE_MODE, settings.intelligenceMode.wireName)
            .putBoolean(KEY_PROACTIVE_SUGGESTIONS_ENABLED, settings.proactiveSuggestionsEnabled)
            .putBoolean(KEY_PROACTIVE_AUTO_EXECUTE, settings.proactiveAutoExecute)
            .putBoolean(KEY_SEMANTIC_MEMORY_ENABLED, settings.semanticMemoryEnabled)
            .putBoolean(KEY_MEMORY_CONSOLIDATION_ENABLED, settings.memoryConsolidationEnabled)
            .putString(KEY_REASONING_MODE, settings.reasoningMode)
            .putInt(KEY_MAX_REASONING_STEPS, settings.maxReasoningSteps.coerceIn(1, 20))
            .putInt(KEY_MAX_PLAN_STEPS, settings.maxPlanSteps.coerceIn(1, 30))
            .putString(KEY_PROACTIVE_AGENT_SCHEDULE, settings.proactiveAgentSchedule)
            .apply()
    }

    fun saveOwnerVoiceEmbedding(embedding: FloatArray) {
        if (embedding.isEmpty()) {
            clearOwnerVoiceEmbedding()
            return
        }
        val buffer = ByteBuffer
            .allocate(embedding.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach(buffer::putFloat)
        val encoded = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        preferences.edit()
            .putString(KEY_OWNER_VOICE_EMBEDDING, encoded)
            .putInt(KEY_OWNER_VOICE_DIM, embedding.size)
            .apply()
    }

    fun loadOwnerVoiceEmbedding(): FloatArray? {
        val encoded = preferences.getString(KEY_OWNER_VOICE_EMBEDDING, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val expectedDim = preferences.getInt(KEY_OWNER_VOICE_DIM, 0)
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return null
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null
        val dimension = bytes.size / Float.SIZE_BYTES
        if (expectedDim > 0 && dimension != expectedDim) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }
    }

    fun clearOwnerVoiceEmbedding() {
        preferences.edit()
            .remove(KEY_OWNER_VOICE_EMBEDDING)
            .remove(KEY_OWNER_VOICE_DIM)
            .putBoolean(KEY_OWNER_VOICE_LOCK, false)
            .apply()
    }

    fun loadMessages(): List<ChatMessage> = readArray(KEY_MESSAGES).mapNotNull { json ->
        runCatching {
            ChatMessage(
                id = json.getString("id"),
                sender = Sender.valueOf(json.getString("sender")),
                text = json.getString("text"),
                createdAtEpochMs = json.getLong("createdAt"),
                provider = json.optString("provider", ""),
                intent = json.optString("intent", ""),
                latencyMs = json.optLong("latencyMs", 0L)
            )
        }.getOrNull()
    }

    fun saveMessages(messages: List<ChatMessage>) {
        writeArray(
            KEY_MESSAGES,
            messages.takeLast(80).map {
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender.name)
                    .put("text", it.text)
                    .put("createdAt", it.createdAtEpochMs)
                    .put("provider", it.provider)
                    .put("intent", it.intent)
                    .put("latencyMs", it.latencyMs)
            }
        )
    }

    fun loadMemories(): List<MemoryItem> {
        migrateLegacyMemoriesIfNeeded()
        return memoryStore.list()
    }

    fun searchMemories(query: String, limit: Int = 8): List<MemoryItem> {
        migrateLegacyMemoriesIfNeeded()
        return memoryStore.search(query, limit)
    }

    fun saveMemories(memories: List<MemoryItem>) {
        migrateLegacyMemoriesIfNeeded()
        memoryStore.replaceAll(memories)
        if (loadSettings().temporalMemoryEnabled) {
            temporalMemoryStore.synchronize(memories)
        }
    }

    fun searchMemoryHistory(query: String, limit: Int = 20): List<String> =
        temporalMemoryStore.history(query, limit)

    fun forgetMemories(query: String): Int {
        migrateLegacyMemoriesIfNeeded()
        return memoryStore.deleteMatching(query)
    }

    fun loadReminders(): List<ReminderItem> = readArray(KEY_REMINDERS).mapNotNull { json ->
        runCatching {
            ReminderItem(
                id = json.getString("id"),
                title = json.getString("title"),
                triggerAtEpochMs = json.getLong("triggerAt"),
                createdAtEpochMs = json.getLong("createdAt")
            )
        }.getOrNull()
    }

    fun saveReminders(reminders: List<ReminderItem>) {
        writeArray(
            KEY_REMINDERS,
            reminders.takeLast(100).map {
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("triggerAt", it.triggerAtEpochMs)
                    .put("createdAt", it.createdAtEpochMs)
            }
        )
    }

    fun loadHistory(): List<ActionHistoryItem> = readArray(KEY_HISTORY).mapNotNull { json ->
        runCatching {
            ActionHistoryItem(
                id = json.getString("id"),
                title = json.getString("title"),
                detail = json.getString("detail"),
                approved = json.getBoolean("approved"),
                createdAtEpochMs = json.getLong("createdAt")
            )
        }.getOrNull()
    }

    fun saveHistory(history: List<ActionHistoryItem>) {
        writeArray(
            KEY_HISTORY,
            history.takeLast(150).map {
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("detail", it.detail)
                    .put("approved", it.approved)
                    .put("createdAt", it.createdAtEpochMs)
            }
        )
    }

    fun loadPendingActions(): List<ButlerAction> =
        readArray(KEY_PENDING_ACTIONS).mapNotNull { json ->
            runCatching {
                val payloadJson = json.getJSONObject("payload")
                val payload = buildMap {
                    payloadJson.keys().forEach { key ->
                        put(key, payloadJson.optString(key))
                    }
                }
                ButlerAction(
                    id = json.getString("id"),
                    kind = ActionKind.valueOf(json.getString("kind")),
                    title = json.getString("title"),
                    summary = json.getString("summary"),
                    risk = ActionRisk.valueOf(json.getString("risk")),
                    payload = payload,
                    createdAtEpochMs = json.optLong("createdAt", 0L),
                    expiresAtEpochMs = json.optLong("expiresAt", 0L)
                )
            }.getOrNull()
        }.filterNot(ButlerAction::isExpired)

    fun savePendingActions(actions: List<ButlerAction>) {
        writeArray(
            KEY_PENDING_ACTIONS,
            actions.takeLast(20).map { action ->
                val payload = JSONObject()
                action.payload.forEach(payload::put)
                JSONObject()
                    .put("id", action.id)
                    .put("kind", action.kind.name)
                    .put("title", action.title)
                    .put("summary", action.summary)
                    .put("risk", action.risk.name)
                    .put("payload", payload)
                    .put("createdAt", action.createdAtEpochMs)
                    .put("expiresAt", action.expiresAtEpochMs)
            }
        )
    }

    fun loadRecentNews(): List<NewsStory> =
        readArray(KEY_RECENT_NEWS).mapNotNull { json ->
            runCatching {
                NewsStory(
                    title = json.getString("title"),
                    description = json.optString("description", ""),
                    content = json.optString("content", ""),
                    url = json.getString("url"),
                    imageUrl = json.optString("imageUrl", ""),
                    sourceName = json.optString("sourceName", ""),
                    sourceUrl = json.optString("sourceUrl", ""),
                    publishedAtEpochMs = if (json.has("publishedAt")) {
                        json.optLong("publishedAt")
                    } else {
                        null
                    }
                )
            }.getOrNull()
        }

    fun saveRecentNews(stories: List<NewsStory>) {
        writeArray(
            KEY_RECENT_NEWS,
            stories.take(10).map { story ->
                JSONObject()
                    .put("title", story.title)
                    .put("description", story.description)
                    .put("content", story.content)
                    .put("url", story.url)
                    .put("imageUrl", story.imageUrl)
                    .put("sourceName", story.sourceName)
                    .put("sourceUrl", story.sourceUrl)
                    .apply {
                        story.publishedAtEpochMs?.let {
                            put("publishedAt", it)
                        }
                    }
            }
        )
    }

    fun clearConversation() {
        preferences.edit().remove(KEY_MESSAGES).apply()
    }

    private fun migrateDualCloudDefaultsIfNeeded() {
        if (preferences.getBoolean(KEY_DUAL_CLOUD_DEFAULTS_MIGRATED, false)) return

        val oldEndpoint = preferences.getString(KEY_LOCAL_AI_ENDPOINT, null)
        val oldModel = preferences.getString(KEY_LOCAL_AI_MODEL, null)
        val editor = preferences.edit()

        if (oldEndpoint.isNullOrBlank() || oldEndpoint == LEGACY_LOCAL_ENDPOINT) {
            editor.putString(KEY_LOCAL_AI_ENDPOINT, remoteManifest.textEndpoint)
        }
        if (oldModel.isNullOrBlank() || oldModel == LEGACY_LOCAL_MODEL) {
            editor.putString(KEY_LOCAL_AI_MODEL, remoteManifest.models.text)
        }
        if (!preferences.contains(KEY_PRIVATE_VISION_ENDPOINT)) {
            editor.putString(
                KEY_PRIVATE_VISION_ENDPOINT,
                remoteManifest.visionEndpoint
            )
        }
        if (!preferences.contains(KEY_PRIVATE_VISION_MODEL)) {
            editor.putString(
                KEY_PRIVATE_VISION_MODEL,
                remoteManifest.models.vision
            )
        }
        editor.putBoolean(KEY_DUAL_CLOUD_DEFAULTS_MIGRATED, true).apply()
    }

    /**
     * One-time v0.15 migration. Preserve owner choices, but replace defaults
     * that are now invalid or were explicitly retired from AURA's identity.
     */
    private fun migrateProfessionalDefaultsIfNeeded() {
        if (preferences.getBoolean(KEY_PROFESSIONAL_V15_MIGRATED, false)) return

        val editor = preferences.edit()
        val oldAddress = preferences.getString(KEY_ADDRESS, "").orEmpty().trim()
        if (oldAddress.equals("Master Diganta", ignoreCase = true)) {
            editor.putString(KEY_ADDRESS, "Diganta")
        }

        when (preferences.getString(KEY_GEMINI_MODEL, "").orEmpty().trim()) {
            "gemini-2.5-pro",
            "gemini-3.1-pro-preview",
            "gemini-3.5-flash" ->
                editor.putString(KEY_GEMINI_MODEL, GeminiAiProvider.MODEL_QUALITY)

            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash",
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3-flash-preview" ->
                editor.putString(KEY_GEMINI_MODEL, GeminiAiProvider.MODEL_FAST)
        }

        editor.putBoolean(KEY_PROFESSIONAL_V15_MIGRATED, true).apply()
    }

    private fun migrateLegacyMemoriesIfNeeded() {
        if (preferences.getBoolean(KEY_MEMORY_DB_MIGRATED, false)) return

        val legacy = readArray(KEY_MEMORIES).mapNotNull { json ->
            runCatching {
                MemoryItem(
                    id = json.getString("id"),
                    label = json.getString("label"),
                    value = json.getString("value"),
                    createdAtEpochMs = json.getLong("createdAt")
                )
            }.getOrNull()
        }

        if (legacy.isNotEmpty()) {
            memoryStore.replaceAll(legacy)
        }

        preferences.edit()
            .putBoolean(KEY_MEMORY_DB_MIGRATED, true)
            .remove(KEY_MEMORIES)
            .apply()
    }

    private fun readArray(key: String): List<JSONObject> {
        val raw = preferences.getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeArray(key: String, values: List<JSONObject>) {
        preferences.edit()
            .putString(key, JSONArray(values).toString())
            .apply()
    }

    private fun normalizeAiModel(value: String): String = when (value.trim()) {
        GroqAiProvider.MODEL_QUALITY,
        "llama-3.3-70b-versatile" -> GroqAiProvider.MODEL_QUALITY

        else -> GroqAiProvider.MODEL_FAST
    }

    private fun normalizeGeminiModel(value: String): String = when (value.trim()) {
        GeminiAiProvider.MODEL_QUALITY,
        "gemini-2.5-pro",
        "gemini-3.1-pro-preview",
        "gemini-3.5-flash" -> GeminiAiProvider.MODEL_QUALITY

        else -> GeminiAiProvider.MODEL_FAST
    }

    private companion object {
        const val KEY_NAME = "preferred_name"
        const val KEY_ADDRESS = "form_of_address"
        const val KEY_HOME_CITY = "home_city"
        const val KEY_HOME_LATITUDE = "home_latitude"
        const val KEY_HOME_LONGITUDE = "home_longitude"
        const val KEY_SPOKEN_REPLIES = "spoken_replies"
        const val KEY_BACKGROUND_REMINDERS = "background_reminders"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_OWNER_VOICE_LOCK = "owner_voice_lock_enabled"
        const val KEY_OWNER_VOICE_THRESHOLD = "owner_voice_threshold_bits"
        const val KEY_OWNER_VOICE_EMBEDDING = "owner_voice_embedding_v1"
        const val KEY_OWNER_VOICE_DIM = "owner_voice_embedding_dim_v1"
        const val KEY_SPEECH_LANGUAGE = "speech_language"
        const val KEY_AI_ENABLED = "ai_enabled"
        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_ALLOW_PUBLIC_AI_FALLBACK = "allow_public_ai_fallback"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL = "gemini_model"
        const val KEY_GROQ_API_KEY = "groq_api_key"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_LOCAL_AI_ENABLED = "local_ai_enabled"
        const val KEY_LOCAL_AI_ENDPOINT = "local_ai_endpoint"
        const val KEY_LOCAL_AI_MODEL = "local_ai_model"
        const val KEY_LOCAL_AI_API_KEY = "local_ai_api_key"
        const val KEY_PRIVATE_VISION_ENABLED = "private_vision_enabled"
        const val KEY_PRIVATE_VISION_ENDPOINT = "private_vision_endpoint"
        const val KEY_PRIVATE_VISION_MODEL = "private_vision_model"
        const val KEY_DUAL_CLOUD_DEFAULTS_MIGRATED = "dual_cloud_defaults_migrated_v1"
        const val KEY_PROFESSIONAL_V15_MIGRATED = "professional_defaults_migrated_v15"
        const val KEY_ALLOW_AI_PRIVATE_CONTEXT = "allow_ai_private_context"
        const val KEY_RESPONSE_STYLE = "response_style"
        const val KEY_ANALYZER_ENABLED = "analyzer_enabled"
        const val KEY_NOTIFICATION_CAPTURE = "notification_capture_enabled"
        const val KEY_CLOUD_ANALYZER_SUMMARIES = "cloud_analyzer_summaries"
        const val KEY_PROACTIVE_ANALYZER_ALERTS = "proactive_analyzer_alerts"
        const val KEY_IMPORTANT_MESSAGE_VOICE_ALERTS = "important_message_voice_alerts"
        const val KEY_IMPORTANT_MESSAGE_DETAILS_LOCKED = "important_message_details_when_locked"
        const val KEY_SCHEDULED_ANALYZER_DIGESTS = "scheduled_analyzer_digests"
        const val KEY_NOTIFICATION_RETENTION_DAYS = "notification_retention_days"
        const val KEY_SCREEN_ANALYSIS = "screen_analysis_enabled"
        const val KEY_RESEARCH_ENABLED = "research_enabled"
        const val KEY_RESEARCH_ENDPOINT = "research_endpoint"
        const val KEY_RESEARCH_MODE = "research_mode"
        const val KEY_TEMPORAL_MEMORY = "temporal_memory_enabled"
        const val KEY_SMART_COMMAND_CORRECTION = "smart_command_correction"
        const val KEY_AI_COMMAND_REPAIR = "ai_command_repair_enabled"
        const val KEY_AUTO_PLAY_YOUTUBE = "auto_play_youtube"
        const val KEY_YOUTUBE_API_KEY = "youtube_api_key"
        const val KEY_GNEWS_API_KEY = "gnews_api_key"
        const val KEY_BUTLER_BANTER_ENABLED = "butler_banter_enabled"
        const val KEY_BUTLER_BANTER_LEVEL = "butler_banter_level"
        const val KEY_MESSAGES = "messages"
        const val KEY_MEMORIES = "memories"
        const val KEY_MEMORY_DB_MIGRATED = "memory_db_migrated_v2"
        const val KEY_REMINDERS = "reminders"
        const val KEY_HISTORY = "action_history"
        const val KEY_PENDING_ACTIONS = "pending_actions"
        const val KEY_RECENT_NEWS = "recent_news"
        // Intelligence settings (v0.18+)
        const val KEY_INTELLIGENCE_MODE = "intelligence_mode"
        const val KEY_PROACTIVE_SUGGESTIONS_ENABLED = "proactive_suggestions_enabled"
        const val KEY_PROACTIVE_AUTO_EXECUTE = "proactive_auto_execute"
        const val KEY_SEMANTIC_MEMORY_ENABLED = "semantic_memory_enabled"
        const val KEY_MEMORY_CONSOLIDATION_ENABLED = "memory_consolidation_enabled"
        const val KEY_REASONING_MODE = "reasoning_mode"
        const val KEY_MAX_REASONING_STEPS = "max_reasoning_steps"
        const val KEY_MAX_PLAN_STEPS = "max_plan_steps"
        const val KEY_PROACTIVE_AGENT_SCHEDULE = "proactive_agent_schedule"

        const val LEGACY_LOCAL_ENDPOINT =
            "http://127.0.0.1:11434/v1/chat/completions"
        const val LEGACY_LOCAL_MODEL = "qwen3:4b"
    }
}
