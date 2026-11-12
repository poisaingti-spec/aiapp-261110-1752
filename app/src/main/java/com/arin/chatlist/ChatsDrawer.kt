package com.arin.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.data.ChatSummary
import com.arin.design.ArinMark
import java.util.Calendar

/** The side panel: search, new chat, chats grouped by day (long-press to rename or delete), Settings. */
@Composable
fun ChatsDrawer(
    vm: ChatViewModel,
    onPick: () -> Unit,
    onSettings: () -> Unit,
    onProjects: () -> Unit,
    onBuild: () -> Unit = {},
    onProject: (com.arin.data.Project) -> Unit,
) {
    var renaming by remember { mutableStateOf<ChatSummary?>(null) }
    var moving by remember { mutableStateOf<ChatSummary?>(null) }
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArinMark(size = 26.dp)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("Arin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "  v" + com.arin.ArinApp.versionLabel(androidx.compose.ui.platform.LocalContext.current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            TextField(
                value = vm.query,
                onValueChange = vm::search,
                placeholder = { Text("Search chats") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DrawerRow(icon = { Icon(Icons.Filled.Create, contentDescription = null) }, label = "New chat") {
                vm.newChat(); onPick()
            }
            val groups = remember(vm.chats.toList()) { group(vm.chats.toList()) }
            LazyColumn(Modifier.weight(1f).padding(top = 4.dp)) {
                item(key = "projects_header") {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Projects",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onProjects) { Text(if (vm.projects.isEmpty()) "New" else "All") }
                    }
                }
                items(vm.projects.take(5), key = { "p_" + it.id }) { p ->
                    DrawerRow(icon = { Text("▸", color = MaterialTheme.colorScheme.secondary) }, label = p.name) { onProject(p) }
                }
                if (groups.isEmpty()) {
                    item {
                        Text(
                            if (vm.query.isBlank()) "Your chats will appear here." else "No chats match.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                groups.forEach { (label, items) ->
                    item(key = "h_$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(items, key = { it.id }) { c ->
                        ChatRow(
                            c,
                            selected = c.id == vm.chatId,
                            onOpen = { vm.open(c.id); onPick() },
                            onRename = { renaming = c },
                            onMove = { moving = c },
                            onDelete = { deleting = c },
                        )
                    }
                }
            }
            DrawerRow(icon = { Icon(Icons.Filled.Build, contentDescription = null) }, label = "Build an app (APK)", onClick = onBuild)
            DrawerRow(icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = "Settings", onClick = onSettings)
        }
    }

    renaming?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(c.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    moving?.let { c ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move to project") },
            text = {
                Column {
                    if (vm.projects.isEmpty()) Text("No projects yet. Create one from Projects in the side panel.")
                    vm.projects.forEach { p ->
                        TextButton(onClick = { vm.moveToProject(c.id, p); moving = null }) {
                            Text((if (c.projectId == p.id) "✓ " else "") + p.name)
                        }
                    }
                    if (c.projectId != null) {
                        TextButton(onClick = { vm.moveToProject(c.id, null); moving = null }) { Text("Remove from project") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = null }) { Text("Close") } },
        )
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete chat?") },
            text = { Text("\"${c.title}\" will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { vm.delete(c.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DrawerRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(Color.Transparent, RoundedCornerShape(50))
            .combinedClickableCompat(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChatRow(c: ChatSummary, selected: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(50),
            )
            .combinedClickableCompat(onClick = onOpen, onLongClick = { menu = true })
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            c.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Move to project") }, onClick = { menu = false; onMove() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Today / Yesterday / Previous 7 days / Older. */
private fun group(chats: List<ChatSummary>): List<Pair<String, List<ChatSummary>>> {
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val day = 24L * 60 * 60 * 1000
    val buckets = linkedMapOf<String, MutableList<ChatSummary>>()
    chats.sortedByDescending { it.updated }.forEach { c ->
        val label = when {
            c.updated >= startOfToday -> "Today"
            c.updated >= startOfToday - day -> "Yesterday"
            c.updated >= startOfToday - 7 * day -> "Previous 7 days"
            else -> "Older"
        }
        buckets.getOrPut(label) { mutableListOf() } += c
    }
    return buckets.map { it.key to it.value.toList() }
}
