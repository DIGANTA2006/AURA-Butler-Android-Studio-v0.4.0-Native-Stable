package `in`.diganta.aurabutler.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Remote configuration manifest for AURA endpoints, models, and budgets.
 * Fetched from a GitHub Pages/Gist URL on first run, cached for 24 hours.
 * Falls back to hardcoded defaults if network and cache both fail.
 */
data class EndpointManifest(
    val version: Int,
    val textEndpoint: String,
    val visionEndpoint: String,
    val researchEndpoint: String,
    val models: Models,
    val budgets: Budgets
) {
    data class Models(
        val text: String,
        val vision: String,
        val research: String
    )
    data class Budgets(
        val dailyText: Int,
        val dailyVision: Int,
        val dailyResearch: Int,
        val dailyAuxiliary: Int
    )
}

object RemoteConfig {
    /**
     * Update this URL to point to your hosted manifest (GitHub Pages, Gist, etc.)
     * Example: https://raw.githubusercontent.com/<user>/<repo>/main/endpoint-manifest.json
     */
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/diganta2006/aura-config/main/endpoint-manifest.json"
    private const val CACHE_KEY = "endpoint_manifest_cache"
    private const val CACHE_TS_KEY = "endpoint_manifest_cache_ts"
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

    @Volatile private var cachedManifest: EndpointManifest? = null

    /**
     * Fetches the manifest from network, falls back to cache, then to hardcoded defaults.
     * Thread-safe: runs on IO dispatcher, safe to call from main thread.
     */
    suspend fun fetchOrCached(context: Context): EndpointManifest = withContext(Dispatchers.IO) {
        if (cachedManifest != null) return@withContext cachedManifest!!

        // Try network first
        val networkManifest = try {
            val url = URL(MANIFEST_URL)
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val json = connection.getInputStream().readBytes().decodeToString()
            parseManifest(json).also { saveCache(context, json) }
        } catch (e: Exception) {
            null
        }

        // Fallback to cache
        val cached = networkManifest ?: loadCache(context)
            ?: EndpointManifest(
                version = 2,
                textEndpoint = "https://diganta2006--aura-dual-model-server-qwentext-web.modal.run/v1/chat/completions",
                visionEndpoint = "https://diganta2006--aura-dual-model-server-gemmavision-web.modal.run/v1/chat/completions",
                researchEndpoint = "https://diganta2006--aura-dual-model-server-researchgateway-web.modal.run/v1/research",
                models = EndpointManifest.Models(
                    text = "Qwen/Qwen3-8B",
                    vision = "google/gemma-3-4b-it",
                    research = "Qwen/Qwen3-8B"
                ),
                budgets = EndpointManifest.Budgets(
                    dailyText = 0,      // JARVIS MODE: 0 = unlimited
                    dailyVision = 0,    // JARVIS MODE: 0 = unlimited
                    dailyResearch = 0,  // JARVIS MODE: 0 = unlimited
                    dailyAuxiliary = 0  // JARVIS MODE: 0 = unlimited
                )
            )

        cachedManifest = cached
        cached
    }

    /** Forces a refetch on next call (e.g., after user changes config URL in settings). */
    fun invalidateCache() { cachedManifest = null }

    /** Returns the cached manifest synchronously, or null if not loaded yet. */
    fun getCached(): EndpointManifest? = cachedManifest

    private fun parseManifest(json: String): EndpointManifest {
        val root = JSONObject(json)
        return EndpointManifest(
            version = root.getInt("version"),
            textEndpoint = root.getString("textEndpoint"),
            visionEndpoint = root.getString("visionEndpoint"),
            researchEndpoint = root.getString("researchEndpoint"),
            models = EndpointManifest.Models(
                text = root.getJSONObject("models").getString("text"),
                vision = root.getJSONObject("models").getString("vision"),
                research = root.getJSONObject("models").getString("research")
            ),
            budgets = EndpointManifest.Budgets(
                dailyText = root.getJSONObject("budgets").getInt("dailyText"),
                dailyVision = root.getJSONObject("budgets").getInt("dailyVision"),
                dailyResearch = root.getJSONObject("budgets").getInt("dailyResearch"),
                dailyAuxiliary = root.getJSONObject("budgets").getInt("dailyAuxiliary")
            )
        )
    }

    private fun saveCache(context: Context, json: String) {
        context.getSharedPreferences("aura_remote_config", Context.MODE_PRIVATE)
            .edit()
            .putString(CACHE_KEY, json)
            .putLong(CACHE_TS_KEY, System.currentTimeMillis())
            .apply()
    }

    private fun loadCache(context: Context): EndpointManifest? {
        val prefs = context.getSharedPreferences("aura_remote_config", Context.MODE_PRIVATE)
        val json = prefs.getString(CACHE_KEY, null)
        val ts = prefs.getLong(CACHE_TS_KEY, 0)
        if (json == null || System.currentTimeMillis() - ts > CACHE_TTL_MS) return null
        return runCatching { parseManifest(json) }.getOrNull()
    }
}
