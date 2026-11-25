<#
  AURA Phase 3 - chat features (ChatGPT / Claude style)

  1. Multiple chats: New chat, switch, rename, delete. Your current conversation is
     kept as one chat, so nothing is lost on the first run.
  2. Pinned chats: pin a chat to the top of the list.
  3. Projects: group chats under a project with its own instructions. The instructions
     are sent to AURA with every message in that project's chats.
  4. Search: find a chat by title or by any message text, with a short excerpt.
  5. Everything is stored encrypted on the phone, the same way as the rest of AURA.

  Open the chat list with the chat icon in the top bar.

  Notes:
  - Switching, starting or deleting a chat is blocked while AURA is still replying.
  - Project instructions reach a cloud AI provider only if "Allow private context to AI
    providers" is ON in Settings. On-device replies always receive them.

  Run:   powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase3-Chats.ps1
  Undo:  powershell -ExecutionPolicy Bypass -File .\Apply-AURA-Phase3-Chats.ps1 -Undo
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot = 'E:\MY PROJECT FOLDER\AURA PROJECT\AURA-Butler-Android-Studio-v0.4.0-Native-Stable',
    [switch]$Undo
)

$ErrorActionPreference = 'Stop'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nl = "`n"

$appRoot  = 'android\app\src\main'
$base     = "$appRoot\java\in\diganta\aurabutler"
$testBase = 'android\app\src\test\java\in\diganta\aurabutler'

$editedFiles = @(
    "$base\model\Models.kt",
    "$base\ButlerViewModel.kt",
    "$base\ui\AuraApp.kt"
)
$newFiles = @(
    "$base\chat\ChatText.kt",
    "$base\chat\ChatThreadRepository.kt",
    "$base\ui\ChatHub.kt",
    "$testBase\chat\ChatTextTest.kt"
)
$backupRoot = Join-Path $ProjectRoot 'backups'

function Write-Step([string]$text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }

function Edit-File {
    param([string]$RelPath, [string]$Old, [string]$New)
    $path = Join-Path $ProjectRoot $RelPath
    $raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $crlf = $raw.Contains("`r`n")
    $text = $raw.Replace("`r`n", "`n")
    $oldLf = $Old.Replace("`r`n", "`n")
    $newLf = $New.Replace("`r`n", "`n")

    $count = 0
    $idx = 0
    while (($idx = $text.IndexOf($oldLf, $idx, [System.StringComparison]::Ordinal)) -ge 0) {
        $count++
        $idx += $oldLf.Length
    }
    if ($count -ne 1) {
        $preview = $oldLf.Substring(0, [Math]::Min(70, $oldLf.Length))
        throw "Anchor found $count time(s) in $RelPath (expected exactly 1). Anchor starts: $preview"
    }

    $text = $text.Replace($oldLf, $newLf)
    if ($crlf) { $text = $text.Replace("`n", "`r`n") }
    [System.IO.File]::WriteAllText($path, $text, $utf8NoBom)
    Write-Host "  edited  $RelPath"
}

function Append-File {
    param([string]$RelPath, [string]$Appendix)
    $path = Join-Path $ProjectRoot $RelPath
    $raw = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $crlf = $raw.Contains("`r`n")
    $addition = $Appendix.Replace("`r`n", "`n")
    if ($crlf) { $addition = $addition.Replace("`n", "`r`n") }
    [System.IO.File]::WriteAllText($path, $raw + $addition, $utf8NoBom)
    Write-Host "  appended $RelPath"
}

function Write-NewFile {
    param([string]$RelPath, [string]$Content)
    $path = Join-Path $ProjectRoot $RelPath
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($path, $Content, $utf8NoBom)
    Write-Host "  created $RelPath"
}

# ---------------------------------------------------------------- sanity
$vmPath = Join-Path $ProjectRoot "$base\ButlerViewModel.kt"
if (-not (Test-Path $vmPath)) {
    throw "AURA source not found under: $ProjectRoot`nRe-run with -ProjectRoot 'D:\path\to\AURA-Butler-Android-Studio-v0.4.0-Native-Stable'"
}

