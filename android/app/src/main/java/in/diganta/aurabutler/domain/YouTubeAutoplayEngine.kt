package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.data.CommandRepair
import `in`.diganta.aurabutler.data.GeminiCommandInterpreter
import `in`.diganta.aurabutler.data.ResolvedYouTubeVideo
import `in`.diganta.aurabutler.data.YouTubeVideoResolver
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ButlerReply
import `in`.diganta.aurabutler.model.ConversationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull

class YouTubeAutoplayEngine(
    private val commandInterpreter: GeminiCommandInterpreter =
        GeminiCommandInterpreter(),
    private val resolver: YouTubeVideoResolver =
        YouTubeVideoResolver()
) {
    suspend fun handle(
        message: String,
        context: ConversationContext
    ): ButlerReply? {
        val request = message.trim()
        val lower = request.lowercase(Locale.ROOT)

        if (
            lower.contains("spotify") ||
            lower.contains("youtube music") ||
            isYouTubeAppOnlyCommand(lower)
        ) {
            return null
        }

        val exactUrl = YOUTUBE_URL
            .find(request)
            ?.value
            ?.trimEnd('.', ',', ')')

        if (
            exactUrl != null &&
            lower.containsAny("open", "play", "youtube", "video")
        ) {
            return replyForUrl(
                url = exactUrl,
                title = "Play the requested YouTube video",
                text = "Playing the requested YouTube video.",
                intent = "youtube_exact_autoplay"
            )
        }

        val rawQuery = extractQuery(request) ?: return null
        val localQuery = CommandNormalizer
            .normalizeMediaQuery(rawQuery)
            .takeIf(String::isNotBlank)
            ?: return null

        val repair = withTimeoutOrNull(
            COMMAND_REPAIR_TIMEOUT_MS
        ) {
            runCatching {
                commandInterpreter.repairCommand(
                    raw = "play $localQuery on YouTube",
                    settings = context.settings,
                    mediaOnly = true
                )
            }.getOrNull()
        }

        val correctedQuery = correctedMediaQuery(
            localQuery = localQuery,
            repair = repair
        )

        if (!context.settings.autoPlayYouTube) {
            return searchFallback(
                query = correctedQuery,
                correction = correctionText(
                    original = rawQuery,
                    corrected = correctedQuery
                ),
                reason =
                    "Automatic YouTube playback is disabled in AURA Settings."
            )
        }

        val youtubeKey = context.settings.youtubeApiKey
            .trim()
            .ifBlank {
                context.settings.geminiApiKey.trim()
            }

        if (youtubeKey.isBlank()) {
            return searchFallback(
                query = correctedQuery,
                correction = correctionText(
                    original = rawQuery,
                    corrected = correctedQuery
                ),
                reason =
                    "Add a YouTube Data API key in AURA Settings for exact automatic playback."
            )
        }

        val resolved = withTimeoutOrNull(
            YOUTUBE_RESOLVE_TIMEOUT_MS
        ) {
            runCatching {
                resolver.resolve(
                    query = correctedQuery,
                    apiKey = youtubeKey
                )
            }.getOrNull()
        }

        return if (resolved != null) {
            resolvedReply(
                video = resolved,
                correction = correctionText(
                    original = rawQuery,
                    corrected = correctedQuery
                )
            )
        } else {
            searchFallback(
                query = correctedQuery,
                correction = correctionText(
                    original = rawQuery,
                    corrected = correctedQuery
                ),
                reason =
                    "I could not safely identify one exact video, so I opened YouTube results instead."
            )
        }
    }

    internal fun extractQuery(request: String): String? {
        val patterns = listOf(
            Regex(
                """(?i)^(?:please\s+)?(?:open|launch|start|show|go\s+to)\s+(?:the\s+)?youtube(?:\s+app)?\s*(?:(?:and|then)\s+|,\s*)?(?:play|find|search(?:\s+for)?)\s+(?:the\s+)?(?:song\s+|music\s+|video\s+)?(.+?)[.!?]*$"""
            ),
            Regex(
                """(?i)^(?:please\s+)?play\s+(?:the\s+)?(?:song\s+|music\s+|video\s+)?(.+?)(?:\s+on\s+youtube)?[.!?]*$"""
            ),
            Regex(
                """(?i)^(?:please\s+)?listen\s+to\s+(.+?)(?:\s+on\s+youtube)?[.!?]*$"""
            ),
            Regex(
                """(?i)^(?:please\s+)?put\s+on\s+(.+?)(?:\s+on\s+youtube)?[.!?]*$"""
            ),
            Regex(
                """(?i)^(?:please\s+)?(?:open|find|search)\s+(?:youtube\s+)?(?:for\s+)?(?:the\s+)?(?:song\s+|music\s+|video\s+)?(.+?)\s+(?:on|in)\s+youtube[.!?]*$"""
            ),
            Regex(
                """(?i)^youtube\s+(?:play\s+|video\s+|song\s+)?(.+?)[.!?]*$"""
            )
        )

        return patterns.firstNotNullOfOrNull { pattern ->
            pattern
                .find(request)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        it.length <= MAX_QUERY_CHARS &&
                        it.lowercase(Locale.ROOT) !in NON_QUERIES
                }
        }
    }

    private fun correctedMediaQuery(
        localQuery: String,
        repair: CommandRepair?
    ): String {
        val aiQuery = repair
            ?.mediaQuery
            ?.takeIf {
                repair.confidence >= MIN_AI_CONFIDENCE
            }
            ?.let(CommandNormalizer::normalizeMediaQuery)
            ?.takeIf(String::isNotBlank)

        return (aiQuery ?: localQuery)
            .take(MAX_QUERY_CHARS)
    }

    private fun resolvedReply(
        video: ResolvedYouTubeVideo,
        correction: String
    ): ButlerReply {
        val correctionPrefix = correction
            .takeIf(String::isNotBlank)
            ?.let { "$it " }
            .orEmpty()
        val text = correctionPrefix +
            "Playing “${video.title}” from ${video.channelTitle} on YouTube."

        return replyForUrl(
            url = video.watchUrl,
            title = "Play ${video.title}",
            text = text,
            intent = "youtube_exact_autoplay"
        )
    }

    private fun searchFallback(
        query: String,
        correction: String,
        reason: String
    ): ButlerReply {
        val url =
            "https://www.youtube.com/results?search_query=${encode(query)}"
        val prefix = correction
            .takeIf(String::isNotBlank)
            ?.let { "$it " }
            .orEmpty()

        return replyForUrl(
            url = url,
            title = "Find $query on YouTube",
            text = prefix + reason,
            intent = "youtube_search_fallback"
        )
    }

    private fun replyForUrl(
        url: String,
        title: String,
        text: String,
        intent: String
    ): ButlerReply = ButlerReply(
        assistantMessage = text,
        speechText = text,
        pendingActions = listOf(
            ButlerAction(
                id = UUID.randomUUID().toString(),
                kind = ActionKind.PLAY_MEDIA,
                title = title,
                summary =
                    "Open an exact or fallback YouTube result.",
                risk = ActionRisk.LOW,
                payload = mapOf("url" to url)
            )
        ),
        memoryWrites = emptyList(),
        provider = "youtube-smart-autoplay",
        intent = intent
    )

    private fun correctionText(
        original: String,
        corrected: String
    ): String {
        val cleanedOriginal =
            CommandNormalizer.normalizeMediaQuery(original)

        return if (
            cleanedOriginal.equals(
                corrected,
                ignoreCase = true
            )
        ) {
            ""
        } else {
            "I understood the title as “$corrected”."
        }
    }

    private fun isYouTubeAppOnlyCommand(
        lower: String
    ): Boolean = lower.matches(
        Regex(
            """^(?:please\s+)?(?:open|launch|start|show)\s+(?:the\s+)?youtube(?:\s+app)?[.!?]?$"""
        )
    )

    private fun encode(value: String): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name()
        )

    private fun String.containsAny(
        vararg values: String
    ): Boolean = values.any {
        contains(it, ignoreCase = true)
    }

    private companion object {
        val YOUTUBE_URL = Regex(
            """(?i)https://(?:www\.)?(?:youtube\.com|youtu\.be)/\S+"""
        )
        const val MAX_QUERY_CHARS = 180
        const val MIN_AI_CONFIDENCE = 0.72
        const val COMMAND_REPAIR_TIMEOUT_MS = 8_500L
        const val YOUTUBE_RESOLVE_TIMEOUT_MS = 10_000L
        val NON_QUERIES = setOf(
            "music",
            "song",
            "songs",
            "something",
            "anything",
            "youtube",
            "video"
        )
    }
}
