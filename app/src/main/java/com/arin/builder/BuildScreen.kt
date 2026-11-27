package com.arin.builder

import com.arin.chat.ChatViewModel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember

private val resumeWords = Regex("^\\s*(retry|resume|continue|try again|go on|carry on|build now|rebuild)\\b", RegexOption.IGNORE_CASE)

/** Starts a build that continues the open project from its last checkpoint. */
private fun resumeBuild(chat: ChatViewModel, vm: BuildViewModel, text: String = "Retry") {
    val project = vm.project ?: return
    vm.start(
        BuildRequest(
            description = text,
            appName = project.appName,
            provider = chat.provider,
            model = chat.model,
            apiKey = chat.key(chat.provider),
            customBaseUrl = if (chat.provider == com.arin.llm.Provider.Custom) chat.customBaseUrl else "",
            customFormat = chat.customFormat.name,
            testApp = chat.testApps,
            effort = chat.effort,
            githubToken = chat.githubToken(),
            allowOtherSites = true,
            project = project,
            resume = true,
        ),
    )
}

/** Runs the last request again from the beginning (round 0, nothing carried over from the stopped run). */
private fun restartBuild(chat: ChatViewModel, vm: BuildViewModel) {
    val project = vm.project ?: return
    val last = BuildRunner.timeline.filterIsInstance<TimelineItem.User>().map { it.text.trim() }
        .lastOrNull { it.isNotBlank() && !resumeWords.containsMatchIn(it) }
        ?: project.requirements.ifBlank { project.description }
    vm.start(
        BuildRequest(
            description = last,
            appName = project.appName,
            provider = chat.provider,
            model = chat.model,
            apiKey = chat.key(chat.provider),
            customBaseUrl = if (chat.provider == com.arin.llm.Provider.Custom) chat.customBaseUrl else "",
            customFormat = chat.customFormat.name,
            testApp = chat.testApps,
            effort = chat.effort,
            githubToken = chat.githubToken(),
            allowOtherSites = true,
            project = project.copy(round = 0, pendingPaths = emptyList()),
            resume = false,
        ),
    )
}

