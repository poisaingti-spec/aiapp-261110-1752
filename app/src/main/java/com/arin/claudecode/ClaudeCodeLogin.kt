package com.arin.claudecode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel

/** One-line summary for the Settings row. */
fun claudeCodeSummary(vm: ChatViewModel): String = when {
    !vm.ccInstalled -> "Not installed"
    vm.ccBackend == "pro" -> if (vm.ccConnected) "Claude Pro login ✓" else "Claude Pro login · not signed in"
    else -> if (vm.ccApiConnected) "Custom API ✓ · ${vm.ccModel}" else "Custom API · not connected"
}

/**
 * Settings → Claude Code: install, then sign in one of two ways — a Claude Pro/Max subscription (Google
 * account login) or the person's own API (URL + key only; models are listed for them).
 */
@Composable
fun ClaudeCodeLogin(vm: ChatViewModel) {
    LaunchedEffect(Unit) { vm.refreshClaudeCode() }
    val colors = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!vm.ccInstalled) {
            Text(
                "Installs Anthropic's Claude Code in Arin's Linux (about 185 MB download, needs about 700 MB free; the download resumes if the connection drops).",
                style = small, color = colors.onSurfaceVariant,
            )
            Button(onClick = { vm.installClaudeCode() }, enabled = !vm.ccBusy) { Text(if (vm.ccBusy) "Installing…" else "Install Claude Code") }
            if (vm.ccInstalling) com.arin.selffix.InstallProgressPanel()
            vm.ccLog.takeLast(8).forEach { Text(it, style = small, color = colors.onSurfaceVariant) }
            return@Column
        }

        Text("Sign in with", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.ccBackend == "pro", onClick = { vm.chooseCcBackend("pro") }, label = { Text("Claude Pro (Google)") })
            FilterChip(selected = vm.ccBackend == "custom", onClick = { vm.chooseCcBackend("custom") }, label = { Text("Custom API") })
        }

        if (vm.ccBackend == "pro") ProLogin(vm) else CustomApiLogin(vm)
    }
}

