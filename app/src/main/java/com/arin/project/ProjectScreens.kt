package com.arin.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.chat.FileChip
import com.arin.data.Project

/** All projects, with a button to make a new one. */
@Composable
fun ProjectsScreen(vm: ChatViewModel, onBack: () -> Unit, onOpen: (Project) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Header("Projects", onBack)
        Text(
            "A project keeps related chats together, with instructions and files that every chat in it can use.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.projects.isEmpty()) {
                item { Text("No projects yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(vm.projects, key = { it.id }) { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(p) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        val count = vm.chatsIn(p).size
                        Text(
                            "$count chat${if (count == 1) "" else "s"} · ${p.files.size} file${if (p.files.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (p.instructions.isNotBlank()) {
                            Text(
                                p.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New project") },
            modifier = Modifier.align(Alignment.End).padding(16.dp),
        )
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New project") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { creating = false; onOpen(vm.createProject(name)) }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}

/** One project: name, instructions, files, its chats, and starting a new chat in it. */
@Composable
fun ProjectScreen(vm: ChatViewModel, projectId: String, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val project = vm.projects.firstOrNull { it.id == projectId }
    if (project == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header("Project", onBack)
            Text("This project no longer exists.", modifier = Modifier.padding(20.dp))
        }
        return
    }
    var instructions by remember(project.id) { mutableStateOf(project.instructions) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addProjectFiles(project, it) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Header(project.name, onBack) {
            TextButton(onClick = { renaming = true }) { Text("Rename") }
            TextButton(onClick = { deleting = true }) { Text("Delete") }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Button(onClick = { vm.newChat(project); onOpenChat() }, modifier = Modifier.fillMaxWidth()) {
                    Text("New chat in this project")
                }
            }
            item {
                Card("Instructions") {
                    Text(
                        "How Arin should behave in this project: its role, tone, what to focus on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        placeholder = { Text("For example: You are my study partner for Class 12 physics. Explain step by step.") },
                        minLines = 3,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (instructions != project.instructions) {
                        TextButton(onClick = { vm.updateProject(project.copy(instructions = instructions)) }) { Text("Save instructions") }
                    }
                }
            }
            item {
                Card("Files") {
                    Text(
                        "Every chat in this project can read these. Photos and PDFs need a Claude, Gemini or ChatGPT key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    project.files.forEach { f -> FileChip(f, onRemove = { vm.removeProjectFile(project, f) }) }
                    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Add files") }
                }
            }
            item {
                Text("Chats", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            val chats = vm.chatsIn(project)
            if (chats.isEmpty()) {
                item { Text("No chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(chats, key = { it.id }) { c ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clickable { vm.open(c.id); onOpenChat() },
                ) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.updateProject(project.copy(name = name.trim())); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete project?") },
            text = { Text("Its instructions and files are removed. Its chats stay, in your main chats list.") },
            confirmButton = { TextButton(onClick = { deleting = false; vm.deleteProject(project); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
            Spacer(Modifier.height(0.dp))
        }
    }
}
