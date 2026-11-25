package `in`.diganta.aurabutler.runtime

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Remote manifest for student budget limits.
 * Loaded once at startup from the AURA API Gateway.
 * Zero limits = unlimited (JARVIS MODE).
 * This is the SINGLE source of truth for cloud budget ceilings.
 * Local DEVICE_ONLY execution NEVER consults this config.
 */
object StudentBudgetConfig {
    private const val TAG = "StudentBudgetConfig"
    private const val MANIFEST_URL = "https://diganta2006--aura-api-gateway.modal.run/v1/budget/manifest"
    private const val CACHE_FILE = "aura-budget-manifest-v1.json"
    private const val INIT_TIMEOUT_MS = 8_000L

    @Volatile private var initialized = false
    @Volatile private var initializationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedManifest = AtomicReference<BudgetManifest?>()

    /** Daily text request limit (0 = unlimited). */
    val DAILY_TEXT_LIMIT: Int
        get() = cachedManifest.get()?.textLimit ?: 0

    /** Daily vision request limit (0 = unlimited). */
    val DAILY_VISION_LIMIT: Int
        get() = cachedManifest.get()?.visionLimit ?: 0

    /** Daily research request limit (0 = unlimited). */
    val DAILY_RESEARCH_LIMIT: Int
        get() = cachedManifest.get()?.researchLimit ?: 0

    /** Daily auxiliary request limit (0 = unlimited). */
    val DAILY_AUXILIARY_LIMIT: Int
        get() = cachedManifest.get()?.auxiliaryLimit ?: 0

    /** Manifest schema version for compatibility checks. */
    val MANIFEST_VERSION: Int
        get() = cachedManifest.get()?.version ?: 1

    /** Whether the remote manifest has been successfully loaded at least once. */
    val isManifestLoaded: Boolean
        get() = cachedManifest.get() != null

    fun isInitialized(): Boolean = initialized

    /**
     * Initialize from remote manifest. Non-blocking; safe limits (0 = unlimited) are effective immediately.
     * Call once at Application startup.
     */
    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (initialized || initializationJob?.isActive == true) return
            initializationJob = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        loadManifest(app)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Budget manifest load failed; using unlimited defaults (JARVIS MODE).", e)
                } finally {
                    initialized = true
                }
            }
        }
    }

    private suspend fun loadManifest(context: Context) {
        // Try cached manifest first for instant startup
        val cached = readCachedManifest(context)
        if (cached != null) {
            cachedManifest.set(cached)
            Log.i(TAG, "Loaded cached budget manifest v${cached.version}: text=${cached.textLimit}, vision=${cached.visionLimit}, research=${cached.researchLimit}, auxiliary=${cached.auxiliaryLimit}")
        }

        // Fetch fresh manifest in background
        try {
            val fresh = withTimeout(INIT_TIMEOUT_MS) {
                fetchRemoteManifest()
            }
            if (fresh != null && (cached == null || fresh.version > cached.version)) {
                writeCachedManifest(context, fresh)
                cachedManifest.set(fresh)
                Log.i(TAG, "Updated budget manifest v${fresh.version}: text=${fresh.textLimit}, vision=${fresh.visionLimit}, research=${fresh.researchLimit}, auxiliary=${fresh.auxiliaryLimit}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote manifest fetch failed; using cached or unlimited defaults.", e)
        }
    }

    private suspend fun fetchRemoteManifest(): BudgetManifest? {
        // Use SafeHttpClient for bounded, validated HTTP
        val client = `in`.diganta.aurabutler.data.SafeHttpClient
        val response = client.postJsonFollowingTrustedRedirects(
            endpoint = MANIFEST_URL,
            body = "{}",
            userAgent = "AURA-Android/0.17.3",
            connectTimeoutMs = 5000,
            readTimeoutMs = 5000,
            maxResponseBytes = 1024 * 1024
        )
        if (response.status in 200..299 && response.body.isNotBlank()) {
            return parseManifest(response.body)
        }
        return null
    }

    private fun parseManifest(json: String): BudgetManifest? {
        return try {
            val obj = JSONObject(json)
            BudgetManifest(
                version = obj.optInt("version", 1),
                textLimit = obj.optInt("daily_text_limit", 0).coerceAtLeast(0),
                visionLimit = obj.optInt("daily_vision_limit", 0).coerceAtLeast(0),
                researchLimit = obj.optInt("daily_research_limit", 0).coerceAtLeast(0),
                auxiliaryLimit = obj.optInt("daily_auxiliary_limit", 0).coerceAtLeast(0),
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun readCachedManifest(context: Context): BudgetManifest? {
        val file = File(context.noBackupFilesDir, CACHE_FILE)
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            parseManifest(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCachedManifest(context: Context, manifest: BudgetManifest) {
        val file = File(context.noBackupFilesDir, CACHE_FILE)
        try {
            val json = JSONObject()
                .put("version", manifest.version)
                .put("daily_text_limit", manifest.textLimit)
                .put("daily_vision_limit", manifest.visionLimit)
                .put("daily_research_limit", manifest.researchLimit)
                .put("daily_auxiliary_limit", manifest.auxiliaryLimit)
                .put("fetched_at_epoch_ms", manifest.fetchedAtEpochMs)
                .toString()
            file.writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache budget manifest", e)
        }
    }

    data class BudgetManifest(
        val version: Int,
        val textLimit: Int,
        val visionLimit: Int,
        val researchLimit: Int,
        val auxiliaryLimit: Int,
        val fetchedAtEpochMs: Long
    )
}