@Composable
private fun ProLogin(vm: ChatViewModel) {
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.bodySmall
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    if (vm.ccConnected && !adding && vm.ccAddingAccount == null) {
        Text("Signed in with your Claude subscription ✓", color = colors.primary)
        Text("Each account has its own sign-in. Only the ACTIVE account is used — here, by the helper, workers and every chat.",
            style = small, color = colors.onSurfaceVariant)
        // Your Claude accounts: each has its own sign-in; pick one per chat from the model menu at the top.
        vm.ccAccountsTick
        val accounts = remember(vm.ccAccountsTick) { com.arin.claudecode.ClaudeAccounts.migrate(ctx); com.arin.claudecode.ClaudeAccounts.list(ctx) }
        val activeId = remember(vm.ccAccountsTick) { com.arin.claudecode.ClaudeAccounts.active(ctx) ?: accounts.firstOrNull()?.id }
        accounts.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = a.id == activeId, onCheckedChange = { on -> if (on) vm.makeCcAccountMain(a.id) })
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                var email by remember(a.id) { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(a.id) { email = com.arin.claudecode.ClaudeAccounts.email(ctx, a.id) }
                Text("• ${a.name}" + (email?.let { " ($it)" } ?: ""), modifier = Modifier.weight(1f))
                var renaming by rememberSaveable(a.id) { mutableStateOf(false) }
                TextButton(onClick = { renaming = true }) { Text("Rename") }
                if (renaming) {
                    var name by rememberSaveable(a.id) { mutableStateOf(a.name) }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { renaming = false },
                        title = { Text("Rename Claude account") },
                        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Account name") }) },
                        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { renaming = false; vm.renameCcAccount(a.id, name.trim()) }) { Text("Save") } },
                        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
                    )
                }
                if (a.id == activeId) Text("Active ", color = colors.primary, style = small)
                TextButton(onClick = { vm.removeCcAccount(a.id) }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text("+ Add another Claude account") }
        TextButton(onClick = { vm.ccLogout() }) { Text("Sign out") }
        return
    }
    if (adding && vm.ccAddingAccount == null) {
        Text("Name this account (for example \"Work\" or \"Account 2\"), then sign in to it on a fresh page.", style = small, color = colors.onSurfaceVariant)
        OutlinedTextField(newName, { newName = it }, placeholder = { Text("Account name") }, singleLine = true)
        Row {
            Button(onClick = { adding = false; vm.startCcLogin(newName.trim().ifBlank { "Account ${com.arin.claudecode.ClaudeAccounts.list(ctx).size + 1}" }); newName = "" }) { Text("Sign in") }
            TextButton(onClick = { adding = false }) { Text("Cancel") }
        }
        return
    }
    Text(
        "Uses your Claude Pro/Max plan — no API key, no extra billing. Sign in with the Google account your plan is on. " +
            "Tap the button, open the sign-in page, and after signing in paste the code it shows.",
        style = small, color = colors.onSurfaceVariant,
    )
    Button(onClick = { vm.startCcLogin() }, enabled = !vm.ccBusy) { Text(if (vm.ccBusy) "Waiting for sign-in…" else "Sign in with Google (Claude)") }
    if (vm.ccLoginStatus.isNotBlank()) {
        val bad = vm.ccLoginStatus.contains("error", true) || vm.ccLoginStatus.contains("couldn't", true) ||
            vm.ccLoginStatus.contains("invalid", true) || vm.ccLoginStatus.contains("failed", true)
        Text(vm.ccLoginStatus, style = small, color = if (bad) colors.error else colors.onSurfaceVariant)
    }
    vm.ccLoginUrl?.let { url ->
        // Fresh sign-in inside Arin: no saved logins, so it signs in to exactly the account you choose.
        var fresh by rememberSaveable { mutableStateOf(false) }
        var pageError by rememberSaveable { mutableStateOf("") }
        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
        Button(onClick = { pageError = ""; fresh = true }) { Text("Sign in here (fresh page)") }
        if (fresh) com.arin.claudecode.FreshSignInDialog(url, onCode = { c -> fresh = false; if (c != com.arin.claudecode.LOCAL_DONE) vm.pasteCcCode(c) }, onClose = { fresh = false },
            onError = { e -> pageError = e })
        if (pageError.isNotBlank()) Text(pageError, style = small, color = colors.error)
        // Always works: a private tab remembers no account, so it signs in to exactly the one you type.
        OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(url)); vm.notice = "Sign-in link copied — paste it in a Firefox private tab" }) {
            Text("Copy link for a private tab")
        }
        Text("Firefox: ⋮ → New private tab → paste the link → sign in → Authorize. Arin finishes by itself (if a code shows instead, paste it below).",
            style = small, color = colors.onSurfaceVariant)
        OutlinedButton(onClick = { uri.openUri(url) }) { Text("Or open in your browser") }
        var code by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it }, placeholder = { Text("Paste the code from the sign-in page") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(enabled = code.isNotBlank(), onClick = { vm.pasteCcCode(code); code = "" }) { Text("Submit") }
        }
    }
    var token by rememberSaveable { mutableStateOf("") }
    Text("Or paste a token from `claude setup-token`:", style = small, color = colors.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(token, { token = it }, placeholder = { Text("sk-ant-oat01-…") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextButton(enabled = token.isNotBlank(), onClick = { vm.saveCcToken(token); token = "" }) { Text("Save") }
    }
}

@Composable
private fun CustomApiLogin(vm: ChatViewModel) {
    val colors = MaterialTheme.colorScheme
    var base by rememberSaveable { mutableStateOf(vm.settings.ccCustomBase.removeSuffix("/anthropic")) }
    var key by rememberSaveable { mutableStateOf(vm.settings.ccCustomKey) }
    var pickOpen by rememberSaveable { mutableStateOf(false) }
    val saved = vm.ccSavedApiChoices()
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.onSurface, unfocusedTextColor = colors.onSurface)
    Text(
        "Your own API is the brain, Claude Code is the tool. Enter only the URL and key — the models are listed for you. " +
            "Works with Anthropic-style and OpenAI-style APIs. Used only by Claude Code, not the chat tab.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
    )
    if (saved.isNotEmpty()) {
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { pickOpen = true }) { Text("Use a saved API from chat") }
            androidx.compose.material3.DropdownMenu(expanded = pickOpen, onDismissRequest = { pickOpen = false }) {
                saved.forEach { (name, url, keyModel) ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text(name)
                                Text(url, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        },
                        onClick = {
                            base = url; key = keyModel.first; pickOpen = false
                            vm.connectCcApi(url, keyModel.first, preferModel = keyModel.second)
                        },
                    )
                }
            }
        }
    }
    OutlinedTextField(
        base, { base = it }, label = { Text("API URL") }, placeholder = { Text("https://api.deepseek.com") },
        singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        key, { key = it }, label = { Text("API key") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), colors = fieldColors, modifier = Modifier.fillMaxWidth(),
    )
    vm.ccConnectError?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.connectCcApi(base, key) }, enabled = !vm.ccConnecting && base.isNotBlank() && key.isNotBlank()) {
            Text(if (vm.ccConnecting) "Connecting…" else if (vm.ccApiConnected) "Reconnect" else "Connect")
        }
        if (vm.ccConnecting) { Spacer(Modifier.width(10.dp)); CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
    }
    if (vm.ccApiConnected) {
        Spacer(Modifier.height(2.dp))
        Text(
            "Connected ✓ · ${vm.ccCustomModelCount} model${if (vm.ccCustomModelCount == 1) "" else "s"}" +
                if (vm.settings.ccCustomKind == "openai") " · via Arin's bridge" else "",
            color = colors.primary, style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { vm.disconnectCcApi() }) { Text("Disconnect") }
    }
}
