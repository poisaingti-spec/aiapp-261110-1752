package com.arin.settings

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.data.ThemeMode
import com.arin.llm.Provider
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit, openClaudeCode: Boolean = false) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            com.arin.selffix.ModelSwitcher(vm)
            com.arin.selffix.AiHelperButton(vm) // ARIN-PROTECTED: ai-helper in every tab
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("My APIs — add as many as you want") {
                MyApis(vm)
            }

            Section("Replies come from") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Provider.entries.forEach { p ->
                        FilterChip(
                            selected = vm.provider == p,
                            onClick = { vm.chooseProvider(p) },
                            label = { Text(p.label) },
                        )
                    }
                }
                Text(
                    when (vm.provider) {
                        Provider.Free -> "Free models work with no key or sign-up."
                        Provider.Custom -> "Uses your own API at ${vm.customBaseUrl}. Set it up below."
                        else -> "Uses your ${vm.provider.label} API key. You pay the provider for what you use."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("API keys") {
                Text(
                    "Stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Provider.entries.filter { it.needsKey && it != Provider.Custom }.forEach { p -> KeyRow(vm, p) }
            }

            Section("Appearance") {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { t ->
                        FilterChip(selected = vm.theme == t, onClick = { vm.chooseTheme(t) }, label = { Text(t.label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show reasoning", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "See how the model worked through an answer, when it shares that.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = vm.showThinking, onCheckedChange = vm::toggleThinking)
                }
            }

            Section("Web search") {
                Toggle(
                    "Search the web",
                    "Arin looks things up when an answer needs current facts, and lists its sources.",
                    vm.webSearch,
                    vm::toggleWebSearch,
                )
            }

            FoldSection(
                "Custom instructions",
                if (vm.aboutYou.isNotBlank() || vm.howToAnswer.isNotBlank() || vm.replyStyle != "Normal") "On · used in every chat"
                else "Tell Arin about you and how you want answers",
            ) {
                CustomInstructionsSettings(vm)
            }

            Section("Credits") {
                Toggle(
                    "Save credits",
                    "Keeps the WHOLE chat in the model's memory, but bills the repeated part at a fraction by caching it, " +
                        "and sends a re-pasted file or page only once. Far fewer tokens on long builds, with no loss of context.",
                    vm.saveCredits,
                    vm::toggleSaveCredits,
                )
                Toggle(
                    "Think less",
                    "Asks the model to reason briefly (DeepSeek: thinking off). Thinking is billed as output, so everyday " +
                        "questions cost much less. Also applies to Claude Code. Turn off for hard problems.",
                    vm.thinkLess,
                    vm::toggleThinkLess,
                )
            }

            Section("Memory") {
                Toggle(
                    "Remember things about me",
                    "Arin saves lasting facts you share, such as your name or how you like answers, and uses them in new chats. Kept only on this phone.",
                    vm.memoryOn,
                    vm::toggleMemory,
                )
                if (vm.memories.isEmpty()) {
                    Text("Nothing remembered yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.memories.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("• " + m.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.forget(m) }) { Text("Forget") }
                    }
                }
                var adding by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = adding,
                        onValueChange = { adding = it },
                        placeholder = { Text("Add something to remember") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = adding.isNotBlank(), onClick = { vm.addMemory(adding); adding = "" }) { Text("Add") }
                }
                if (vm.memories.isNotEmpty()) {
                    TextButton(onClick = { vm.forgetAll() }) { Text("Forget everything") }
                }
            }

            Section("Notifications and voice") {
                Toggle(
                    "Notify when a reply is ready",
                    "If you leave Arin while it's writing, it keeps going and lets you know when it's done.",
                    vm.notifyOnReply,
                    vm::toggleNotify,
                )
                var rate by remember { mutableStateOf(vm.settings.voiceRate) }
                Text("Voice speed: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    onValueChangeFinished = { vm.settings.voiceRate = rate },
                    valueRange = 0.6f..1.8f,
                    steps = 5,
                )
                var natural by remember { mutableStateOf(vm.settings.voiceNatural) }
                Toggle(
                    "Natural voice",
                    "A warm, human-like voice over the internet instead of the robotic phone voice. Falls back to the phone's voice when you're offline.",
                    natural,
                ) { natural = it; vm.settings.voiceNatural = it }
                if (natural) {
                    var engine by remember { mutableStateOf(vm.settings.voiceEngine) }
                    Text("ChatGPT voice", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Both options use the real ChatGPT voices. Your chat still runs on whichever model/API you picked — this only changes the voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = engine == "free",
                            onClick = { engine = "free"; vm.settings.voiceEngine = "free" },
                            label = { Text("Free · no key") },
                        )
                        androidx.compose.material3.FilterChip(
                            selected = engine == "openai",
                            onClick = { engine = "openai"; vm.settings.voiceEngine = "openai" },
                            label = { Text("OpenAI key · more expressive") },
                        )
                    }
                    var vkey by remember { mutableStateOf(vm.settings.voiceKey) }
                    var vkeySaved by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        value = vkey,
                        onValueChange = { vkey = it; vkeySaved = false },
                        label = { Text("OpenAI key (for the OpenAI engine)") },
                        placeholder = { Text("sk-…") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Button(
                            onClick = { vm.settings.voiceKey = vkey.trim(); vkeySaved = true },
                            enabled = !vkeySaved,
                        ) { Text(if (vkeySaved) "Saved ✓" else "Save key") }
                        if (vkey.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vkey = ""; vm.settings.voiceKey = ""; vkeySaved = true }) { Text("Clear") }
                        }
                    }
                    Text(
                        "Used only when the OpenAI engine is selected. Only the voice uses it — your chat still runs on the API you picked. Costs a small amount per reply. Leave blank to use the free voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    var picked by remember { mutableStateOf(vm.settings.voiceName) }
                    Text("Voice", style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "nova" to "Nova", "shimmer" to "Shimmer", "alloy" to "Alloy",
                            "echo" to "Echo", "fable" to "Fable", "onyx" to "Onyx",
                        ).forEach { (id, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = picked == id,
                                onClick = { picked = id; vm.settings.voiceName = id },
                                label = { Text(label) },
                            )
                        }
                    }
                    TextButton(onClick = { vm.previewVoice() }) { Text("▶  Preview voice") }
                    var barge by remember { mutableStateOf(vm.settings.voiceBargeIn) }
                    Toggle(
                        "Interrupt by talking",
                        "Start speaking while Arin is talking and it stops to listen — no tapping. Works best with earphones or in a quiet place; turn it off if it cuts itself off.",
                        barge,
                    ) { barge = it; vm.settings.voiceBargeIn = it }
                }
            }

            // Extra features, folded away so the screen stays simple. Tap a row to open it.
            FoldSection(
                "Offline models (no internet)",
                vm.offlineStatus.ifBlank { "${vm.offlineDownloaded().size} on this phone · run uncensored models with no internet" },
                startOpen = vm.offlineBusy,
            ) { OfflineModelSettings(vm) }
            FoldSection("Claude Code", com.arin.claudecode.claudeCodeSummary(vm), startOpen = openClaudeCode || vm.ccBusy) {
                com.arin.claudecode.ClaudeCodeLogin(vm)
            }
            FoldSection("Custom API", "${vm.savedApis.size} saved" + (vm.currentSavedApi()?.let { " · using ${it.name}" } ?: "") + " · tap to add more services") {
                CustomApiSettings(vm)
            }
            FoldSection(
                "Build apps and update Arin",
                if (vm.githubToken().isNotBlank()) "GitHub: ${vm.githubUser.ifBlank { "signed in" }}" else "Sign in with GitHub to build APKs",
            ) { GithubSettings(vm) }
            FoldSection(
                "Linux",
                when {
                    vm.linuxBusy -> "Working…"
                    vm.linuxReady -> "Installed" + if (vm.devToolsReady) " · Java, Kotlin, Gradle" else ""
                    else -> "Run tools like Node, Python and REA inside Arin"
                },
                startOpen = vm.linuxBusy,
            ) { LinuxSettings(vm) }
            FoldSection("Terminal", if (vm.terminalOn) ("On" + (if (vm.terminalAsk) " · asks before each command" else "")) else "Off") {
                TerminalSettings(vm)
            }
            FoldSection(
                "Tools (MCP)",
                vm.mcpServers.takeIf { it.isNotEmpty() }?.joinToString { it.name } ?: "Connect REA or other MCP servers",
            ) { ToolsSettings(vm) }

            FoldSection("Credit saver", when (com.arin.selffix.CreditSaver.mode(androidx.compose.ui.platform.LocalContext.current)) {
                1 -> "On · your model + Claude"; 2 -> "On · your model + offline model + Claude"; else -> "Off · tap to save credit" }) {
                com.arin.selffix.CreditSaverSection(vm)
            }
            FoldSection("Storage", "One protected Arin folder · Free up space · Backup") {
                com.arin.settings.StorageSection(vm)
            }
            Section("Your data") {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all chats and projects") }
            }

            Text(
                "Arin version ${com.arin.ArinApp.versionLabel(androidx.compose.ui.platform.LocalContext.current)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all chats and projects?") },
            text = { Text("Every chat and project on this phone will be removed. Memory is kept; clear it above. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FoldSection(title: String, summary: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (!open) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(if (open) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Sign in with GitHub so Build mode can create repos and build APKs with GitHub Actions. */
@Composable
private fun GithubSettings(vm: ChatViewModel) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdvanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(vm.githubClientId()) }
    var token by remember { mutableStateOf("") }
    Text(
        "Build mode writes the app, puts its source in a new repository on your GitHub and compiles it into an APK " +
            "there (free GitHub Actions), then you install it from here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val code = vm.githubCode
    when {
        vm.githubToken().isNotBlank() -> {
            Text("✓ Signed in as ${vm.githubUser.ifBlank { "your GitHub account" }}.", color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = vm::signOutGithub) { Text("Sign out") }
        }
        code != null -> {
            Text("Enter this code on GitHub, then come back:")
            Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode)) }) { Text("Copy code") }
                OutlinedButton(onClick = { uri.openUri(code.verificationUri) }) { Text("Open GitHub") }
            }
            Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
        }
        else -> androidx.compose.material3.Button(onClick = { vm.signInGithub { link -> uri.openUri(link) } }) { Text("Sign in with GitHub") }
    }
    vm.githubLoginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    // Arin updating itself.
    var selfRepo by remember { mutableStateOf(vm.selfRepo()) }
    var selfBranch by remember { mutableStateOf(vm.selfBranch()) }
    var change by remember { mutableStateOf("") }
    var updateNote by remember { mutableStateOf<String?>(null) }
    Text("Update Arin itself", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Describe a change and Arin edits its own code on GitHub (downloading anything it needs), builds a new version " +
            "and offers to install it. You can also just ask for a new feature in chat.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = change,
        onValueChange = { change = it; updateNote = null },
        label = { Text("What should Arin change or add?") },
        modifier = Modifier.fillMaxWidth(),
    )
    androidx.compose.material3.Button(
        enabled = change.isNotBlank() && vm.githubToken().isNotBlank(),
        onClick = { updateNote = vm.startSelfUpdate(change) ?: "Started. Follow it in Build mode." },
    ) { Text("Update Arin") }
    updateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = selfRepo,
            onValueChange = { selfRepo = it },
            label = { Text("Arin's source (owner/repo)") },
            singleLine = true,
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = selfBranch,
            onValueChange = { selfBranch = it },
            label = { Text("Branch") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    TextButton(onClick = { vm.saveSelfRepo(selfRepo, selfBranch); vm.notice = "Saved" }) { Text("Save source location") }

    TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }
    if (showAdvanced) {
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("GitHub OAuth Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Optional: your own OAuth app from github.com/settings/developers (tick \"Enable Device Flow\").",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { vm.saveGithubClientId(clientId.trim()) }) { Text("Save Client ID") }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Or paste a personal access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Classic token with the repo and workflow scopes, from github.com/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(enabled = token.isNotBlank(), onClick = { vm.saveGithubToken(token); token = "" }) { Text("Save token") }
    }
}

