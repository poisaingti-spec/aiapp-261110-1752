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
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
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
            }

            // Extra features, folded away so the screen stays simple. Tap a row to open it.
            FoldSection("Custom API", if (vm.provider == Provider.Custom) "In use · ${vm.customBaseUrl}" else "Use any service with your own base URL and key") {
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
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vm.linuxReady) androidx.compose.material3.Button(onClick = { vm.installLinux() }) { Text("Install Linux") }
            if (vm.mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name && com.arin.mcp.Mcp.status(it.name) != null }) {
                OutlinedButton(onClick = { vm.setUpRea() }) { Text(if (vm.linuxReady) "Install REA" else "Install Linux + REA") }
            }
            if (!vm.androidBuildReady) {
                OutlinedButton(onClick = { vm.installDevTools() }) { Text("Install Android build tools (JDK, Gradle, SDK)") }
            }
            if (vm.linuxReady && vm.mcpServers.any { it.name == com.arin.mcp.Mcp.REA.name }) {
                OutlinedButton(onClick = { vm.repairRea() }) { Text("Repair REA") }
            }
            if (vm.linuxInstalled) TextButton(onClick = { confirmRemove = true }) { Text("Remove Linux") }
        }
    }
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
            title = { Text("Remove Linux?") },
            text = { Text("Everything installed in it, including REA and files in /root, is deleted. Your chats stay.") },
            confirmButton = { TextButton(onClick = { vm.removeLinux(); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
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

/** Your own service: base URL (typed or a preset), request format, key and model id. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomApiSettings(vm: ChatViewModel) {
    val base = vm.customBaseUrl
    var urlText by remember(base) { mutableStateOf(base) }
    var modelText by remember(base) { mutableStateOf(vm.customModel) }
    var modelNote by remember(base) { mutableStateOf<String?>(null) }

    Text(
        "Connect any service with its own base URL and key. Tap one, or paste the address it gives you. " +
            "Each address keeps its own key and model.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.arin.llm.CustomApi.presets.forEach { preset ->
            FilterChip(
                selected = base == preset.baseUrl,
                onClick = { vm.setCustomBase(preset.baseUrl, preset.format) },
                label = { Text(preset.name) },
            )
        }
    }
    OutlinedTextField(
        value = urlText,
        onValueChange = { urlText = it },
        label = { Text("Base URL") },
        placeholder = { Text("https://example.com/v1") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(
        enabled = urlText.isNotBlank() && com.arin.llm.CustomApi.normalize(urlText, vm.customFormat) != base,
        onClick = { vm.setCustomBase(urlText) },
    ) { Text("Use this URL") }

    Text("Request format", style = MaterialTheme.typography.bodyLarge)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        com.arin.llm.ApiFormat.entries.forEach { f ->
            FilterChip(selected = vm.customFormat == f, onClick = { vm.chooseCustomFormat(f) }, label = { Text(f.label) })
        }
    }
    Text(
        "Most services use the OpenAI format. Pick the Claude format for gateways that copy the Claude API.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    KeyRow(vm, Provider.Custom, title = "API key for ${com.arin.llm.CustomApi.label(base)}", resetOn = base)

    OutlinedTextField(
        value = modelText,
        onValueChange = { modelText = it; modelNote = null },
        label = { Text("Model id (optional)") },
        placeholder = { Text("e.g. deepseek-v4.1-flash:free") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Models this service is known for: one tap fills in and saves the id.
    com.arin.llm.CustomApi.presetFor(base)?.models?.takeIf { it.isNotEmpty() }?.let { list ->
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            list.forEach { (label, id) ->
                FilterChip(
                    selected = vm.customModel == id,
                    onClick = { modelText = id; vm.saveCustomModel(id); modelNote = "Saved" },
                    label = { Text(label) },
                )
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.saveCustomModel(modelText); modelNote = "Saved" }) { Text("Save model") }
        modelNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
    Text(
        "Arin loads the service's model list on its own. Type an id only if the list is missing or doesn't show the one you want.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (vm.provider != Provider.Custom) {
        OutlinedButton(onClick = { vm.chooseProvider(Provider.Custom) }) { Text("Use Custom API for replies") }
    }
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
