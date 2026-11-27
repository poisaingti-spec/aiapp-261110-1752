package com.arin.selffix

// ARIN-PROTECTED-RULE: arin-update-tab
// The Arin Update tab updates ONLY Arin itself, never another app. Every update keeps the app id (com.arin.app)
// and the signing key and raises the version, so it installs over the Arin installed now. Protected: only the
// owner can change this (see PROTECTED_RULES.md).

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.arin.builder.BuildEvent
import com.arin.builder.BuildRunner
import com.arin.builder.TimelineItem
import com.arin.chat.ChatViewModel
import com.arin.data.Message
import kotlinx.coroutines.launch

/** The Arin Update tab: a chat like every other, where you discuss an update, then choose where to build it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArinUpdateScreen(vm: ChatViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { ArinUpdateChat.load(context) }
    var input by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var pendingSpec by remember { mutableStateOf<String?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var showSteps by rememberSaveable { mutableStateOf(false) }
    var installNote by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }

    val id = vm.arinUpdateSession
    val session = id?.let { BuildRunner.sessions[it] ?: remember(it) { BuildRunner.sessionFor(context, it) } }
    val running = session?.running == true
    val events = session?.timeline?.toList().orEmpty()
    val lastUser = events.indexOfLast { it is TimelineItem.User }
    val lastChange = events.filterIsInstance<TimelineItem.User>().map { it.text }.lastOrNull { it != "Resume" && it != "Retry" }.orEmpty()
    val chat = ArinUpdateChat.messages
    // A build held back a change to a protected rule, or needs a new repository: the owner decides.
    val blocked = events.drop(lastUser.coerceAtLeast(0)).filterIsInstance<TimelineItem.Event>()
        .lastOrNull { it.event.text.startsWith(SelfUpdateRules.NEEDS_APPROVAL) }?.event?.text
    var blockedAnswered by remember(id, lastUser) { mutableStateOf(false) }
    // Buttons go under the newest message that has an action, only when nothing is running.
    val actionIndex = chat.indexOfLast { it.action.isNotBlank() }.takeIf { it == chat.lastIndex && !running && !ArinUpdateChat.busy }

    /** Writes the code for what was discussed; it then asks where to build. A protected rule needs approval first. */
    fun write(spec: String, unlock: Boolean? = null) {
        val approvedInChat = ArinUpdateChat.approved
        val wantsUnlock = chat.any { it.fromUser && SelfUpdateRules.asksToUnlock(it.text) }
        if (unlock == null && wantsUnlock && !approvedInChat) { pendingSpec = spec; return }
        problem = vm.startArinUpdate(spec, unlock ?: approvedInChat, stopAfterWriting = true, attachments = ArinUpdateChat.allFiles)
        if (problem == null) {
            installNote = null
            ArinUpdateChat.consumeApproval()
            ArinUpdateChat.note(context, "🛠 Writing the code for this update…")
        }
    }

    /** Builds the written code where you chose (resumes the saved work: nothing is rewritten). */
    fun buildAt(where: String, autoInstall: Boolean = false) {
        ArinUpdateChat.autoInstall = autoInstall
        problem = id?.let { vm.buildWrittenAt(it, where) } ?: "There's no written update to build yet."
        if (problem == null) ArinUpdateChat.note(context, "🛠 Building " + when (where) {
            "phone" -> "on this phone"
            "codespace" -> "on GitHub with a Codespace (${vm.selfRepo()})"
            "chat" -> "on GitHub — you can also follow it in the chat"
            "auto" -> "where it works best"
            else -> "on GitHub in ${vm.selfRepo()}"
        } + if (autoInstall) " — I'll install it by myself when it's ready." else "…")
    }

    fun resume() {
        problem = vm.startArinUpdate(lastChange, retry = true)
        if (problem == null) ArinUpdateChat.note(context, "▶️ Resuming from where it stopped…")
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() && ArinUpdateChat.pending.isEmpty()) return
        // Something is running: what you type steers it.
        if (running && t.isNotEmpty()) {
            vm.steerArinUpdate(t)
            ArinUpdateChat.note(context, "↪ Sent to the build: $t")
            input = ""
            return
        }
        if (ArinUpdateChat.busy && t.isNotEmpty()) {
            ArinUpdateChat.steer(context, t)
            input = ""
            return
        }
        input = ""
        // With the helper on: the tab's model reads the whole message first, then takes the helper's help.
        ArinUpdateChat.send(context, vm, t, onBuild = { spec -> scope.launch { write(spec) } }, consultHelper = vm.helperOn("arin-update"))
    }

    val listState = rememberLazyListState()
    LaunchedEffect(chat.size, ArinUpdateChat.live.length / 200, events.size, running) {
        val n = listState.layoutInfo.totalItemsCount
        if (n > 0) listState.animateScrollToItem(n - 1)
    }

    pendingSpec?.let { spec ->
        AlertDialog(
            onDismissRequest = { pendingSpec = null },
            title = { Text("Change a protected rule?") },
            text = {
                Text("This discussion asks to change a protected rule. Allow this one update to change it? If not, the update " +
                    "keeps the rules exactly as they are.")
            },
            confirmButton = { TextButton(onClick = { pendingSpec = null; write(spec, true) }) { Text("Approve") } },
            dismissButton = { TextButton(onClick = { pendingSpec = null; write(spec, false) }) { Text("Keep the rules") } },
        )
    }
    if (showRules) {
        AlertDialog(
            onDismissRequest = { showRules = false },
            title = { Text("About Arin Update") },
            text = {
                Text("This tab only updates Arin itself — never another app. Each update keeps Arin's app id " +
                    "(${SelfUpdateRules.APP_ID}) and signing key and raises the version, so it installs over the Arin you have " +
                    "now. Talk it through first; when the code is written I ask where to build. Errors are researched on the " +
                    "internet first and I keep trying other methods until it's done (Stop ends it). Your model and Custom API " +
                    "are never changed, and this conversation stays until Clear all chats.")
            },
            confirmButton = { TextButton(onClick = { showRules = false }) { Text("OK") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Arin Update")
                        ModelSwitcher(vm) // switch the API or model for this tab (only when you tap)
                        com.arin.claudecode.UsageBar(vm) // real usage, at the top
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { showRules = true }) { Icon(Icons.Filled.Info, contentDescription = "About this tab") }
                    AiHelperButton(vm, hasInput = true) // ARIN-PROTECTED: ai-helper in every tab
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().navigationBarsPadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (chat.isEmpty()) item {
                    Text(
                        "Tell me what to change in Arin. We'll talk it through first — I'll read Arin's code and give you my " +
                            "view. When the code is written, I'll ask you where to build it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(chat, key = { _, m -> m.id }) { i, m ->
                    val msg = Message(id = m.id, fromUser = m.fromUser, text = m.text)
                    if (m.fromUser) com.arin.chat.UserMessage(msg, onEdit = null)
                    else Column {
                        com.arin.chat.ArinMessage(msg, showThinking = false, isLast = false, onRetry = {})
                        if (i == actionIndex) Actions(
                            action = m.action,
                            installing = installing,
                            onWhere = { w -> buildAt(w) },
                            onBuildInstall = { buildAt("github", true) },
                            onResume = { resume() },
                            onInstall = { url ->
                                installing = true
                                scope.launch {
                                    installNote = if (url.startsWith("file:")) ArinUpdateChat.installFile(context, url.removePrefix("file:"))
                                        else ArinUpdateChat.install(context, url) { installNote = it }
                                    installing = false
                                }
                            },
                        )
                    }
                }
                // The model asks before a protected rule is changed: Approve or Keep, right in the conversation.
                ArinUpdateChat.approvalAsk?.let { what ->
                    item {
                        AskInline("🔒 Can I change a protected rule for this?\n\n$what",
                            onYes = { ArinUpdateChat.answerApproval(context, true) },
                            onNo = { ArinUpdateChat.answerApproval(context, false) })
                    }
                }
                if (blocked != null && !blockedAnswered && (!running || blocked.contains(SelfRepo.NEW_REPO))) item {
                    val what = blocked.removePrefix(SelfUpdateRules.NEEDS_APPROVAL).substringBefore(" It was kept")
                    AskInline(
                        if (blocked.contains(SelfRepo.NEW_REPO)) "🔒 May I create a new GitHub repository?\n\n$what" else "🔒 This update needs a protected rule changed.\n\n$what",
                        onYes = {
                            blockedAnswered = true
                            if (blocked.contains(SelfRepo.NEW_REPO)) {
                                SelfRepo.approveNewRepo(context)
                                ArinUpdateChat.note(context, "✅ You approved a new GitHub repository. Carrying on…")
                                if (!running) resume()
                            } else {
                                ArinUpdateChat.note(context, "✅ You approved changing the protected rule. Carrying on…")
                                problem = vm.startArinUpdate(lastChange + "\n\nThe owner APPROVED this change to a protected rule: " + what, unlock = true)
                            }
                        },
                        onNo = { blockedAnswered = true; ArinUpdateChat.note(context, "🔒 Kept the rule as it is.") },
                    )
                }
                // The model's reply as it's written.
                if (ArinUpdateChat.busy) item {
                    com.arin.chat.LiveReply(ArinUpdateChat.live, "", false, ArinUpdateChat.status ?: "Thinking")
                }
                // What the agent is doing: one plain line in the conversation (tap Steps for the details).
                if (running) item {
                    Column {
                        com.arin.chat.LiveReply("", "", false, PlainSummary.of(session).lineSequence().first().removePrefix("Now: "))
                        // Waiting for you: show how to fix it here, not hidden in Steps.
                        val runEvents = events.drop(lastUser.coerceAtLeast(0)).filterIsInstance<TimelineItem.Event>()
                        val waiting = runEvents.lastOrNull { it.event.kind == BuildEvent.Kind.ERROR }?.event?.text?.startsWith("Waiting for you") == true
                        val howTo = runEvents.lastOrNull { it.event.text.startsWith("🔎") }?.event?.text
                            ?.removePrefix("🔎 How to fix (researched on the internet):")?.trim()
                        if (waiting && !howTo.isNullOrBlank()) {
                            com.arin.chat.ArinMessage(Message(id = "howto-$lastUser", fromUser = false, text = "🔎 **How to fix it:**\n\n$howTo"),
                                showThinking = false, isLast = false, onRetry = {})
                        }
                        Row(Modifier.padding(start = 28.dp)) {
                            TextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Hide steps" else "Steps") }
                            TextButton(onClick = { vm.stopArinUpdate() }) { Text("Stop") }
                        }
                        if (showSteps) {
                            events.drop(lastUser.coerceAtLeast(0)).filterIsInstance<TimelineItem.Event>().forEach { e ->
                                val line = e.event.text.lineSequence().firstOrNull { it.isNotBlank() && !it.trim().startsWith("at ") }.orEmpty()
                                Text(
                                    (if (e.event.kind == BuildEvent.Kind.ERROR) "⚠️ " else "• ") + line.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
                installNote?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
                problem?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
            }

            // The message box, like the chat tab: + to attach, it grows as you type, then scrolls inside.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                ActivityBar("arin-update") // what Arin is doing (helper help marked 🟣)
                HelperTalkBar(vm) // talking to the AI helper in this message box
                // Waiting files in one compact row (swipe sideways), so the message box always stays on screen.
                PendingFiles(
                    ArinUpdateChat.pending.map { it.name to it.size },
                    onRemove = { i -> ArinUpdateChat.pending.getOrNull(i)?.let { ArinUpdateChat.pending.remove(it) } },
                    onRemoveAll = { ArinUpdateChat.pending.clear() },
                )
                ArinUpdateChat.reading?.let { Text("Reading $it…", style = MaterialTheme.typography.labelMedium) }
                if (chat.any { it.fromUser } && !running && !ArinUpdateChat.busy && actionIndex == null) {
                    TextButton(onClick = { write(ArinUpdateChat.transcript()) }) { Text("✍️ Write the code for this update") }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(28.dp)) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
                        AttachButton(onUris = { ArinUpdateChat.attach(context, it) }, onProblem = { problem = it })
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text(if (running || ArinUpdateChat.busy) "Steer it while it works…" else "Message ${vm.provider.label} about the update") },
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
                        if (ArinUpdateChat.busy && input.isBlank()) {
                            FilledIconButton(onClick = { ArinUpdateChat.stop() }, shape = CircleShape) {
                                Box(Modifier.size(12.dp)) { Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(12.dp)) {} }
                            }
                        } else {
                            FilledIconButton(onClick = { send() }, enabled = input.isNotBlank() || ArinUpdateChat.pending.isNotEmpty(), shape = CircleShape) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The buttons under the newest message: where to build, install, or resume. */
@Composable
private fun Actions(
    action: String,
    installing: Boolean,
    onWhere: (String) -> Unit,
    onBuildInstall: () -> Unit,
    onResume: () -> Unit,
    onInstall: (String) -> Unit,
) {
    Column(Modifier.padding(start = 32.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            action == "where" -> WhereToBuild(onPick = onWhere, showBuildTab = false) {
                OutlinedButton(onClick = onBuildInstall) { Text("On GitHub, then install by itself") }
            }
            action.startsWith("install:") -> Button(onClick = { onInstall(action.removePrefix("install:")) }, enabled = !installing) {
                if (installing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Install update over current Arin")
            }
            action == "resume" -> Button(onClick = onResume) { Text("Resume from where it stopped") }
        }
    }
}

/** A yes/no question in the conversation (protected rule, new repository). Nothing changes without Approve. */
@Composable
private fun AskInline(text: String, onYes: () -> Unit, onNo: () -> Unit) {
    Column {
        com.arin.chat.ArinMessage(Message(fromUser = false, text = text), showThinking = false, isLast = false, onRetry = {})
        Row(Modifier.padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onYes) { Text("Approve") }
            OutlinedButton(onClick = onNo) { Text("Keep as it is") }
        }
    }
}
