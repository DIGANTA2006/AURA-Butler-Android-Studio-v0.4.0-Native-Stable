package `in`.diganta.aurabutler.chat

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import `in`.diganta.aurabutler.model.ChatIndex
import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.ChatProject
import `in`.diganta.aurabutler.model.ChatSearchHit
import `in`.diganta.aurabutler.model.ChatThread
import `in`.diganta.aurabutler.model.MemoryItem
import `in`.diganta.aurabutler.model.Sender
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Encrypted storage for chat threads, projects and their messages.
 *
 * The active conversation is also kept under SecureStore's legacy message key, so
 * existing save paths keep working. Thread files are refreshed when the user switches
 * chats, starts or deletes one, or opens the chat list. Every read-modify-write runs
 * under one lock, and saves to a deleted thread are ignored so it cannot come back.
 */
class ChatThreadRepository(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        "aura_chat_threads",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** First run after this update: turns the existing single conversation into thread one. */
    fun bootstrap(legacyMessages: List<ChatMessage>, greeting: ChatMessage): ChatIndex {
        synchronized(lock) {
            val existing = readIndex()
            if (existing != null && existing.threads.isNotEmpty()) {
                val active = existing.threads.firstOrNull { it.id == existing.activeThreadId }?.id
                    ?: existing.threads.first().id
                val fixed = existing.copy(activeThreadId = active)
                writeIndex(fixed)
                return fixed
            }
            val messages = legacyMessages.ifEmpty { listOf(greeting) }
            val now = System.currentTimeMillis()
            val thread = ChatThread(
                id = newId(),
                title = ChatText.deriveTitle(messages) ?: ChatText.DEFAULT_TITLE,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                lastMessageId = messages.last().id
            )
            val index = ChatIndex(
                threads = listOf(thread),
                projects = emptyList(),
                activeThreadId = thread.id
            )
            writeMessages(thread.id, messages)
            writeIndex(index)
            return index
        }
    }

    fun index(): ChatIndex = synchronized(lock) {
        readIndex() ?: ChatIndex(emptyList(), emptyList(), "")
    }

    fun createThread(projectId: String?): ChatThread = synchronized(lock) {
        val now = System.currentTimeMillis()
        val thread = ChatThread(
            id = newId(),
            title = ChatText.DEFAULT_TITLE,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            projectId = projectId
        )
        val current = readIndex() ?: ChatIndex(emptyList(), emptyList(), "")
        writeIndex(current.copy(threads = listOf(thread) + current.threads))
        thread
    }

    fun loadMessages(threadId: String): List<ChatMessage> = synchronized(lock) {
        readMessages(threadId)
    }

    fun setActive(threadId: String) {
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            writeIndex(current.copy(activeThreadId = threadId))
        }
    }

    /** Saves a thread's messages and refreshes its title and last-updated time. Unknown threads are ignored. */
    fun saveMessages(threadId: String, messages: List<ChatMessage>) {
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            val target = current.threads.firstOrNull { it.id == threadId } ?: return@synchronized
            val trimmed = messages.takeLast(MAX_MESSAGES_PER_THREAD)
            writeMessages(threadId, trimmed)
            val lastId = trimmed.lastOrNull()?.id.orEmpty()
            val title = if (target.title == ChatText.DEFAULT_TITLE) {
                ChatText.deriveTitle(trimmed) ?: target.title
            } else {
                target.title
            }
            val updatedAt = if (lastId != target.lastMessageId) {
                System.currentTimeMillis()
            } else {
                target.updatedAtEpochMs
            }
            val updated = target.copy(title = title, updatedAtEpochMs = updatedAt, lastMessageId = lastId)
            writeIndex(current.copy(threads = current.threads.map { if (it.id == threadId) updated else it }))
        }
    }

    fun renameThread(threadId: String, title: String) {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(60)
        if (clean.isEmpty()) return
        updateThread(threadId) { it.copy(title = clean) }
    }

    fun togglePin(threadId: String) {
        updateThread(threadId) { it.copy(pinned = !it.pinned) }
    }

    fun moveThread(threadId: String, projectId: String?) {
        updateThread(threadId) { it.copy(projectId = projectId) }
    }

    fun deleteThread(threadId: String) {
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            prefs.edit().remove(messagesKey(threadId)).apply()
            writeIndex(current.copy(threads = current.threads.filterNot { it.id == threadId }))
        }
    }

    fun createProject(name: String, instructions: String) {
        val cleanName = name.trim().replace(Regex("\\s+"), " ").take(40)
        if (cleanName.isEmpty()) return
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            val project = ChatProject(
                id = newId(),
                name = cleanName,
                instructions = instructions.trim().take(MAX_INSTRUCTION_CHARS)
            )
            writeIndex(current.copy(projects = current.projects + project))
        }
    }

    /** Deleting a project keeps its chats; they move back to the recent list. */
    fun deleteProject(projectId: String) {
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            writeIndex(
                current.copy(
                    projects = current.projects.filterNot { it.id == projectId },
                    threads = current.threads.map {
                        if (it.projectId == projectId) it.copy(projectId = null) else it
                    }
                )
            )
        }
    }

    /** Project instructions for a chat, passed to the assistant as one memory item. */
    fun projectMemory(threadId: String): List<MemoryItem> {
        val current = index()
        val projectId = current.threads.firstOrNull { it.id == threadId }?.projectId ?: return emptyList()
        val project = current.projects.firstOrNull { it.id == projectId } ?: return emptyList()
        if (project.instructions.isBlank()) return emptyList()
        return listOf(
            MemoryItem(
                label = "Project instructions (${project.name})",
                value = project.instructions
            )
        )
    }

    fun search(query: String): List<ChatSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return synchronized(lock) {
            val hits = mutableListOf<ChatSearchHit>()
            for (thread in index().threads) {
                if (hits.size >= MAX_SEARCH_HITS) break
                val messageHit = readMessages(thread.id).lastOrNull { ChatText.matches(it.text, needle) }
                when {
                    messageHit != null -> hits += ChatSearchHit(
                        threadId = thread.id,
                        title = thread.title,
                        snippet = ChatText.snippet(messageHit.text, needle)
                    )
                    ChatText.matches(thread.title, needle) -> hits += ChatSearchHit(
                        threadId = thread.id,
                        title = thread.title,
                        snippet = "Title matches"
                    )
                }
            }
            hits
        }
    }

    private fun updateThread(threadId: String, transform: (ChatThread) -> ChatThread) {
        synchronized(lock) {
            val current = readIndex() ?: return@synchronized
            writeIndex(current.copy(threads = current.threads.map { if (it.id == threadId) transform(it) else it }))
        }
    }

    private fun readIndex(): ChatIndex? {
        val raw = prefs.getString(KEY_INDEX, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val threads = json.optJSONArray("threads") ?: JSONArray()
            val projects = json.optJSONArray("projects") ?: JSONArray()
            ChatIndex(
                threads = (0 until threads.length()).map { readThread(threads.getJSONObject(it)) },
                projects = (0 until projects.length()).map { readProject(projects.getJSONObject(it)) },
                activeThreadId = json.optString("active", "")
            )
        }.getOrNull()
    }

    private fun writeIndex(index: ChatIndex) {
        val json = JSONObject()
            .put("active", index.activeThreadId)
            .put("threads", JSONArray(index.threads.map { writeThread(it) }))
            .put("projects", JSONArray(index.projects.map { writeProject(it) }))
        prefs.edit().putString(KEY_INDEX, json.toString()).apply()
    }

    private fun readThread(json: JSONObject): ChatThread = ChatThread(
        id = json.getString("id"),
        title = json.optString("title", ChatText.DEFAULT_TITLE),
        createdAtEpochMs = json.optLong("createdAt", 0L),
        updatedAtEpochMs = json.optLong("updatedAt", 0L),
        pinned = json.optBoolean("pinned", false),
        projectId = json.optString("projectId", "").ifBlank { null },
        lastMessageId = json.optString("lastMessageId", "")
    )

    private fun writeThread(thread: ChatThread): JSONObject = JSONObject()
        .put("id", thread.id)
        .put("title", thread.title)
        .put("createdAt", thread.createdAtEpochMs)
        .put("updatedAt", thread.updatedAtEpochMs)
        .put("pinned", thread.pinned)
        .put("projectId", thread.projectId.orEmpty())
        .put("lastMessageId", thread.lastMessageId)

    private fun readProject(json: JSONObject): ChatProject = ChatProject(
        id = json.getString("id"),
        name = json.optString("name", "Project"),
        instructions = json.optString("instructions", "")
    )

    private fun writeProject(project: ChatProject): JSONObject = JSONObject()
        .put("id", project.id)
        .put("name", project.name)
        .put("instructions", project.instructions)

    private fun readMessages(threadId: String): List<ChatMessage> {
        val raw = prefs.getString(messagesKey(threadId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val json = array.getJSONObject(i)
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
        }.getOrDefault(emptyList())
    }

    private fun writeMessages(threadId: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("sender", message.sender.name)
                    .put("text", message.text)
                    .put("createdAt", message.createdAtEpochMs)
                    .put("provider", message.provider)
                    .put("intent", message.intent)
                    .put("latencyMs", message.latencyMs)
            )
        }
        prefs.edit().putString(messagesKey(threadId), array.toString()).apply()
    }

    private fun messagesKey(threadId: String): String = "msgs_$threadId"

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val KEY_INDEX = "index"
        const val MAX_MESSAGES_PER_THREAD = 80
        const val MAX_SEARCH_HITS = 40
        const val MAX_INSTRUCTION_CHARS = 2_000
    }
}