/** Install, check and remove Arin's own Ubuntu Linux. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LinuxSettings(vm: ChatViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    Text(
        "Install Ubuntu Linux inside Arin, no root or other apps needed. Arin then runs commands, Node.js, npm, " +
            "Python, git and tools like REA in it, like Claude Code on a computer. Needs about 1 GB of space and " +
            "a few minutes on Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    when {
        !com.arin.terminal.Linux.supported() -> Text(
            "This phone's processor isn't supported by the built-in Linux. Use Termux below instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        vm.linuxBusy -> Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.padding(end = 10.dp).height(18.dp), strokeWidth = 2.dp)
            Text("Working… you can leave this screen.", style = MaterialTheme.typography.bodyMedium)
        }
        vm.linuxReady -> Text(
            "✓ Linux is installed (Ubuntu 24.04 with Node.js, npm, git, Python" +
                (if (vm.devToolsReady) ", Java 21, Kotlin, Gradle" else "") + ").",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        vm.linuxInstalled -> Text("Ubuntu is unpacked but setup didn't finish. Tap Install again to finish it.", style = MaterialTheme.typography.bodyMedium)
        else -> Text("Not installed.", style = MaterialTheme.typography.bodyMedium)
    }
    if (com.arin.terminal.Linux.supported() && !vm.linuxBusy) {
        // Every tool: Installed or not, with Install / Uninstall.
        val reaOn = vm.mcpServers.any { it.name == com.arin.mcp.Mcp.REA.name }
        ToolRow("Linux", "Ubuntu 24.04 with Node.js, npm, git and Python", vm.linuxReady) {
            if (!vm.linuxReady) androidx.compose.material3.Button(onClick = { vm.installLinux() }) { Text("Install") }
            if (vm.linuxInstalled) TextButton(onClick = { confirmRemove = true }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
        }
        com.arin.terminal.Linux.Tool.entries.forEach { tool ->
            val on = vm.toolInstalled(tool)
            ToolRow(tool.label, tool.detail, on) {
                if (on) TextButton(onClick = { vm.uninstallTool(tool) }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
                else OutlinedButton(onClick = { vm.installDevTools() }) { Text("Install") }
            }
        }
        ToolRow("REA", "Reverse Engineer Anything (AI agent for apps and APKs)", reaOn) {
            if (reaOn) {
                TextButton(onClick = { vm.repairRea() }) { Text("Repair") }
                TextButton(onClick = { vm.uninstallRea() }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            } else OutlinedButton(onClick = { vm.setUpRea() }) { Text("Install") }
        }
        if (vm.linuxReady && com.arin.terminal.Linux.Tool.entries.all { vm.toolInstalled(it) }) {
            OutlinedButton(onClick = { vm.installDevTools() }) { Text("Update all APK & build tools (newest)") }
        }
        if (vm.linuxReady) {
            // ARIN-PROTECTED: terminal-self-test — checks working folders, separate chats, simultaneous commands,
            // exit codes, a missing folder and the retry limit on this phone's real terminal.
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var testing by remember { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true; result = "Testing the terminal…"
                scope.launch {
                    result = runCatching { com.arin.terminal.TerminalSelfTest.report(com.arin.terminal.TerminalSelfTest.run(vm.getApplication<android.app.Application>())) }
                        .getOrElse { "Terminal test couldn't run: ${it.message}" }
                    testing = false
                }
            }) { Text(if (testing) "Testing…" else "Test the terminal") }
            if (result.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
                Text(result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (vm.linuxBusy) com.arin.selffix.InstallProgressPanel()
    if (vm.linuxLog.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
            Text(
                vm.linuxLog.takeLast(12).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp).fillMaxWidth(),
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Uninstall Linux?") },
            text = { Text("Everything installed in it, including REA and files in /root, is deleted. Your chats stay.") },
            confirmButton = { TextButton(onClick = { vm.removeLinux(); confirmRemove = false }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** One installable tool: name, what it is, ✓ Installed / Not installed, and its buttons. */
