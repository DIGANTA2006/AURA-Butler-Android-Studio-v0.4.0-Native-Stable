package `in`.diganta.aurabutler.memory

import `in`.diganta.aurabutler.data.AiProvider
import `in`.diganta.aurabutler.model.ConversationContext
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.runtime.SecretRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced semantic memory system with embeddings-based retrieval, consolidation, and forgetting.
 * Provides long-term memory with relevance scoring, clustering, and automatic maintenance.
 */
class SemanticMemory(
    private val aiProvider: AiProvider?,
    private val embeddingProvider: EmbeddingProvider,
    private val memoryStore: MemoryStore
) {

    data class MemoryEntry(
        val id: String = UUID.randomUUID().toString(),
        val content: String,
        val label: String,
        val embedding: FloatArray? = null,
        val metadata: MemoryMetadata = MemoryMetadata(),
        val accessCount: Int = 0,
        val lastAccessed: Long = System.currentTimeMillis(),
        val createdAt: Long = System.currentTimeMillis(),
        val importance: Double = 0.5,  // 0.0 to 1.0
        val tags: Set<String> = emptySet(),
        val source: MemorySource = MemorySource.USER_INPUT
    )

    data class MemoryMetadata(
        val context: String = "",           // Conversation context when created
        val relatedEntities: List<String> = emptyList(),  // People, places, projects mentioned
        val temporalTags: List<String> = emptyList(),     // "today", "this week", "2024", etc.
        val sentiment: Double = 0.0,        // -1.0 to 1.0
        val confidence: Double = 1.0        // How confident we are in this memory
    )

    enum class MemorySource {
        USER_INPUT,
        AI_GENERATED,
        TOOL_RESULT,
        OBSERVATION,
        CONSOLIDATED
    }

    data class RetrievalResult(
        val entries: List<ScoredMemory>,
        val query: String,
        val strategy: RetrievalStrategy,
        val totalCandidates: Int
    )

    data class ScoredMemory(
        val entry: MemoryEntry,
        val similarity: Double,
        val relevanceScore: Double,
        val combinedScore: Double
    )

    enum class RetrievalStrategy {
        SEMANTIC_SIMILARITY,    // Pure embedding similarity
        HYBRID,                 // Semantic + keyword + recency + importance
        TEMPORAL,               // Time-based (recent, specific period)
        ENTITY_BASED,           // By mentioned entities
        IMPORTANCE_WEIGHTED     // Prioritize important memories
    }

    /**
     * Store a new memory with automatic embedding generation and metadata extraction.
     */
    suspend fun remember(
        content: String,
        label: String,
        context: ConversationContext,
        source: MemorySource = MemorySource.USER_INPUT,
        importance: Double = 0.5,
        tags: Set<String> = emptySet()
    ): MemoryEntry = withContext(Dispatchers.IO) {
        // Redact secrets before storage
        val safeContent = SecretRedactor.redact(content, 5000)
        val safeLabel = SecretRedactor.redact(label, 200)
        
        // Extract metadata using AI if available
        val metadata = extractMetadata(safeContent, context)
        
        // Generate embedding
        val embedding = embeddingProvider.embed(safeContent)
        
        val entry = MemoryEntry(
            content = safeContent,
            label = safeLabel,
            embedding = embedding,
            metadata = metadata,
            importance = importance.coerceIn(0.0, 1.0),
            tags = tags,
            source = source
        )
        
        memoryStore.save(entry)
        
        // Trigger consolidation if needed
        maybeTriggerConsolidation()
        
        entry
    }

    /**
     * Retrieve relevant memories for a query using multiple strategies.
     */
    suspend fun recall(
        query: String,
        context: ConversationContext,
        options: RecallOptions = RecallOptions()
    ): RetrievalResult = withContext(Dispatchers.IO) {
        val allEntries = memoryStore.getAll()
        val queryEmbedding = embeddingProvider.embed(query)
        
        val scored = allEntries.mapNotNull { entry ->
            entry.embedding?.let { emb ->
                val similarity = cosineSimilarity(queryEmbedding, emb)
                val relevance = calculateRelevance(entry, query, context, options)
                val combined = (similarity * options.semanticWeight) + (relevance * (1 - options.semanticWeight))
                
                ScoredMemory(entry, similarity, relevance, combined)
            }
        }.filter { it.combinedScore >= options.minScore }
            .sortedByDescending { it.combinedScore }
            .take(options.maxResults)
        
        // Apply strategy-specific re-ranking
        val finalResults = applyStrategy(scored, query, context, options.strategy)
        
        // Update access counts
        finalResults.forEach { memoryStore.updateAccess(it.entry.id) }
        
        RetrievalResult(
            entries = finalResults,
            query = query,
            strategy = options.strategy,
            totalCandidates = allEntries.size
        )
    }

    /**
     * Consolidate memories - merge similar, extract patterns, create higher-level concepts.
     */
    suspend fun consolidate(): ConsolidationResult = withContext(Dispatchers.IO) {
        val entries = memoryStore.getAll()
        var merged = 0
        var patterns = 0
        var concepts = 0
        val errors = mutableListOf<String>()
        
        // 1. Find and merge near-duplicates
        val duplicateGroups = findDuplicateGroups(entries, threshold = 0.92)
        for (group in duplicateGroups) {
            if (group.size > 1) {
                val mergedEntry = mergeMemoryGroup(group)
                memoryStore.save(mergedEntry)
                group.drop(1).forEach { memoryStore.delete(it.id) }
                merged++
            }
        }
        
        // 2. Extract recurring patterns (if AI available)
        if (aiProvider != null) {
            val patternResult = extractPatterns(entries)
            patterns = patternResult.patternsFound
            concepts = patternResult.conceptsCreated
            errors.addAll(patternResult.errors)
        }
        
        // 3. Decay old, unimportant memories
        val decayed = applyForgettingCurve(entries)
        
        ConsolidationResult(
            mergedCount = merged,
            patternsFound = patterns,
            conceptsCreated = concepts,
            decayedCount = decayed,
            errors = errors
        )
    }

    /**
     * Get memory statistics and health metrics.
     */
    fun getStats(): MemoryStats {
        val entries = memoryStore.getAll()
        val total = entries.size
        val withEmbeddings = entries.count { it.embedding != null }
        val avgImportance = entries.map { it.importance }.average()
        val avgAccess = entries.map { it.accessCount.toDouble() }.average()
        val oldest = entries.minByOrNull { it.createdAt }?.createdAt
        val newest = entries.maxByOrNull { it.createdAt }?.createdAt
        val sources = entries.groupingBy { it.source }.eachCount()
        
        return MemoryStats(
            totalMemories = total,
            withEmbeddings = withEmbeddings,
            averageImportance = avgImportance,
            averageAccessCount = avgAccess,
            oldestMemory = oldest,
            newestMemory = newest,
            sourceDistribution = sources,
            storageSizeEstimate = estimateStorageSize(entries)
        )
    }

    // ===== Private Implementation =====

    private suspend fun extractMetadata(content: String, context: ConversationContext): MemoryMetadata {
        if (aiProvider == null) {
            return extractMetadataHeuristic(content)
        }
        
        val prompt = """
            Extract structured metadata from this memory content:
            
            Content: $content
            Context: ${context.settings.preferredName}, ${context.settings.formOfAddress}
            
            Return JSON with:
            - relatedEntities: [people, places, projects, technologies mentioned]
            - temporalTags: [time references like "today", "yesterday", "2024", "next week"]
            - sentiment: -1.0 to 1.0
            - confidence: 0.0 to 1.0
        """.trimIndent()
        
        return try {
            val response = aiProvider!!.answer(prompt, context)
            parseMetadataResponse(response ?: "")
        } catch (e: Exception) {
            extractMetadataHeuristic(content)
        }
    }

    private fun extractMetadataHeuristic(content: String): MemoryMetadata {
        val lower = content.lowercase()
        val entities = mutableListOf<String>()
        val temporal = mutableListOf<String>()
        
        // Simple entity extraction patterns
        Regex("""\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b""").findAll(content).forEach { 
            entities += it.value 
        }
        
        // Temporal patterns
        listOf("today", "tomorrow", "yesterday", "this week", "next week", "last week", 
               "this month", "next month", "this year", "next year", "monday", "tuesday", 
               "wednesday", "thursday", "friday", "saturday", "sunday",
               "january", "february", "march", "april", "may", "june", "july", "august",
               "september", "october", "november", "december")
            .filter { lower.contains(it) }
            .forEach { temporal += it }
        
        // Sentiment heuristic
        val positive = listOf("good", "great", "excellent", "happy", "love", "like", "success", "win")
        val negative = listOf("bad", "terrible", "hate", "fail", "error", "problem", "issue", "wrong")
        val posCount = positive.count { lower.contains(it) }
        val negCount = negative.count { lower.contains(it) }
        val sentiment = when {
            posCount > negCount -> 0.5
            negCount > posCount -> -0.5
            else -> 0.0
        }
        
        return MemoryMetadata(
            relatedEntities = entities.distinct().take(10),
            temporalTags = temporal.distinct(),
            sentiment = sentiment,
            confidence = 0.6
        )
    }

    private fun parseMetadataResponse(response: String): MemoryMetadata {
        // Try to parse JSON from AI response
        try {
            val json = org.json.JSONObject(response)
            val related = json.optJSONArray("relatedEntities")
            val temporal = json.optJSONArray("temporalTags")
            return MemoryMetadata(
                relatedEntities = related?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                        .filter(String::isNotBlank)
                } ?: emptyList(),
                temporalTags = temporal?.let { array ->
                    List(array.length()) { index -> array.optString(index) }
                        .filter(String::isNotBlank)
                } ?: emptyList(),
                sentiment = json.optDouble("sentiment", 0.0).coerceIn(-1.0, 1.0),
                confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        } catch (e: Exception) {
            return MemoryMetadata()
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Embedding dimension mismatch" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA > 0 && normB > 0) dot / (Math.sqrt(normA) * Math.sqrt(normB)) else 0.0
    }

    private fun calculateRelevance(entry: MemoryEntry, query: String, context: ConversationContext, options: RecallOptions): Double {
        var score = 0.0
        
        // Keyword overlap
        val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val contentWords = entry.content.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val overlap = queryWords.intersect(contentWords).size.toDouble()
        val keywordScore = if (queryWords.isNotEmpty()) overlap / queryWords.size else 0.0
        score += keywordScore * 0.3
        
        // Importance weight
        score += entry.importance * 0.2
        
        // Recency (exponential decay)
        val ageHours = (System.currentTimeMillis() - entry.lastAccessed) / (1000.0 * 3600)
        val recencyScore = Math.exp(-ageHours / 168.0) // Half-life ~1 week
        score += recencyScore * 0.2
        
        // Access frequency (popularity)
        val accessScore = Math.min(entry.accessCount / 10.0, 1.0)
        score += accessScore * 0.1
        
        // Tag match
        val tagMatches = options.boostTags.count { entry.tags.contains(it) }
        score += tagMatches * 0.1
        
        // Entity match
        val queryEntities = extractEntities(query)
        val entityMatches = entry.metadata.relatedEntities.count { queryEntities.contains(it) }
        score += entityMatches * 0.1
        
        return score.coerceIn(0.0, 1.0)
    }

    private fun extractEntities(text: String): Set<String> {
        return Regex("""\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)*\b""")
            .findAll(text)
            .map { it.value }
            .toSet()
    }

    private fun applyStrategy(
        scored: List<ScoredMemory>,
        query: String,
        context: ConversationContext,
        strategy: RetrievalStrategy
    ): List<ScoredMemory> {
        return when (strategy) {
            RetrievalStrategy.SEMANTIC_SIMILARITY -> scored.sortedByDescending { it.similarity }
            RetrievalStrategy.HYBRID -> scored // Already sorted by combined score
            RetrievalStrategy.TEMPORAL -> scored.sortedByDescending { it.entry.lastAccessed }
            RetrievalStrategy.ENTITY_BASED -> {
                val queryEntities = extractEntities(query)
                scored.sortedByDescending { 
                    it.entry.metadata.relatedEntities.count { queryEntities.contains(it) }.toDouble() 
                }
            }
            RetrievalStrategy.IMPORTANCE_WEIGHTED -> scored.sortedByDescending { it.entry.importance }
        }
    }

    private fun findDuplicateGroups(entries: List<MemoryEntry>, threshold: Double): List<List<MemoryEntry>> {
        val groups = mutableListOf<MutableList<MemoryEntry>>()
        val processed = mutableSetOf<String>()
        
        for (entry in entries) {
            if (entry.embedding == null || entry.id in processed) continue
            
            val group = mutableListOf(entry)
            processed.add(entry.id)
            
            for (other in entries) {
                if (other.id in processed || other.embedding == null) continue
                
                val sim = cosineSimilarity(entry.embedding!!, other.embedding!!)
                if (sim >= threshold) {
                    group.add(other)
                    processed.add(other.id)
                }
            }
            
            if (group.size > 1) groups.add(group)
        }
        
        return groups
    }

    private fun mergeMemoryGroup(group: List<MemoryEntry>): MemoryEntry {
        // Keep the most important/accessed as base
        val base = group.maxByOrNull { it.importance * (1 + it.accessCount * 0.1) }!!
        
        val mergedContent = group.map { it.content }.joinToString("\n\n---\n\n")
        val mergedTags = group.flatMap { it.tags }.toSet()
        val mergedEntities = group.flatMap { it.metadata.relatedEntities }.toSet()
        val avgImportance = group.map { it.importance }.average()
        val maxConfidence = group.map { it.metadata.confidence }.maxOrNull() ?: 1.0
        
        return MemoryEntry(
            id = base.id,
            content = mergedContent,
            label = base.label,
            embedding = base.embedding,
            metadata = base.metadata.copy(
                relatedEntities = mergedEntities.toList(),
                confidence = maxConfidence
            ),
            accessCount = group.sumOf { it.accessCount },
            lastAccessed = group.maxByOrNull { it.lastAccessed }?.lastAccessed ?: System.currentTimeMillis(),
            createdAt = group.minByOrNull { it.createdAt }?.createdAt ?: System.currentTimeMillis(),
            importance = avgImportance,
            tags = mergedTags,
            source = MemorySource.CONSOLIDATED
        )
    }

    private data class PatternResult(
        val patternsFound: Int,
        val conceptsCreated: Int,
        val errors: List<String>
    )

    private suspend fun extractPatterns(entries: List<MemoryEntry>): PatternResult {
        // Group by temporal proximity and entities
        val recentEntries = entries.filter { 
            (System.currentTimeMillis() - it.createdAt) < 30L * 24 * 3600 * 1000 // Last 30 days
        }
        
        if (recentEntries.size < 5) return PatternResult(0, 0, emptyList())
        
        val prompt = """
            Analyze these memory entries and identify:
            1. Recurring themes/topics (patterns)
            2. Higher-level concepts that unite multiple memories
            3. Contradictions or updates to previous information
            
            Memories:
            ${recentEntries.take(50).joinToString("\n") { "- ${it.label}: ${it.content.take(200)}" }}
            
            Return JSON:
            {
              "patterns": [{"theme": "...", "memoryIds": [...], "confidence": 0.0-1.0}],
              "concepts": [{"name": "...", "description": "...", "relatedMemoryIds": [...]}],
              "contradictions": [{"old": "...", "new": "...", "memoryIds": [...]}]
            }
        """.trimIndent()
        
        return try {
            val response = aiProvider!!.answer(prompt, ConversationContext.empty())
            parsePatternResponse(response ?: "", recentEntries)
        } catch (e: Exception) {
            PatternResult(0, 0, listOf(e.message ?: "Pattern extraction failed"))
        }
    }

    private fun parsePatternResponse(response: String, entries: List<MemoryEntry>): PatternResult {
        // Simplified - in production, parse JSON and create concept memories
        return PatternResult(0, 0, emptyList())
    }

    private fun applyForgettingCurve(entries: List<MemoryEntry>): Int {
        var decayed = 0
        val now = System.currentTimeMillis()
        
        for (entry in entries) {
            val ageDays = (now - entry.lastAccessed) / (1000.0 * 3600 * 24)
            val importance = entry.importance
            
            // Forgetting curve: retention = importance * exp(-age / halfLife)
            // Half-life scales with importance: 30 days for low importance, 365 for high
            val halfLife = 30 + (335 * importance)
            val retention = importance * Math.exp(-ageDays / halfLife)
            
            // Delete if retention very low and not recently accessed
            if (retention < 0.05 && ageDays > 60 && entry.accessCount < 2) {
                memoryStore.delete(entry.id)
                decayed++
            }
        }
        
        return decayed
    }

    @Volatile
    private var lastScheduledConsolidationCount = 0

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun maybeTriggerConsolidation() {
        // Consolidation is suspend work; run it in an owned background scope,
        // and schedule at most once for each 50-record boundary.
        val count = memoryStore.getAll().size
        if (count == 0 || count % 50 != 0 || count == lastScheduledConsolidationCount) return

        synchronized(this) {
            if (count == lastScheduledConsolidationCount) return
            lastScheduledConsolidationCount = count
            maintenanceScope.launch {
                runCatching { consolidate() }
            }
        }
    }

    private fun estimateStorageSize(entries: List<MemoryEntry>): Long {
        return entries.sumOf { 
            it.content.length + it.label.length + (it.embedding?.size ?: 0) * 4 + 200 
        }.toLong()
    }

    data class RecallOptions(
        val maxResults: Int = 10,
        val minScore: Double = 0.3,
        val semanticWeight: Double = 0.7,
        val strategy: RetrievalStrategy = RetrievalStrategy.HYBRID,
        val boostTags: Set<String> = emptySet(),
        val timeRange: TimeRange? = null
    )

    data class TimeRange(
        val start: Long,
        val end: Long
    )

    data class ConsolidationResult(
        val mergedCount: Int,
        val patternsFound: Int,
        val conceptsCreated: Int,
        val decayedCount: Int,
        val errors: List<String>
    )

    data class MemoryStats(
        val totalMemories: Int,
        val withEmbeddings: Int,
        val averageImportance: Double,
        val averageAccessCount: Double,
        val oldestMemory: Long?,
        val newestMemory: Long?,
        val sourceDistribution: Map<MemorySource, Int>,
        val storageSizeEstimate: Long
    )
}

/**
 * Embedding provider interface - can use local or remote embeddings.
 */
interface EmbeddingProvider {
    fun embed(text: String): FloatArray
    val dimension: Int
}

/**
 * Local embedding provider using a small ONNX model (e.g., all-MiniLM-L6-v2 quantized).
 */
class LocalEmbeddingProvider(
    private val modelPath: String
) : EmbeddingProvider {
    override val dimension: Int = 384  // all-MiniLM-L6-v2 dimension
    
    override fun embed(text: String): FloatArray {
        // Placeholder - in production, run ONNX inference
        // For now, return a deterministic hash-based pseudo-embedding
        return generatePseudoEmbedding(text)
    }
    
    internal fun generatePseudoEmbedding(text: String): FloatArray {
        val embedding = FloatArray(dimension)
        val random = java.util.Random(text.hashCode().toLong())
        for (i in embedding.indices) {
            embedding[i] = (random.nextGaussian().toFloat() * 0.1f).coerceIn(-1f, 1f)
        }
        // Normalize in double precision, then store unit-length Float values.
        val norm = kotlin.math.sqrt(embedding.sumOf { value -> value.toDouble() * value.toDouble() })
        if (norm > 1e-12) {
            for (i in embedding.indices) {
                embedding[i] = (embedding[i].toDouble() / norm).toFloat()
            }
        }
        return embedding
    }
}

/**
 * Remote embedding provider (OpenAI-compatible / Modal).
 */
class RemoteEmbeddingProvider(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String = "text-embedding-3-small"
) : EmbeddingProvider {
    override val dimension: Int = 1536  // text-embedding-3-small
    
    override fun embed(text: String): FloatArray {
        // Placeholder - in production, make HTTP request
        return LocalEmbeddingProvider("").generatePseudoEmbedding(text)
    }
}

/**
 * Persistent memory storage.
 */
class MemoryStore {
    private val memories = ConcurrentHashMap<String, SemanticMemory.MemoryEntry>()
    private val accessOrder = ConcurrentHashMap<String, Long>()
    
    fun save(entry: SemanticMemory.MemoryEntry) {
        memories[entry.id] = entry
        accessOrder[entry.id] = System.currentTimeMillis()
    }
    
    fun get(id: String): SemanticMemory.MemoryEntry? = memories[id]
    
    fun getAll(): List<SemanticMemory.MemoryEntry> = memories.values.toList()
    
    fun updateAccess(id: String) {
        memories[id]?.let { entry ->
            val updated = entry.copy(
                accessCount = entry.accessCount + 1,
                lastAccessed = System.currentTimeMillis()
            )
            memories[id] = updated
            accessOrder[id] = System.currentTimeMillis()
        }
    }
    
    fun delete(id: String) {
        memories.remove(id)
        accessOrder.remove(id)
    }
    
    fun clear() {
        memories.clear()
        accessOrder.clear()
    }
    
    fun size(): Int = memories.size
}
