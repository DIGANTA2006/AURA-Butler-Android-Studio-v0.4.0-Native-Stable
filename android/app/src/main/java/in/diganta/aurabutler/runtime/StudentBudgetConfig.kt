package `in`.diganta.aurabutler.runtime

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Student-safe external AI request limits. Zero means unlimited. */
object StudentBudgetConfig {
    // JARVIS MODE: Zero = unlimited. No daily caps.
    private const val DEFAULT_DAILY_TEXT_LIMIT = 0
    private const val DEFAULT_DAILY_VISION_LIMIT = 0
    private const val DEFAULT_DAILY_RESEARCH_LIMIT = 0
    private const val DEFAULT_DAILY_AUXILIARY_LIMIT = 0

    @Volatile private var cachedTextLimit: Int = DEFAULT_DAILY_TEXT_LIMIT
    @Volatile private var cachedVisionLimit: Int = DEFAULT_DAILY_VISION_LIMIT
    @Volatile private var cachedResearchLimit: Int = DEFAULT_DAILY_RESEARCH_LIMIT
    @Volatile private var cachedAuxiliaryLimit: Int = DEFAULT_DAILY_AUXILIARY_LIMIT
    @Volatile private var initialized = false
    private val initializationMutex = Mutex()

    /** Applies a manifest atomically after its asynchronous fetch has completed. */
    private fun applyManifest(manifest: EndpointManifest) {
        cachedTextLimit = manifest.budgets.dailyText.coerceAtLeast(0)
        cachedVisionLimit = manifest.budgets.dailyVision.coerceAtLeast(0)
        cachedResearchLimit = manifest.budgets.dailyResearch.coerceAtLeast(0)
        cachedAuxiliaryLimit = manifest.budgets.dailyAuxiliary.coerceAtLeast(0)
        initialized = true
    }

    /** Current daily text request limit (0 = unlimited). */
    val DAILY_TEXT_LIMIT: Int
        get() = cachedTextLimit

    /** Current daily vision request limit (0 = unlimited). */
    val DAILY_VISION_LIMIT: Int
        get() = cachedVisionLimit

    /** Current daily research request limit (0 = unlimited). */
    val DAILY_RESEARCH_LIMIT: Int
        get() = cachedResearchLimit

    /** Current daily auxiliary request limit (0 = unlimited). */
    val DAILY_AUXILIARY_LIMIT: Int
        get() = cachedAuxiliaryLimit

    /**
     * Initializes budgets from the remote manifest.
     * Safe to call multiple times; subsequent calls are no-ops.
     * Runs on IO dispatcher - safe to call from main thread.
     */
    suspend fun initialize(context: Context) {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return@withLock
            val manifest = RemoteConfig.fetchOrCached(context.applicationContext)
            applyManifest(manifest)
        }
    }

    /**
     * Forces a re-initialization from the remote manifest (e.g., after config refresh).
     * Use RemoteConfig.invalidateCache() first if you want to force a network fetch.
     */
    suspend fun refresh(context: Context) {
        initializationMutex.withLock {
            initialized = false
            val manifest = RemoteConfig.fetchOrCached(context.applicationContext)
            applyManifest(manifest)
        }
    }

    /** Returns true if budgets have been loaded from remote manifest (not just fallbacks). */
    fun isInitialized(): Boolean = initialized

    /** Resets to hardcoded fallbacks (for testing). */
    internal fun resetForTesting() {
        cachedTextLimit = DEFAULT_DAILY_TEXT_LIMIT
        cachedVisionLimit = DEFAULT_DAILY_VISION_LIMIT
        cachedResearchLimit = DEFAULT_DAILY_RESEARCH_LIMIT
        cachedAuxiliaryLimit = DEFAULT_DAILY_AUXILIARY_LIMIT
        initialized = false
    }
}