/** Build Mode, laid out like an IDE agent chat: your requests, live steps, files and results. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(chat: ChatViewModel, vm: BuildViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    val project = vm.project

    BackHandler(enabled = showFiles || openFile != null || showProjects) {
        if (openFile != null) openFile = null else if (showFiles) showFiles = false else showProjects = false
    }

    if (showProjects) {
        ProjectList(vm, onBack = { showProjects = false })
        return
    }

    openFile?.let { path ->
        CodeViewer(path, project?.files?.get(path).orEmpty(), onBack = { openFile = null })
        return
    }
    if (showFiles && project != null) {
        FileList(project.files.keys.toList(), onOpen = { openFile = it }, onBack = { showFiles = false })
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(vm.timeline.size) {
        if (vm.timeline.isNotEmpty()) listState.animateScrollToItem(vm.timeline.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.appName ?: "Build app")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            com.arin.selffix.ModelSwitcher(chat) // switch the API or model (only when you tap)
                            Text(
                                "  · ${chat.effort.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        com.arin.claudecode.UsageBar(chat) // real usage, at the top
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    com.arin.selffix.AiHelperButton(chat, hasInput = true) // ARIN-PROTECTED: ai-helper in every tab
                    var summary by remember { mutableStateOf(false) }
                    if (vm.timeline.isNotEmpty()) TextButton(onClick = { summary = true }) { Text("Summary") }
                    if (summary) BuildSummarySheet(onClose = { summary = false })
                    TextButton(onClick = { showProjects = true }) { Text("Projects") }
                    if (project != null) {
                        TextButton(onClick = { showFiles = true }) { Text("Files") }
                    }
                    if (vm.timeline.isNotEmpty()) {
                        TextButton(onClick = vm::newProject, enabled = !vm.running) { Text("New") }
                    }
                },
            )
        },
        bottomBar = { BuildComposer(chat, vm, onOpenSettings) },
    ) { padding ->
        if (vm.timeline.isEmpty()) {
            EmptyState(chat, Modifier.padding(padding))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(vm.timeline) { index, item ->
                    val isLast = index == vm.timeline.lastIndex
                    when (item) {
                        is TimelineItem.User -> UserBubble(item.text)
                        is TimelineItem.Event -> {
                            EventRow(item.event, active = isLast && vm.running)
                            val prog = BuildRunner.progress
                            if (isLast && vm.running && prog != null) {
                                Column(Modifier.padding(start = 30.dp)) { UploadBar(BuildRunner.progressText.ifBlank { "Working…" }, prog) }
                            }
                        }
                        is TimelineItem.Plan -> PlanCard(item, canWrite = isLast && item.done && !vm.running) { BuildPlanner.writeAsked++ }
                        is TimelineItem.Result -> ResultCard(
                            item,
                            onRetry = if (!item.success && isLast && !vm.running && vm.project != null) {
                                { resumeBuild(chat, vm) }
                            } else null,
                            // The code is written: where to build it.
                            onWhere = if (item.message.startsWith(BuildAgent.CODE_READY) && isLast && !vm.running) {
                                { w -> chat.buildWrittenAt(BuildRunner.projectId, w)?.let { chat.notice = it } }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(chat: ChatViewModel, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Describe an app and I'll build it", style = MaterialTheme.typography.titleLarge)
        Text(
            "I write the code, build the APK on your GitHub, fix any errors, and give you a download link. " +
                "Afterwards, ask for changes and I'll update the same app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Builds keep running if you close the app; you'll get a notification when it's ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (false) {
            Text(
                "You're using an offline model. It can manage simple one-screen apps; pick Claude Fable or Opus for bigger ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) { Text(text, modifier = Modifier.padding(12.dp)) }
    }
}

private fun iconFor(kind: BuildEvent.Kind): ImageVector = when (kind) {
    BuildEvent.Kind.THINKING -> Icons.Filled.Create
    BuildEvent.Kind.WEB -> Icons.Filled.Search
    BuildEvent.Kind.FILE -> Icons.Filled.Edit
    BuildEvent.Kind.LIBRARY -> Icons.Filled.Add
    BuildEvent.Kind.DOWNLOAD -> Icons.Filled.KeyboardArrowDown
    BuildEvent.Kind.BUILD -> Icons.Filled.Build
    BuildEvent.Kind.ERROR -> Icons.Filled.Warning
    BuildEvent.Kind.DONE -> Icons.Filled.Done
    BuildEvent.Kind.STEP -> Icons.Filled.CheckCircle
}

@Composable
private fun EventRow(e: BuildEvent, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    iconFor(e.kind),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (e.kind == BuildEvent.Kind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            e.text + if (active) "…" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = null,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(r: TimelineItem.Result, onRetry: (() -> Unit)? = null, onWhere: ((String) -> Unit)? = null) {
    val uri = LocalUriHandler.current
    val written = r.message.startsWith(BuildAgent.CODE_READY)
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (written) {
                val n = r.message.removePrefix(BuildAgent.CODE_READY).split(',').count { it.isNotBlank() }
                Text("✍️ The code is written ($n file${if (n == 1) "" else "s"}).", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (onWhere != null) com.arin.selffix.WhereToBuild(onPick = onWhere, showBuildTab = false)
            } else Text(
                r.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            r.apkUrl?.let { url -> ApkDownload(url) }
            // ARIN-PROTECTED: explain-after-coding — the code explained in plain English, then suggestions.
            if (r.explanation.isNotBlank()) Text(r.explanation.replace("**", "").trim(), style = MaterialTheme.typography.bodyMedium)
            // ARIN-PROTECTED: task-summary in every tab — what was done and what to do next, after every build (made by Arin, no AI).
            com.arin.selffix.TaskSummary.forBuild(r.success, r.message, r.apkUrl,
                if (written) r.message.removePrefix(BuildAgent.CODE_READY).split(',').count { it.isNotBlank() } else null).let { (did, next) ->
                Text("Summary — what I did: $did", style = MaterialTheme.typography.bodyMedium)
                Text("What to do next: $next", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry from where it stopped") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.apkUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Open in browser") } }
                r.apkUrl?.let { url ->
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(url)) }) { Text("Copy link") }
                }
                r.repoUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("GitHub") } }
            }
            if (r.success) {
                Text(
                    "Ask for changes below — the new version installs over this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuildComposer(chat: ChatViewModel, vm: BuildViewModel, onOpenSettings: () -> Unit) {
    val project = vm.project
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var allowOther by rememberSaveable { mutableStateOf(true) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unpacking by rememberSaveable { mutableStateOf(false) }

    val attached = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<com.arin.data.Attachment>() }
    // "Write the code" on the plan: start writing (stop after writing, then Arin asks where to build).
    androidx.compose.runtime.LaunchedEffect(BuildPlanner.writeAsked) {
        if (BuildPlanner.writeAsked == 0 || vm.running) return@LaunchedEffect
        if (chat.githubToken().isBlank()) { problem = "Sign in with GitHub in Settings first."; return@LaunchedEffect }
        if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        vm.start(
                                BuildRequest(
                                    description = BuildPlanner.writeDescription(),
                                    appName = name.trim(),
                                    provider = chat.provider,
                                    model = chat.model,
                                    apiKey = chat.key(chat.provider),
                                    customBaseUrl = if (chat.provider == com.arin.llm.Provider.Custom) chat.customBaseUrl else "",
                                    customFormat = chat.customFormat.name,
                                    testApp = chat.testApps,
                                    effort = chat.effort,
                                    githubToken = chat.githubToken(),
                                    allowOtherSites = allowOther,
                                    project = project,
                                    attachments = BuildPlanner.files.toList(),
                                    stopAfterWriting = true,
                                    // With the helper on: the model writes first, then the helper reviews and the model fixes.
                                    consultHelper = chat.helperOn("build"),
                                    // Everything asked for so far in this Build chat counts, not only this message.
                                    requirements = BuildPlanner.requirements(),
                                ),
                            )
    }
    var attaching by rememberSaveable { mutableStateOf(false) }
    var attachPct by remember { mutableStateOf(0f) }
    fun importAll(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        attaching = true
        scope.launch {
            uris.forEach { u ->
                try {
                    attachPct = 0f
                    attached += importForBuild(context, u) { v -> scope.launch { attachPct = v } }
                } catch (e: Exception) {
                    problem = e.message ?: "Couldn't attach that file."
                }
            }
            attaching = false
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attaching = true
        scope.launch {
            uris.forEach { u ->
                try {
                    attachPct = 0f
                    attached += importForBuild(context, u) { v -> scope.launch { attachPct = v } }
                } catch (e: Exception) {
                    problem = e.message ?: "Couldn't attach that file."
                }
            }
            attaching = false
        }
    }

    fun ready(): String? = when {
        chat.githubToken().isBlank() -> "Sign in with GitHub in Settings first."
        else -> null
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = ready()
        if (problem != null) return@rememberLauncherForActivityResult
        unpacking = true
        scope.launch {
            try {
                attachPct = 0f
                val a = importForBuild(context, uri) { v -> scope.launch { attachPct = v } }
                if (!a.name.endsWith(".zip", true)) {
                    problem = "Pick a .zip of an Android Studio project."
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.newProject()
                    vm.start(
                        BuildRequest(
                            description = "Compile ${a.name} to an APK",
                            appName = a.name.removeSuffix(".zip"),
                            provider = chat.provider,
                            model = chat.model,
                            apiKey = chat.key(chat.provider),
                            customBaseUrl = if (chat.provider == com.arin.llm.Provider.Custom) chat.customBaseUrl else "",
                            customFormat = chat.customFormat.name,
                            testApp = chat.testApps,
                            effort = chat.effort,
                            githubToken = chat.githubToken(),
                            allowOtherSites = allowOther,
                            zipPath = a.path,
                            zipName = a.name,
                        ),
                    )
                }
            } catch (e: Exception) {
                problem = e.message ?: "Couldn't read that file."
            } finally {
                unpacking = false
            }
        }
    }

    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            problem?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            if (vm.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Working…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                }
                return@Column
            }
            if (project == null) {
                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = !unpacking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unpacking) "Reading project…" else "Upload Android Studio project (.zip) → APK") }
                if (unpacking) UploadBar("Copying your project…", attachPct)
                Text(
                    "…or describe a new app:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (project != null && BuildRunner.timeline.any { it is TimelineItem.User }) {
                val lastResult = BuildRunner.timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
                Text(
                    when {
                        lastResult == null -> "The last build didn't finish."
                        lastResult.success -> "The last build succeeded."
                        else -> "The last build failed."
                    } + " Resume it, or restart the last request from the beginning:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            problem = ready()
                            if (problem == null) resumeBuild(chat, vm, "Resume")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Resume last build") }
                    OutlinedButton(
                        onClick = {
                            problem = ready()
                            if (problem == null) restartBuild(chat, vm)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Restart build") }
                }
            }
            if (attaching) UploadBar("Attaching…", attachPct)
            if (attached.isNotEmpty() && !attaching) {
                Text(
                    "✓ ${attached.size} file${if (attached.size == 1) "" else "s"} ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (attached.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attached.toList().forEach { a ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                TextButton(onClick = { attached.remove(a) }) { Text("✕") }
                            }
                        }
                    }
                }
            }
            com.arin.selffix.ActivityBar("build") // what Arin is doing (helper help marked 🟣)
            com.arin.selffix.HelperTalkBar(chat) // talking to the AI helper in this message box
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Attach anything: camera, photos & videos, any file of any size, an installed app's APK, an APK file.
                com.arin.selffix.AttachButton(onUris = { importAll(it) }, onProblem = { problem = it })
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(if (vm.running) "Steer the build while it works…" else if (project == null) "Describe the app…" else "Ask for a change…") },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    // A build is running: what you type steers it right away.
                    if (vm.running) {
                        if (text.isNotBlank()) { BuildRunner.current.steer(text); text = "" }
                        return@Button
                    }
                    val zip = attached.firstOrNull { it.isZip }
                    if (project == null && zip != null) {
                        // An attached project zip: compile it.
                        problem = ready()
                        if (problem == null) {
                            if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            vm.newProject()
                            vm.start(
                                BuildRequest(
                                    description = text.trim().ifBlank { "Compile ${zip.name} to an APK" },
                                    appName = zip.name.removeSuffix(".zip"),
                                    provider = chat.provider,
                                    model = chat.model,
                                    apiKey = chat.key(chat.provider),
                                    customBaseUrl = if (chat.provider == com.arin.llm.Provider.Custom) chat.customBaseUrl else "",
                                    customFormat = chat.customFormat.name,
                                    testApp = chat.testApps,
                                    effort = chat.effort,
                                    githubToken = chat.githubToken(),
                                    allowOtherSites = allowOther,
                                    zipPath = zip.path,
                                    zipName = zip.name,
                                ),
                            )
                            attached.clear()
                            text = ""
                        }
                        return@Button
                    }
                    if (project != null && attached.isEmpty() && resumeWords.containsMatchIn(text)) {
                        problem = ready()
                        if (problem == null) {
                            resumeBuild(chat, vm, text.trim())
                            text = ""
                        }
                        return@Button
                    }
                    problem = when {
                        text.isBlank() && attached.isEmpty() -> "Describe what you want."
                        project == null && name.isBlank() -> "Give the app a name."
                        chat.githubToken().isBlank() -> "Sign in with GitHub in Settings first."
                        chat.provider.needsKey && chat.key(chat.provider).isBlank() -> "Add your ${chat.provider.label} key in Settings."
                        chat.model.isBlank() -> "Pick a model on the chat screen first."
                        else -> null
                    }
                    if (problem == null) {
                        // ARIN-PROTECTED: plan-improve-build-where — first a plan (improve it by replying), then
                        // "Write the code", then Arin asks where to build. Never straight to building.
                        BuildPlanner.plan(context, chat, project?.appName ?: name.trim(), text.trim(), attached.toList())
                        text = ""
                        attached.clear()
                    }
                }) { Text(if (vm.running) "Steer" else if (BuildPlanner.busy) "…" else if (vm.timeline.lastOrNull() is TimelineItem.Plan) "Improve" else "Plan") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allow downloads from any site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowOther, onCheckedChange = { allowOther = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileList(paths: List<String>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { com.arin.selffix.AiHelperButtonHere() }, // ARIN-PROTECTED: ai-helper in every tab
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(paths.sorted()) { _, path ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        path.removePrefix("app/src/main/").substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeViewer(path: String, content: String, onBack: () -> Unit) {
    val lines = content.lines()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { com.arin.selffix.AiHelperButtonHere() }, // ARIN-PROTECTED: ai-helper in every tab
            )
        },
    ) { padding ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(padding).fillMaxSize()) {
            SelectionContainer {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEachIndexed { i, line ->
                        Row {
                            Text(
                                "${i + 1}".padStart(4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(vm: BuildViewModel, onBack: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    val projects = remember(version) { vm.projects() }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    com.arin.selffix.AiHelperButtonHere() // ARIN-PROTECTED: ai-helper in every tab
                    TextButton(onClick = {
                        vm.newProject()
                        onBack()
                    }, enabled = !vm.running) { Text("New project") }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Text("No projects yet. Describe an app or upload a .zip to start one.", modifier = Modifier.padding(padding).padding(24.dp))
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(projects) { _, p ->
                val current = p.id == BuildRunner.projectId
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Any project can be opened, even while others build (several builds run at once).
                        .clickable { if (vm.openProject(p.id) || current) onBack() }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title + if (current) "  (open)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val status = when {
                            BuildRunner.session(p.id)?.running == true -> "Building now…"
                            p.lastResult?.success == true -> "APK ready"
                            p.lastResult != null -> "Last build failed"
                            else -> "In progress"
                        }
                        Text(
                            "${fmt.format(java.util.Date(p.updated))} · $status" + (if (p.project?.imported == true) " · uploaded" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!(current && vm.running)) {
                        TextButton(onClick = {
                            vm.deleteProject(p.id)
                            version++
                        }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Downloads the APK in the app with a progress bar, checks it, then opens Android's installer. */