@Composable
private fun ToolRow(name: String, detail: String, installed: Boolean, buttons: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (installed) "✓ Installed" else "Not installed",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) { buttons() }
        }
    }
}

/** Lets the model run commands (built-in Linux or Termux): ask-first switch, Termux setup, test. */
@Composable
private fun TerminalSettings(vm: ChatViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    val installed = remember(refresh) { com.arin.terminal.Termux.installed(context) }
    val permitted = remember(refresh) { com.arin.terminal.Termux.permitted(context) }
    var test by remember { mutableStateOf<String?>(null) }
    val askPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    Toggle(
        "Let Arin run commands",
        "Arin can install and run tools (Node, npm, Python, git, REA…) and read the results, like Claude Code. " +
            "Uses the built-in Linux when it's installed, otherwise Termux.",
        vm.terminalOn,
        vm::toggleTerminal,
    )
    Toggle(
        "Ask before each command",
        "Shows every command and tool call for you to allow first. Turn off only if you trust what the model will run.",
        vm.terminalAsk,
        vm::toggleTerminalAsk,
    )
    Text("Or use Termux instead (optional)", style = MaterialTheme.typography.bodyLarge)
    Text(
        (if (installed) "✓ " else "1. ") + "Install Termux from F-Droid (the Play Store version is outdated).",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!installed) OutlinedButton(onClick = { uri.openUri(com.arin.terminal.Termux.DOWNLOAD_URL) }) { Text("Get Termux") }
    Text(
        "2. Open Termux, paste this and press Enter. It lets Arin send commands and installs Node, Python and git:",
        style = MaterialTheme.typography.bodySmall,
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Text(
            com.arin.terminal.Termux.SETUP_COMMAND,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            modifier = Modifier.padding(10.dp),
        )
    }
    TextButton(onClick = {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(com.arin.terminal.Termux.SETUP_COMMAND))
        vm.notice = "Setup command copied"
    }) { Text("Copy setup command") }
    Text(
        (if (permitted) "✓ " else "3. ") + "Allow Arin to send commands to Termux.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (installed && !permitted) {
        OutlinedButton(onClick = { askPermission.launch(com.arin.terminal.Termux.PERMISSION) }) { Text("Allow Arin to use Termux") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = vm.linuxInstalled || (installed && permitted), onClick = {
            test = "Testing…"
            scope.launch { test = vm.testTerminal() }
        }) { Text("Test connection") }
    }
    test?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** MCP servers: REA in one tap, any other local or remote server, with connect status. */
