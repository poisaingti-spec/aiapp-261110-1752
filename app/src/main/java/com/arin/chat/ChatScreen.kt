package com.arin.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chatlist.ChatsDrawer
import com.arin.design.ArinMark
import kotlinx.coroutines.launch

private val starters = listOf(
    "Explain something simply",
    "Help me write a message",
    "Plan my day",
    "Debug some code",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenProject: (com.arin.data.Project) -> Unit,
    onOpenBuild: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    startVoice: Boolean = false,
    onVoiceStarted: () -> Unit = {},
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.attach(it) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { vm.attach(it) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Camera: the photo is written to a file Arin shares with the camera app, then attached.
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) vm.attach(listOf(uri))
        cameraUri = null
    }
    fun openCamera() {
        runCatching {
            val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(dir, "photo-${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
            cameraUri = uri
            takePhoto.launch(uri)
        }.onFailure { vm.notice = "Couldn't open the camera." }
    }
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceOpen = true else vm.notice = "Voice mode needs microphone permission."
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun openVoice() {
        val has = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (has) voiceOpen = true else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    fun send() {
        // Ask once, at the first message, so a finished reply can notify while Arin is in the background.
        if (android.os.Build.VERSION.SDK_INT >= 33 && !vm.settings.askedNotifications) {
            vm.settings.askedNotifications = true
            notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        vm.send(input)
        input = ""
    }
    LaunchedEffect(startVoice) {
        if (startVoice) { onVoiceStarted(); openVoice() }
    }
    if (voiceOpen) com.arin.voice.VoiceMode(vm, onClose = { voiceOpen = false })

    LaunchedEffect(vm.sharedText) {
        val shared = vm.sharedText ?: return@LaunchedEffect
        input = if (input.isBlank()) shared else input + "\n" + shared
        vm.sharedText = null
    }
    vm.commandToApprove?.let { cmd ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text(if (vm.approvingTool) "Allow this tool call?" else "Run this command in Termux?") },
            text = {
                Column {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Text(
                            cmd,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            modifier = Modifier.padding(10.dp)
                                .heightIn(max = 240.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        )
                    }
                    Text(
                        if (vm.approvingTool) "A connected MCP server will run it on your phone. Only allow what you're comfortable with."
                        else "It runs on your phone with Termux's access. Only allow commands you're comfortable with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    androidx.compose.material3.TextButton(onClick = { vm.answerCommand(allow = true, always = true) }) {
                        Text("Always allow (don't ask again)")
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.answerCommand(true) }) { Text("Run") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.answerCommand(false) }) { Text("Don't run") } },
        )
    }

    vm.crashReport?.let { report ->
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissCrash() },
            title = { Text("Arin closed unexpectedly last time") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text(
                        "Copy this report and send it to get the crash fixed. It's also saved in Logs (side panel).\n\n" + report.take(3_000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(report))
                    vm.notice = "Crash report copied"
                    vm.dismissCrash()
                }) { Text("Copy report") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { vm.dismissCrash() }) { Text("Close") } },
        )
    }

    LaunchedEffect(vm.notice) {
        val n = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(n)
        vm.notice = null
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatsDrawer(
                vm,
                onPick = { scope.launch { drawer.close() } },
                onSettings = { scope.launch { drawer.close() }; onOpenSettings() },
                onProjects = { scope.launch { drawer.close() }; onOpenProjects() },
                onProject = { p -> scope.launch { drawer.close() }; onOpenProject(p) },
                onBuild = { scope.launch { drawer.close() }; onOpenBuild() },
                onLogs = { scope.launch { drawer.close() }; onOpenLogs() },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopBar(
                    vm,
                    onMenu = { scope.launch { drawer.open() } },
                    onNew = { vm.newChat(vm.chatProject.takeIf { vm.messages.isEmpty() }); input = "" },
                    onProject = onOpenProject,
                )
            },
            bottomBar = {
                Composer(
                    vm,
                    text = input,
                    onText = { input = it },
                    onSend = { send() },
                    onVoice = { openVoice() },
                    onPhotos = {
                        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFiles = { pickFiles.launch(arrayOf("*/*")) },
                    onCamera = { openCamera() },
                    onCancelEdit = { vm.cancelEdit(); input = "" },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (vm.messages.isEmpty() && !vm.streaming) {
                    Welcome(project = vm.chatProject?.name, onPick = { input = it })
                } else {
                    Conversation(vm, onEdit = { i -> vm.startEdit(i)?.let { input = it } })
                }
            }
        }
    }
}

