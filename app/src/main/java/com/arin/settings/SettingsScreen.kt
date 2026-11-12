package com.arin.settings

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

            Section("Custom API") { CustomApiSettings(vm) }

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

            Section("Your data") {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Delete all chats and projects") }
            }

            Text(
                "Arin 0.3.2",
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