# ---------------------------------------------------------------- undo
if ($Undo) {
    Write-Step "Undoing the latest Phase 3 apply"
    $last = Get-ChildItem $backupRoot -Directory -Filter 'phase3-*' -ErrorAction SilentlyContinue |
        Sort-Object Name | Select-Object -Last 1
    if (-not $last) { throw "No Phase 3 backup found in $backupRoot" }
    foreach ($rel in $editedFiles) {
        $src = Join-Path $last.FullName $rel
        if (Test-Path $src) {
            Copy-Item $src (Join-Path $ProjectRoot $rel) -Force
            Write-Host "  restored $rel"
        }
    }
    $list = Join-Path $last.FullName 'NEW_FILES.txt'
    if (Test-Path $list) {
        Get-Content $list | ForEach-Object {
            $p = Join-Path $ProjectRoot $_
            if (Test-Path $p) { Remove-Item $p -Force; Write-Host "  removed $_" }
        }
    }
    Write-Host "Undo complete (backup: $($last.Name))." -ForegroundColor Green
    return
}

# ---------------------------------------------------------------- already applied?
$vmText = [System.IO.File]::ReadAllText($vmPath, [System.Text.Encoding]::UTF8)
if ($vmText.Contains('private val chatRepo = ChatThreadRepository(application)')) {
    Write-Host "Phase 3 is already applied. Nothing changed." -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------- backup
Write-Step "Backing up the files this phase touches"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $backupRoot "phase3-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
foreach ($rel in $editedFiles) {
    $dest = Join-Path $backupDir $rel
    New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
    Copy-Item (Join-Path $ProjectRoot $rel) $dest
}
Write-Host "  backup -> $backupDir"

# ---------------------------------------------------------------- 1. data model
Write-Step "1/4 Chat data model (threads, projects, search hits)"
$stateOld = '    val speechSequence: Long = 0,'
$stateNew = @(
    '    val speechSequence: Long = 0,',
    '    val chatThreads: List<ChatThread> = emptyList(),',
    '    val chatProjects: List<ChatProject> = emptyList(),',
    '    val activeThreadId: String = "",'
) -join $nl
Edit-File "$base\model\Models.kt" $stateOld $stateNew

$modelsAppendText = @'

/** One saved conversation. Its messages live in encrypted chat storage under the thread id. */
data class ChatThread(
    val id: String,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val pinned: Boolean = false,
    val projectId: String? = null,
    val lastMessageId: String = ""
)

/** A group of chats that share instructions for AURA. */
data class ChatProject(
    val id: String,
    val name: String,
    val instructions: String = ""
)

data class ChatSearchHit(
    val threadId: String,
    val title: String,
    val snippet: String
)

data class ChatIndex(
    val threads: List<ChatThread>,
    val projects: List<ChatProject>,
    val activeThreadId: String
)
'@
Append-File "$base\model\Models.kt" $modelsAppendText

# ---------------------------------------------------------------- 2. storage and pure helpers
Write-Step "2/4 Encrypted chat storage and helpers"
Write-NewFile "$base\chat\ChatText.kt" @'
package `in`.diganta.aurabutler.chat

import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.Sender

/** Pure helpers for chat titles and search snippets. No Android APIs, so they are easy to unit test. */
object ChatText {
    const val DEFAULT_TITLE = "New chat"
    private const val MAX_TITLE_CHARS = 48
    private const val SNIPPET_CHARS = 90

    private val whitespace = Regex("\\s+")

    /** Title from the first user message, or null when there is no usable text yet. */
    fun deriveTitle(messages: List<ChatMessage>): String? {
        val first = messages.firstOrNull { it.sender == Sender.USER }?.text ?: return null
        val clean = first.replace(whitespace, " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX_TITLE_CHARS) {
            clean
        } else {
            clean.take(MAX_TITLE_CHARS - 3).trimEnd() + "..."
        }
    }

    fun matches(text: String, query: String): Boolean =
        text.contains(query.trim(), ignoreCase = true)

    /** A short excerpt around the first match, or the start of the text when nothing matches. */
    fun snippet(text: String, query: String): String {
        val clean = text.replace(whitespace, " ").trim()
        val needle = query.trim()
        val at = clean.indexOf(needle, ignoreCase = true)
        if (at < 0) return clean.take(SNIPPET_CHARS)
        val start = (at - 30).coerceAtLeast(0)
        val end = (at + needle.length + 50).coerceAtMost(clean.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < clean.length) "..." else ""
        return prefix + clean.substring(start, end) + suffix
    }
}
'@
Write-NewFile "$base\chat\ChatThreadRepository.kt" @'
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
'@

# ---------------------------------------------------------------- 3. view model
Write-Step "3/4 View model: thread actions, active chat sync, project instructions"
$vmImportOld = 'import `in`.diganta.aurabutler.model.ChatMessage'
$vmImportNew = @(
    'import `in`.diganta.aurabutler.chat.ChatThreadRepository',
    'import `in`.diganta.aurabutler.model.ChatMessage',
    'import `in`.diganta.aurabutler.model.ChatSearchHit'
) -join $nl
Edit-File "$base\ButlerViewModel.kt" $vmImportOld $vmImportNew

$repoOld = '    private val _uiState = MutableStateFlow(initialState)'
$repoNew = '    private val chatRepo = ChatThreadRepository(application)' + $nl + $repoOld
Edit-File "$base\ButlerViewModel.kt" $repoOld $repoNew

$initOld = '    init {'
$initNew = '    init {' + $nl + '        bootstrapChats()'
Edit-File "$base\ButlerViewModel.kt" $initOld $initNew

$memOld = '                    memories = before.memories,'
$memNew = '                    memories = before.memories + chatRepo.projectMemory(before.activeThreadId),'
Edit-File "$base\ButlerViewModel.kt" $memOld $memNew

$vmAnchor = '    fun clearConversation() {'
$vmBlockText = @'
    // ---- Chats: threads, pinning, projects and search ----

    fun startNewChat(projectId: String? = null) {
        if (_uiState.value.isBusy) {
            showMessage("Wait for the current reply to finish before starting a new chat.")
            return
        }
        val thread = chatRepo.createThread(projectId)
        activateChat(thread.id, persistCurrent = true)
    }

    fun switchChat(threadId: String) {
        val current = _uiState.value
        if (threadId == current.activeThreadId) return
        if (current.isBusy) {
            showMessage("Wait for the current reply to finish before switching chats.")
            return
        }
        activateChat(threadId, persistCurrent = true)
    }

    fun deleteChat(threadId: String) {
        val current = _uiState.value
        if (current.isBusy) {
            showMessage("Wait for the current reply to finish before deleting a chat.")
            return
        }
        val wasActive = threadId == current.activeThreadId
        chatRepo.deleteThread(threadId)
        if (!wasActive) {
            refreshChatList()
            return
        }
        val next = chatRepo.index().threads.maxByOrNull { it.updatedAtEpochMs }
        if (next != null) {
            activateChat(next.id, persistCurrent = false)
        } else {
            startNewChat()
        }
    }

    fun renameChat(threadId: String, title: String) {
        chatRepo.renameThread(threadId, title)
        refreshChatList()
    }

    fun togglePinChat(threadId: String) {
        chatRepo.togglePin(threadId)
        refreshChatList()
    }

    fun moveChatToProject(threadId: String, projectId: String?) {
        chatRepo.moveThread(threadId, projectId)
        refreshChatList()
    }

    fun createChatProject(name: String, instructions: String) {
        chatRepo.createProject(name, instructions)
        refreshChatList()
    }

    fun deleteChatProject(projectId: String) {
        chatRepo.deleteProject(projectId)
        refreshChatList()
    }

    /** Saves the active chat's messages into its thread and refreshes the list. Runs off the main thread. */
    fun syncActiveChat() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _uiState.value
            chatRepo.saveMessages(current.activeThreadId, current.messages)
            refreshChatList()
        }
    }

    suspend fun searchChats(query: String): List<ChatSearchHit> =
        withContext(Dispatchers.IO) { chatRepo.search(query) }

    private fun refreshChatList() {
        val index = chatRepo.index()
        _uiState.update { it.copy(chatThreads = index.threads, chatProjects = index.projects) }
    }

    private fun activateChat(threadId: String, persistCurrent: Boolean) {
        val current = _uiState.value
        if (persistCurrent) {
            chatRepo.saveMessages(current.activeThreadId, current.messages)
        }
        val target = chatRepo.loadMessages(threadId).ifEmpty { listOf(freshGreeting()) }
        store.saveMessages(target)
        store.savePendingActions(emptyList())
        chatRepo.setActive(threadId)
        val index = chatRepo.index()
        _uiState.update {
            it.copy(
                messages = target,
                pendingActions = emptyList(),
                activeThreadId = threadId,
                chatThreads = index.threads,
                chatProjects = index.projects
            )
        }
    }

    private fun bootstrapChats() {
        val index = chatRepo.bootstrap(store.loadMessages(), freshGreeting())
        _uiState.update {
            it.copy(
                chatThreads = index.threads,
                chatProjects = index.projects,
                activeThreadId = index.activeThreadId
            )
        }
    }

    private fun freshGreeting(): ChatMessage = ChatMessage(
        sender = Sender.AURA,
        text = "A fresh conversation is ready. What may I take care of?"
    )
