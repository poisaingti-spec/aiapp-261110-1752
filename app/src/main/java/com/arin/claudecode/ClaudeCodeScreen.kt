package com.arin.claudecode

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.design.LocalArinExtras
import com.arin.design.MarkdownText

/**
 * Claude Code as a chat, like the chat tab. The brain is the person's Claude Pro login or their own API
 * (both set up in Settings → Claude Code); the tool is Anthropic's Claude Code, running in Arin's Linux.
 */
@Composable
fun ClaudeCodeScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshClaudeCode() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        // ---- top bar, like the chat tab: title with the model underneath ----
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text("Claude Code", style = MaterialTheme.typography.titleLarge)
                if (vm.ccInstalled && vm.ccSignedIn) ModelPicker(vm)
                if (vm.ccInstalled && vm.ccSignedIn) com.arin.claudecode.UsageBar(vm, claudeLogin = vm.ccBackend == "pro")
            }
            if (vm.ccInstalled && vm.ccSignedIn) {
                IconButton(onClick = { vm.newCcChat() }, enabled = !vm.ccRunning) { Icon(Icons.Filled.Edit, contentDescription = "New chat") }
            }
            com.arin.selffix.AiHelperButton(vm, hasInput = true) // ARIN-PROTECTED: ai-helper in every tab
            Box {
                var menu by rememberSaveable { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text((if (vm.ccReaOn) "✓ " else "") + "Reverse engineering (REA)") },
                        onClick = { vm.toggleCcRea(!vm.ccReaOn); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text((if (vm.showThinking) "✓ " else "") + "Show thinking") },
                        onClick = { vm.toggleThinking(!vm.showThinking); menu = false },
                    )
                    DropdownMenuItem(text = { Text("Claude Code settings") }, onClick = { menu = false; onOpenSettings() })
                }
            }
        }
        if (vm.ccReaOn && vm.ccSignedIn) {
            Text(
                "🔍 Reverse engineering on — Claude Code can use REA's tools (decompile, inspect APKs…)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (!vm.ccInstalled || !vm.ccSignedIn) {
            Setup {
                Card {
                    Text(if (!vm.ccInstalled) "Set up Claude Code" else "Sign in to Claude Code", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Install and sign in from Settings → Claude Code — with your Claude Pro (Google) account or your own API.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        } else {
            Conversation(vm, Modifier.weight(1f))
            Composer(vm)
        }
    }
}

@Composable
private fun ModelPicker(vm: ChatViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(enabled = !vm.ccRunning) { open = true }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                vm.ccModel.ifBlank { "Pick a model" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Change model", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            vm.ccModels.forEach { id ->
                DropdownMenuItem(
                    text = { Text((if (id == vm.ccModel) "✓ " else "") + id) },
                    onClick = { vm.selectCcModel(id); open = false },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Setup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun Conversation(vm: ChatViewModel, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(vm.ccMessages.size, vm.ccLiveText.length, vm.ccLiveCommands.size, vm.ccRunning) {
        val n = vm.ccMessages.size + if (vm.ccRunning) 1 else 0
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    // Summary of a reply's steps (commands, files, tools), like the chat tab.
    var stepsFor by rememberSaveable { mutableStateOf<String?>(null) }
    stepsFor?.let { id ->
        if (id == "live") com.arin.builder.StepsSummarySheet(
            steps = com.arin.chat.chatSteps(vm.ccLiveCommands.toList(), finished = !vm.ccRunning),
            running = vm.ccRunning, doing = vm.ccStatus ?: "Working", onClose = { stepsFor = null },
        ) else vm.ccMessages.firstOrNull { it.id == id }?.let { m ->
            com.arin.builder.StepsSummarySheet(
                steps = com.arin.chat.chatSteps(m.commands, finished = true, failed = m.failed),
                running = false, doing = "", onClose = { stepsFor = null },
            )
        }
    }
    if (vm.ccMessages.isEmpty() && !vm.ccRunning) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Ask Claude Code to build, fix or explain something.\nIt can run commands, search the web, push to GitHub, " +
                    "make movies, build APKs" + (if (vm.ccReaOn) " and reverse engineer apps with REA." else "."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(vm.ccMessages, key = { _, m -> m.id }) { i, m ->
            if (m.fromUser) com.arin.chat.UserMessage(m, onEdit = null)
            else com.arin.chat.ArinMessage(
                m, vm.showThinking,
                isLast = i == vm.ccMessages.lastIndex && !vm.ccRunning,
                onRetry = vm::retryClaudeCode,
                onResume = if (i == vm.ccMessages.lastIndex && !vm.ccRunning && (m.failed || m.text.startsWith("Stopped") || m.text.startsWith("🔎"))) vm::resumeClaudeCode else null,
                onSummary = if (m.commands.isNotEmpty()) ({ stepsFor = m.id }) else null,
            )
        }
        if (vm.ccRunning) {
            item(key = "live") {
                com.arin.chat.LiveReply(
                    vm.ccLiveText, vm.ccLiveThinking, vm.showThinking, vm.ccStatus, vm.ccLiveCommands.toList(),
                    onSummary = { stepsFor = "live" },
                )
            }
        }
    }
}

/** The same message box as the chat tab, with + to attach photos, camera shots or any file. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Composer(vm: ChatViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var attachMenu by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { vm.ccAttach(it) }
    val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(),
    ) { vm.ccAttach(it) }
    var cameraUri by androidx.compose.runtime.remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.ccAttach(listOf(uri))
        cameraUri = null
    }
    val takeVideo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CaptureVideo(),
    ) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.ccAttach(listOf(uri))
        cameraUri = null
    }
    val pickApk = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { it?.let { u -> vm.ccAttach(listOf(u)) } }
    var pickApp by androidx.compose.runtime.remember { mutableStateOf(false) }
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    if (pickApp) com.arin.chat.InstalledAppPicker(
        onPick = { app ->
            pickApp = false
            appScope.launch {
                runCatching { com.arin.data.InstalledApps.copyApks(context, app) }
                    .onSuccess { files -> vm.ccAttach(files.map { android.net.Uri.fromFile(it) }) }
                    .onFailure { vm.notice = "Couldn't copy ${app.label}'s APK: ${it.message}" }
            }
        },
        onClose = { pickApp = false },
    )
    fun openCamera() {
        runCatching {
            val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
            cameraUri = uri
            takePhoto.launch(uri)
        }.onFailure { vm.notice = "Couldn't open the camera." }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Files waiting to go with the next message.
        com.arin.selffix.ActivityBar("claude-code") // what Arin is doing (helper help marked 🟣)
        com.arin.selffix.HelperTalkBar(vm) // talking to the AI helper in this message box
        // Waiting files in one compact row (swipe sideways), so the message box always stays on screen.
        com.arin.selffix.PendingFiles(
            vm.ccPending.map { it.name to it.size },
            onRemove = { i -> vm.ccPending.getOrNull(i)?.let { vm.ccRemovePending(it) } },
            onRemoveAll = { vm.ccPending.toList().forEach { vm.ccRemovePending(it) } },
        )
        if (vm.ccAttaching) CircularProgressIndicator(Modifier.size(18.dp).padding(bottom = 6.dp), strokeWidth = 2.dp)
        Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(28.dp)) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.Add, contentDescription = "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Camera") }, onClick = { attachMenu = false; openCamera() })
                        DropdownMenuItem(text = { Text("Photos") }, onClick = {
                            attachMenu = false
                            pickPhotos.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                ),
                            )
                        })
                        DropdownMenuItem(text = { Text("Files (any type)") }, onClick = { attachMenu = false; pickFiles.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Camera (video)") }, onClick = {
                            attachMenu = false
                            runCatching {
                                val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files",
                                    java.io.File(dir, "video-${System.currentTimeMillis()}.mp4"))
                                cameraUri = uri
                                takeVideo.launch(uri)
                            }.onFailure { vm.notice = "Couldn't open the camera." }
                        })
                        DropdownMenuItem(text = { Text("Installed app (APK)") }, onClick = { attachMenu = false; pickApp = true })
                        DropdownMenuItem(text = { Text("APK file") }, onClick = {
                            attachMenu = false
                            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                        })
                    }
                }
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (vm.ccRunning) "Steer Claude Code while it works…" else "Message Claude Code") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (vm.ccRunning && (text.isNotBlank() || vm.ccPending.isNotEmpty())) {
                    // Sending now steers the running reply.
                    FilledIconButton(onClick = { vm.askClaudeCode(text); text = "" }, shape = CircleShape, enabled = !vm.ccAttaching) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Steer", modifier = Modifier.size(20.dp))
                    }
                } else if (vm.ccRunning) {
                    FilledIconButton(
                        onClick = { vm.stopClaudeCode() },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.onSurface),
                    ) {
                        Surface(color = colors.surface, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(12.dp)) {}
                    }
                } else {
                    FilledIconButton(
                        onClick = { vm.askClaudeCode(text); text = "" },
                        enabled = (text.isNotBlank() || vm.ccPending.isNotEmpty()) && !vm.ccAttaching,
                        shape = CircleShape,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