@Composable
private fun ApkDownload(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var size by remember(url) { mutableStateOf<Long?>(null) }
    var progress by remember(url) { mutableStateOf<Float?>(null) }
    var file by remember(url) { mutableStateOf<java.io.File?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(url) { size = ApkInstaller.check(url) }

    fun install(f: java.io.File) {
        // Android asks once to allow installing apps from Arin.
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + context.packageName),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            error = "Allow \"Install unknown apps\" for Arin, come back, and tap Install."
            return
        }
        runCatching { ApkInstaller.install(context, f) }.onFailure { error = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val f = file
        val p = progress
        when {
            f != null -> {
                Text("✓ Downloaded ${f.name} — also saved to Downloads/Arin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { install(f) }, modifier = Modifier.fillMaxWidth()) { Text("Install app") }
            }
            p != null -> UploadBar("Downloading APK…", p)
            else -> Button(
                onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        try {
                            val downloaded = ApkInstaller.download(context, url) { v ->
                                scope.launch { progress = v }
                            }
                            file = downloaded
                            install(downloaded)
                        } catch (e: Exception) {
                            error = e.message ?: "Download failed."
                        } finally {
                            progress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val s = size
                Text("Download & install APK" + if (s != null) " (" + (if (s < 1048576) "${s / 1024} KB" else "%.1f MB".format(s / 1048576.0)) + ")" else "")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** A progress bar: label, bar and percentage (or a moving bar when the size is unknown). */
@Composable
fun UploadBar(label: String, progress: Float, detail: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (progress >= 0f) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        if (progress >= 0f) {
            androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}


/** Plan → improve → write the code → where to build (the Arin Update tab's way, here too). */
object BuildPlanner {
    var busy by androidx.compose.runtime.mutableStateOf(false)
    var writeAsked by androidx.compose.runtime.mutableStateOf(0)
    val files = mutableListOf<com.arin.data.Attachment>()
    private const val SYSTEM = "You are planning an Android app (Kotlin, Jetpack Compose) with the person, BEFORE any code is written. " +
        "Write a short, clear plan in plain English: what the app does, its screens, how each feature works, what it saves, " +
        "permissions and libraries. If something important is unclear, end with up to 3 short questions. Do NOT write code. " +
        "The person will improve the plan by replying, then tap \"Write the code\"."

    fun lastPlan(): String = BuildRunner.timeline.filterIsInstance<TimelineItem.Plan>().lastOrNull { it.done }?.text.orEmpty()

    fun requirements(): String = (BuildRunner.timeline.filterIsInstance<TimelineItem.User>().map { it.text.trim() }.filter { it.isNotBlank() } +
        listOf("THE AGREED PLAN (follow it):\n" + lastPlan())).distinct().joinToString("\n\n")

    fun writeDescription(): String = "Write the code exactly as in the agreed plan below (and everything the person asked).\n\n" + requirements()

    fun plan(context: android.content.Context, chat: ChatViewModel, appName: String, text: String, attached: List<com.arin.data.Attachment>) {
        if (busy) return
        files += attached
        BuildRunner.timeline += TimelineItem.User(text.ifBlank { "(files attached)" } + if (attached.isNotEmpty()) "\n📎 " + attached.joinToString(", ") { it.name } else "")
        val idx = BuildRunner.timeline.size
        BuildRunner.timeline += TimelineItem.Plan("", done = false)
        busy = true
        val prompt = buildString {
            append("App: ").append(appName.ifBlank { "(no name yet)" }).append("\n\nWhat the person asked so far:\n")
            BuildRunner.timeline.filterIsInstance<TimelineItem.User>().forEach { append("- ").append(it.text).append('\n') }
            lastPlan().takeIf { it.isNotBlank() }?.let { append("\nYOUR PREVIOUS PLAN (improve it with the newest message):\n").append(it) }
        }
        com.arin.ArinApp.scope.launch {
            val sb = StringBuilder()
            try {
                streamModel(chat.provider, chat.key(chat.provider), chat.model, listOf(ChatMessage(com.arin.builder.Role.USER, prompt, files.toList())), SYSTEM)
                    .collect { if (!it.startsWith(THOUGHT)) { sb.append(it); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { if (idx < BuildRunner.timeline.size) BuildRunner.timeline[idx] = TimelineItem.Plan(sb.toString(), false) } } }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                sb.append("\n\n(Couldn't make the plan: ${e.message?.take(200)})")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (idx < BuildRunner.timeline.size) BuildRunner.timeline[idx] = TimelineItem.Plan(sb.toString().trim(), true)
                busy = false
                BuildRunner.save(context)
            }
        }
    }
}

@Composable
private fun PlanCard(p: TimelineItem.Plan, canWrite: Boolean, onWrite: () -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("📝 Plan", style = MaterialTheme.typography.titleSmall)
            Text(p.text.ifBlank { "Planning…" }, style = MaterialTheme.typography.bodyMedium)
            if (canWrite) {
                Text("Reply below to improve it, or:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onWrite) { Text("✍️ Write the code") }
            }
        }
    }
}