'@
Edit-File "$base\ButlerViewModel.kt" $vmAnchor ($vmBlockText.TrimEnd("`r","`n") + $nl + $nl + $vmAnchor)

# ---------------------------------------------------------------- 4. chat list UI
Write-Step "4/4 Chat list: top-bar button, sheet, and icon import"
Write-NewFile "$base\ui\ChatHub.kt" @'
package `in`.diganta.aurabutler.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import `in`.diganta.aurabutler.ButlerViewModel
import `in`.diganta.aurabutler.model.ChatProject
import `in`.diganta.aurabutler.model.ChatSearchHit
import `in`.diganta.aurabutler.model.ChatThread

/** Chat list: new chat, search, pinned chats, projects, and per-chat actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHubSheet(viewModel: ButlerViewModel, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ChatSearchHit>>(emptyList()) }
    var renaming by remember { mutableStateOf<ChatThread?>(null) }
    var deleting by remember { mutableStateOf<ChatThread?>(null) }
    var creatingProject by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.syncActiveChat() }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = emptyList()
        } else {
            delay(250)
            hits = viewModel.searchChats(query)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    viewModel.startNewChat()
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New chat")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().height(520.dp)) {
                if (query.isNotBlank()) {
                    item { ChatSectionLabel("Search results") }
                    if (hits.isEmpty()) {
                        item { Text("No matching chats.", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(hits, key = { it.threadId + "|" + it.snippet }) { hit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.switchChat(hit.threadId)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                hit.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                hit.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    val threads = state.chatThreads
                    val projects = state.chatProjects
                    val projectIds = projects.map { it.id }.toSet()

                    val pinned = threads.filter { it.pinned }.sortedByDescending { it.updatedAtEpochMs }
                    if (pinned.isNotEmpty()) {
                        item { ChatSectionLabel("Pinned") }
                        items(pinned, key = { "pin-" + it.id }) { thread ->
                            ChatRow(
                                thread = thread,
                                active = thread.id == state.activeThreadId,
                                projects = projects,
                                onOpen = {
                                    viewModel.switchChat(thread.id)
                                    onDismiss()
                                },
                                onPin = { viewModel.togglePinChat(thread.id) },
                                onRename = { renaming = thread },
                                onMove = { projectId -> viewModel.moveChatToProject(thread.id, projectId) },
                                onDelete = { deleting = thread }
                            )
                        }
                    }

                    projects.forEach { project ->
                        val projectThreads = threads
                            .filter { !it.pinned && it.projectId == project.id }
                            .sortedByDescending { it.updatedAtEpochMs }
                        item(key = "project-" + project.id) {
                            ChatProjectHeader(
                                project = project,
                                onNewChat = {
                                    viewModel.startNewChat(project.id)
                                    onDismiss()
                                },
                                onDelete = { viewModel.deleteChatProject(project.id) }
                            )
                        }
                        if (projectThreads.isEmpty()) {
                            item(key = "empty-" + project.id) {
                                Text(
                                    "No chats in this project yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                                )
                            }
                        }
                        items(projectThreads, key = { "proj-" + it.id }) { thread ->
                            ChatRow(
                                thread = thread,
                                active = thread.id == state.activeThreadId,
                                projects = projects,
                                onOpen = {
                                    viewModel.switchChat(thread.id)
                                    onDismiss()
                                },
                                onPin = { viewModel.togglePinChat(thread.id) },
                                onRename = { renaming = thread },
                                onMove = { projectId -> viewModel.moveChatToProject(thread.id, projectId) },
                                onDelete = { deleting = thread }
                            )
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = { creatingProject = true },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("New project")
                        }
                    }

                    val recent = threads.filter { thread ->
                        val projectId = thread.projectId
                        !thread.pinned && (projectId == null || projectId !in projectIds)
                    }.sortedByDescending { it.updatedAtEpochMs }
                    item { ChatSectionLabel("Recent") }
                    if (recent.isEmpty()) {
                        item { Text("No other chats yet.", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(recent, key = { "recent-" + it.id }) { thread ->
                        ChatRow(
                            thread = thread,
                            active = thread.id == state.activeThreadId,
                            projects = projects,
                            onOpen = {
                                viewModel.switchChat(thread.id)
                                onDismiss()
                            },
                            onPin = { viewModel.togglePinChat(thread.id) },
                            onRename = { renaming = thread },
                            onMove = { projectId -> viewModel.moveChatToProject(thread.id, projectId) },
                            onDelete = { deleting = thread }
                        )
                    }
                }
            }
        }

        renaming?.let { thread ->
            var draft by remember(thread.id) { mutableStateOf(thread.title) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename chat") },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameChat(thread.id, draft)
                        renaming = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
            )
        }

        deleting?.let { thread ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete this chat?") },
                text = { Text("\"${thread.title}\" and its messages will be removed from this phone.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteChat(thread.id)
                        deleting = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
            )
        }

        if (creatingProject) {
            var projectName by remember { mutableStateOf("") }
            var projectInstructions by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { creatingProject = false },
                title = { Text("New project") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = projectName,
                            onValueChange = { projectName = it },
                            singleLine = true,
                            label = { Text("Project name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = projectInstructions,
                            onValueChange = { projectInstructions = it },
                            minLines = 3,
                            label = { Text("Instructions for AURA in this project") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = projectName.isNotBlank(),
                        onClick = {
                            viewModel.createChatProject(projectName, projectInstructions)
                            creatingProject = false
                        }
                    ) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { creatingProject = false }) { Text("Cancel") } }
            )
        }
    }
}

@Composable
private fun ChatSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ChatProjectHeader(project: ChatProject, onNewChat: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(
                project.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onNewChat) { Text("New chat") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete project")
            }
        }
        if (project.instructions.isNotBlank()) {
            Text(
                project.instructions,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChatRow(
    thread: ChatThread,
    active: Boolean,
    projects: List<ChatProject>,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onMove: (String?) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = thread.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    thread.updatedAtEpochMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString(),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Chat options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (thread.pinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onPin()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Move to project") },
                    onClick = {
                        menuOpen = false
                        moveOpen = true
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
            DropdownMenu(expanded = moveOpen, onDismissRequest = { moveOpen = false }) {
                DropdownMenuItem(
                    text = { Text("No project") },
                    onClick = {
                        moveOpen = false
                        onMove(null)
                    }
                )
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            moveOpen = false
                            onMove(project.id)
                        }
                    )
                }
            }
        }
    }
}
'@

$iconOld = 'import androidx.compose.material.icons.rounded.ClearAll'
$iconNew = $iconOld + $nl + 'import androidx.compose.material.icons.rounded.Forum'
Edit-File "$base\ui\AuraApp.kt" $iconOld $iconNew

$scaffoldOld = '    Scaffold('
$scaffoldNew = @(
    '    var showChatHub by remember { mutableStateOf(false) }',
    '    ChatHubSheet(viewModel = viewModel, visible = showChatHub, onDismiss = { showChatHub = false })',
    '    Scaffold('
) -join $nl
Edit-File "$base\ui\AuraApp.kt" $scaffoldOld $scaffoldNew

$clearOld = 'IconButton(onClick = { clearDialogVisible = true }) {'
$clearNew = @(
    'IconButton(onClick = { showChatHub = true }) {',
    '                            Icon(Icons.Rounded.Forum, contentDescription = "Chats")',
    '                        }',
    '                        IconButton(onClick = { clearDialogVisible = true }) {'
) -join $nl
Edit-File "$base\ui\AuraApp.kt" $clearOld $clearNew

Write-NewFile "$testBase\chat\ChatTextTest.kt" @'
package `in`.diganta.aurabutler.chat

import `in`.diganta.aurabutler.model.ChatMessage
import `in`.diganta.aurabutler.model.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {
    @Test
    fun titleCollapsesWhitespaceFromFirstUserMessage() {
        val messages = listOf(ChatMessage(sender = Sender.USER, text = "  hello   there "))
        assertEquals("hello there", ChatText.deriveTitle(messages))
    }

    @Test
    fun longTitleIsTruncatedWithEllipsis() {
        val title = ChatText.deriveTitle(listOf(ChatMessage(sender = Sender.USER, text = "a".repeat(80))))
        assertEquals(48, title?.length)
        assertTrue(title?.endsWith("...") == true)
    }

    @Test
    fun noTitleWithoutUserText() {
        val messages = listOf(ChatMessage(sender = Sender.AURA, text = "Hello"))
        assertNull(ChatText.deriveTitle(messages))
    }

    @Test
    fun matchingIgnoresCase() {
        assertTrue(ChatText.matches("Hello World", "world"))
    }

    @Test
    fun snippetAroundMatchKeepsTheMatch() {
        val snippet = ChatText.snippet("Please remind me about the CEMK lab report tomorrow morning", "lab report")
        assertTrue(snippet.contains("lab report"))
    }
}
'@

Set-Content -Path (Join-Path $backupDir 'NEW_FILES.txt') -Value $newFiles -Encoding ASCII

Write-Host ""
Write-Host "Phase 3 applied." -ForegroundColor Green
Write-Host "Next: Android Studio > Build > Rebuild Project, then install."
Write-Host "Try: tap the chat icon in the top bar, then New chat, pin a chat, create a project with instructions, and search."
Write-Host "Existing conversation: it becomes the first chat in the list, unchanged."