@Composable
private fun TopBar(vm: ChatViewModel, onMenu: () -> Unit, onNew: () -> Unit, onProject: (com.arin.data.Project) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Chats") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Surface(
                onClick = { menu = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        vm.chatProject?.let { p ->
                            Text(
                                "▸ " + p.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onProject(p) },
                            )
                        }
                        Text(
                            if (vm.temporary) "Temporary chat (not saved)" else vm.title.takeIf { vm.messages.isNotEmpty() } ?: "Arin",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            vm.modelLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose model", modifier = Modifier.size(20.dp))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Saved APIs: switch service with one tap.
                if (vm.savedApis.isNotEmpty()) {
                    Text(
                        "Your APIs",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    vm.savedApis.forEach { api ->
                        DropdownMenuItem(
                            text = { Text(api.name + if (vm.currentSavedApi() == api) "  ✓" else "") },
                            onClick = { vm.useSavedApi(api); menu = false },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                }
                Text(
                    vm.currentSavedApi()?.name ?: vm.provider.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                vm.models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label + if (m.id == vm.model) "  ✓" else "") },
                        onClick = { vm.chooseModel(m); menu = false },
                    )
                }
            }
        }
        if (vm.messages.isNotEmpty()) ChatMenu(vm)
        IconButton(onClick = onNew) { Icon(Icons.Filled.Create, contentDescription = "New chat") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Welcome(project: String?, onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArinMark(size = 56.dp)
        Spacer(Modifier.height(18.dp))
        Text(if (project != null) project else "Hi, I'm Arin", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (project != null) "New chat in this project. Its instructions and files are included."
            else "Ask me anything, attach any file, or tap the mic to talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            starters.forEach { s -> AssistChip(onClick = { onPick("$s: ") }, label = { Text(s) }) }
        }
    }
}

@Composable
private fun Conversation(vm: ChatViewModel, onEdit: (Int) -> Unit) {
    val list = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val count = vm.messages.size + if (vm.streaming) 1 else 0
    var summaryOpen by remember { mutableStateOf(false) }
    if (summaryOpen) com.arin.builder.BuildSummarySheet(onClose = { summaryOpen = false })
    // Follow the newest text (to the very bottom) until the person scrolls up; the ↓ button turns it back on.
    var follow by remember { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.canScrollForward }.collect { more -> if (!more) follow = true }
    }
    LaunchedEffect(dragged) {
        if (dragged) {
            androidx.compose.runtime.snapshotFlow { list.canScrollForward }.collect { more -> if (more) follow = false }
        }
    }
    // Sending a message always jumps to it.
    val lastFromUser = vm.messages.lastOrNull()?.fromUser == true
    LaunchedEffect(vm.messages.size) { if (lastFromUser) follow = true }
    val growth = listOf(
        count, vm.liveText.length, vm.liveThinking.length, vm.liveCommands.size, vm.status,
        vm.messages.lastOrNull()?.text?.length,
    )
    LaunchedEffect(growth, follow) {
        if (follow && count > 0 && !dragged) {
            list.scrollToItem(count - 1)
            list.scrollBy(1_000_000f) // to the end of the last item, not its top
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(vm.messages, key = { _, m -> m.id }) { i, m ->
                if (m.fromUser) UserMessage(m, onEdit = if (vm.streaming) null else ({ onEdit(i) }))
                else ArinMessage(
                    m, vm.showThinking, isLast = i == vm.messages.lastIndex && !vm.streaming, onRetry = vm::retry,
                    // Build progress in chat: a Summary of every step, like Build mode keeps.
                    onSummary = if (m.text.startsWith("🛠️")) ({ summaryOpen = true }) else null,
                    // Only the newest reply can be built from, so an old plan never starts by accident.
                    onConfirmBuild = if (i == vm.messages.lastIndex && !vm.streaming) ({ vm.confirmBuild(m) }) else null,
                )
            }
            if (vm.streaming) {
                item(key = "live") { LiveReply(vm.liveText, vm.liveThinking, vm.showThinking, vm.status, vm.liveCommands.toList()) }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = list.canScrollForward && !follow,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) {
            androidx.compose.material3.SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch {
                        list.animateScrollToItem((count - 1).coerceAtLeast(0))
                        list.animateScrollBy(1_000_000f)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Go to the bottom")
            }
        }
    }
}

