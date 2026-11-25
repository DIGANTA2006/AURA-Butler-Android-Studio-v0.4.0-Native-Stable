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