@Composable
private fun ToolsSettings(vm: ChatViewModel) {
    var adding by remember { mutableStateOf(false) }
    Text(
        "Connect MCP servers so the model can use their tools, as in Claude Code. Local servers run in Termux; " +
            "remote ones are reached by URL.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name }) {
        androidx.compose.material3.Button(onClick = { vm.setUpRea() }) { Text("Set up REA (Reverse Engineer Anything)") }
        Text(
            "Turns on the terminal and starts a chat where Arin installs REA in Termux, connects it and checks it works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    vm.mcpServers.forEach { s ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        vm.serverStatus[s.name] ?: com.arin.mcp.Mcp.status(s.name) ?: s.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = { vm.toggleServer(s, it) })
            }
            Row {
                TextButton(onClick = { vm.checkServer(s) }) { Text("Connect") }
                TextButton(onClick = { vm.removeServer(s) }) { Text("Remove") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var start by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        OutlinedTextField(value = name, onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, placeholder = { Text("http://127.0.0.1:8766/mcp or https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = start,
            onValueChange = { start = it },
            label = { Text("Start command in Termux (optional)") },
            placeholder = { Text("node ~/.arin/mcp-bridge.mjs --port 8766 -- npx -y some-mcp-server") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "For a local stdio server, wrap it with Arin's bridge: (nohup node ~/.arin/mcp-bridge.mjs --port PORT -- COMMAND > ~/.arin/NAME.log 2>&1 &); sleep 5",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                val s = com.arin.mcp.McpServer(name.trim(), url.trim(), start.trim(), token.trim())
                vm.saveServer(s)
                vm.checkServer(s)
                adding = false
            }) { Text("Add and connect") }
            TextButton(onClick = { adding = false }) { Text("Cancel") }
        }
    } else {
        OutlinedButton(onClick = { adding = true }) { Text("Add MCP server") }
    }
}

/** Every API the person added (name, base URL, key, model), one tap to use; Add API opens one form for all of it. */
@Composable
private fun MyApis(vm: ChatViewModel) {
    var editing by remember { mutableStateOf<com.arin.data.SavedApi?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (vm.savedApis.isEmpty()) {
        Text(
            "Add your own APIs: each with its name, base URL, API key and model. Then pick any of them here or from the model menu on the chat screen, without typing them again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    vm.savedApis.forEach { api ->
        val inUse = vm.currentSavedApi()?.slotId == api.slotId
        Surface(
            color = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().clickable { vm.useSavedApi(api) },
        ) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = inUse, onClick = { vm.useSavedApi(api) })
                Column(Modifier.weight(1f)) {
                    Text(api.name + if (inUse) "  · in use" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(api.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        (if (api.model.isNotBlank()) "Model: ${api.model}" else "Model: chosen from its list") +
                            if (vm.hasKeyFor(api)) " · key saved" else " · no key",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (vm.hasKeyFor(api)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { editing = api }) { Text("Edit") }
            }
        }
    }
    androidx.compose.material3.Button(onClick = { adding = true }) { Text(if (vm.savedApis.isEmpty()) "+ Add API" else "+ Add another API (${vm.savedApis.size} saved)") }
    if (adding || editing != null) {
        ApiForm(vm, editing, onClose = { adding = false; editing = null })
    }
}

/** One form for an API: name, base URL, key, model and request format. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ApiForm(vm: ChatViewModel, old: com.arin.data.SavedApi?, onClose: () -> Unit, preset: com.arin.llm.CustomApi.Preset? = null) {
    var name by remember { mutableStateOf(old?.name ?: preset?.name ?: "") }
    var url by remember { mutableStateOf(old?.baseUrl ?: preset?.baseUrl ?: "") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(old?.model ?: preset?.models?.firstOrNull()?.second ?: "") }
    var format by remember {
        mutableStateOf(old?.let { runCatching { com.arin.llm.ApiFormat.valueOf(it.format) }.getOrNull() } ?: preset?.format ?: com.arin.llm.ApiFormat.OpenAI)
    }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (old == null) "Add API" else "Edit API") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, placeholder = { Text("My API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it.trim(); problem = null }, label = { Text("Base URL") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key, { key = it.trim() },
                    label = { Text("API key") },
                    placeholder = { Text(if (old != null && vm.hasKeyFor(old)) "Saved (leave empty to keep it)" else "Paste the key") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(model, { model = it.trim() }, label = { Text("Model id") }, placeholder = { Text("e.g. deepseek-v4.1-flash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                com.arin.llm.CustomApi.presetFor(com.arin.llm.CustomApi.normalize(url, format))?.models?.takeIf { it.isNotEmpty() }?.let { list ->
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { (label, id) -> FilterChip(selected = model == id, onClick = { model = id }, label = { Text(label) }) }
                    }
                }
                com.arin.llm.CustomApi.presetFor(com.arin.llm.CustomApi.normalize(url, format))?.let {
                    Text("Get a key at ${it.keyPage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Request format", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.arin.llm.ApiFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (old != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete this API", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                problem = vm.saveApi(old, name, url, key, model, format)
                if (problem == null) onClose()
            }) { Text("Save and use") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
    if (confirmDelete && old != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${old.name}?") },
            confirmButton = { TextButton(onClick = { vm.removeSavedApi(old); confirmDelete = false; onClose() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Reply style, plus two notes Arin reads before every answer. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomInstructionsSettings(vm: ChatViewModel) {
    var about by remember { mutableStateOf(vm.aboutYou) }
    var how by remember { mutableStateOf(vm.howToAnswer) }
    Text("Reply style", style = MaterialTheme.typography.labelLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Normal", "Concise", "Detailed", "Formal").forEach { s ->
            FilterChip(selected = vm.replyStyle == s, onClick = { vm.chooseStyle(s) }, label = { Text(s) })
        }
    }
    OutlinedTextField(
        value = about,
        onValueChange = { about = it.take(1500) },
        label = { Text("About you") },
        placeholder = { Text("Your work, what you're building, what you already know…") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = how,
        onValueChange = { how = it.take(1500) },
        label = { Text("How Arin should answer") },
        placeholder = { Text("Simple words, short answers, always give steps…") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        enabled = about.trim() != vm.aboutYou || how.trim() != vm.howToAnswer,
        onClick = { vm.saveCustomInstructions(about, how) },
    ) { Text("Save") }
}

/** Offline models: download an uncensored GGUF model and run it on the phone with llama.cpp (in Linux). */
@Composable
private fun OfflineModelSettings(vm: ChatViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val linux = com.arin.terminal.Linux.installed(context)
    var repo by remember { mutableStateOf("") }
    Text(
        "These models run entirely on this phone (no internet, no API key, no cost) using llama.cpp inside Arin's Linux. " +
            "They are abliterated: their refusal behaviour was removed. Phone models are smaller than cloud ones, so answers are " +
            "slower and less capable than DeepSeek or Claude. First reply after loading takes a little longer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!linux) {
        Text("Install Linux first (the Linux section below), then come back here.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (vm.offlineStatus.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.offlineBusy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (vm.offlineBusy) Spacer(Modifier.width(10.dp))
                Text(vm.offlineStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val have = vm.offlineDownloaded()
    if (have.isNotEmpty()) {
        Text("On this phone", style = MaterialTheme.typography.titleSmall)
        have.forEach { f ->
            val api = vm.savedApis.firstOrNull { it.id == "local:${f.name}" }
            val inUse = api != null && vm.currentSavedApi()?.slotId == api.slotId
            Surface(
                color = if (inUse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension + if (inUse) "  · in use" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text("%.2f GB".format(f.length() / 1073741824.0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(enabled = !vm.offlineBusy, onClick = {
                        if (api != null) vm.useSavedApi(api) else vm.useOfflineFile(f)
                    }) { Text("Use") }
                    TextButton(enabled = !vm.offlineBusy, onClick = { vm.deleteOfflineModel(f) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        OutlinedButton(onClick = { vm.stopOfflineModel() }) { Text("Stop offline model (free memory)") }
    }
    Text("Download a model", style = MaterialTheme.typography.titleSmall)
    com.arin.llm.LocalModel.presets.forEach { p ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                Text("${p.size} download · needs ${p.ram}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.Button(enabled = linux && !vm.offlineBusy, onClick = { vm.setUpOfflineModel(p.repo, p.name) }) {
                    Text("Download and use")
                }
            }
        }
    }
    OutlinedTextField(
        value = repo, onValueChange = { repo = it.trim() },
        label = { Text("Any Hugging Face GGUF repo or .gguf link") },
        placeholder = { Text("e.g. bartowski/mlabonne_Qwen3-4B-abliterated-GGUF") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    TextButton(enabled = linux && !vm.offlineBusy && repo.isNotBlank(), onClick = { vm.setUpOfflineModel(repo, "") }) { Text("Download and use this model") }
}

/** Custom APIs: the full list (add as many as you like), plus one-tap services that open a new API form. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomApiSettings(vm: ChatViewModel) {
    var preset by remember { mutableStateOf<com.arin.llm.CustomApi.Preset?>(null) }
    MyApis(vm)
    Spacer(Modifier.height(4.dp))
    Text("Add a service", style = MaterialTheme.typography.titleSmall)
    Text(
        "Tap a service to add it as a NEW API (it won't replace the ones above). Paste its key, pick a model, Save and use.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.arin.llm.CustomApi.presets.forEach { p ->
            androidx.compose.material3.AssistChip(onClick = { preset = p }, label = { Text("+ " + p.name) })
        }
    }
    preset?.let { p -> ApiForm(vm, null, onClose = { preset = null }, preset = p) }
}

@Composable
private fun KeyRow(vm: ChatViewModel, p: Provider, title: String = p.label, resetOn: Any? = null) {
    var editing by remember(resetOn) { mutableStateOf(false) }
    var value by remember(resetOn) { mutableStateOf("") }
    var saved by remember(resetOn) { mutableStateOf(vm.hasKey(p)) }
    val hint = if (p == Provider.Custom) com.arin.llm.CustomApi.presetFor()?.keyPage ?: p.keyHint else p.keyHint
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (saved) "Key saved" else "Get one at $hint",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { editing = !editing; value = "" }) { Text(if (saved) "Change" else "Add") }
        }
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(if (p == Provider.Custom) title else "${p.label} API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { vm.saveKey(p, value); saved = value.isNotBlank(); editing = false }) { Text("Save") }
                if (saved) TextButton(onClick = { vm.saveKey(p, ""); saved = false; editing = false }) { Text("Remove key") }
            }
        }
    }
}