@Composable
private fun Composer(
    vm: ChatViewModel,
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onCamera: () -> Unit = {},
    onCancelEdit: () -> Unit,
) {
    var attachMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
    ) {
        if (vm.editing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Editing your message. Sending replaces it and the replies after it.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = onCancelEdit) { Text("Cancel") }
            }
        }
        vm.pending.forEach { f -> FileChip(f, onRemove = { vm.removePending(f) }) }
        vm.steering.forEachIndexed { i, note ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "↪ Arin will read this at its next step: $note",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    androidx.compose.material3.TextButton(onClick = { vm.removeSteer(i) }) { Text("✕") }
                }
            }
        }
        vm.readingFile?.let { name ->
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "  Reading $name…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 0.dp,
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenu = true }) { Icon(Icons.Filled.Add, contentDescription = "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(text = { Text("Camera") }, onClick = { attachMenu = false; onCamera() })
                        DropdownMenuItem(text = { Text("Photos") }, onClick = { attachMenu = false; onPhotos() })
                        DropdownMenuItem(text = { Text("Files (any type)") }, onClick = { attachMenu = false; onFiles() })
                        androidx.compose.material3.HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text((if (vm.webSearch) "✓ " else "") + "Web search") },
                            onClick = { vm.toggleWebSearch(!vm.webSearch); attachMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text((if (vm.deepResearch) "✓ " else "") + "Deep research") },
                            onClick = { vm.toggleDeepResearch(!vm.deepResearch); attachMenu = false },
                        )
                    }
                }
                TextField(
                    value = text,
                    onValueChange = onText,
                    placeholder = { Text(if (vm.streaming) "Steer Arin while it works…" else "Message Arin") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                if (vm.streaming && text.isNotBlank()) {
                    // Sending now steers the running reply.
                    FilledIconButton(onClick = onSend, shape = CircleShape) {
                        Icon(Icons.Filled.Send, contentDescription = "Steer", modifier = Modifier.size(20.dp))
                    }
                } else if (vm.streaming) {
                    FilledIconButton(
                        onClick = vm::stop,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface),
                    ) {
                        Box(Modifier.size(12.dp).padding(0.dp).then(Modifier), contentAlignment = Alignment.Center) {
                            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(12.dp)) {}
                        }
                    }
                } else {
                    val canSend = text.isNotBlank() || vm.pending.isNotEmpty()
                    if (canSend) {
                        FilledIconButton(onClick = onSend, shape = CircleShape) {
                            Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                        }
                    } else {
                        FilledIconButton(
                            onClick = onVoice,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        ) {
                            Icon(
                                androidx.compose.ui.res.painterResource(com.arin.R.drawable.ic_mic),
                                contentDescription = "Voice mode",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** The chat's ⋮ menu: export, copy everything, and how long the chat is. */
@Composable
private fun ChatMenu(vm: ChatViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("New temporary chat") }, onClick = {
                open = false
                vm.newChat(temporary = true)
            })
            DropdownMenuItem(text = { Text("Export as Markdown") }, onClick = {
                open = false
                runCatching { com.arin.data.ChatExport.shareMarkdown(context, vm.title, vm.messages.toList()) }
                    .onFailure { vm.notice = "Couldn't export this chat." }
            })
            DropdownMenuItem(text = { Text("Copy whole chat") }, onClick = {
                open = false
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.arin.data.ChatExport.toPlainText(vm.title, vm.messages.toList())))
                vm.notice = "Chat copied"
            })
            DropdownMenuItem(text = { Text("Chat info") }, onClick = { open = false; info = true })
        }
    }
    if (info) {
        val s = remember(vm.messages.size) { com.arin.data.ChatExport.stats(vm.messages.toList()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { info = false },
            title = { Text(vm.title) },
            text = {
                Text(
                    "${s.messages} messages · ${s.words} words · about ${s.readingMinutes} min to read" +
                        (vm.chatProject?.let { "\nProject: ${it.name}" } ?: ""),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { info = false }) { Text("OK") } },
        )
    }
}
