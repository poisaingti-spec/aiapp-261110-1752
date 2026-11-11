package com.aiapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Role

/**
 * Reverse Engineering tab — the same conversational interface as Chat.
 *
 * Load the target (APK, a .zip holding one, or a direct download URL), pick a model, then talk to it:
 * ask a question or describe a change. You can send another message while it's working — it's folded
 * in at the next checkpoint, right up to the moment GitHub Actions is kicked off (that external build
 * itself can't be redirected once started, same as any dispatched tool call — but the next one can be).
 *
 * For apps you are authorized to inspect and modify — your own apps, open-source apps, security
 * research, and personal modding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseScreen(chat: ChatViewModel, vm: ReverseViewModel, onBack: () -> Unit) {
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.loadApk(uri) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.loadZip(uri) }
    var input by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(vm.transcript.size, vm.liveAnswer.length) {
        val count = vm.transcript.size + if (vm.answering && vm.liveAnswer.isNotEmpty()) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    val title = vm.report?.let { it.appLabel.ifBlank { it.packageName } } ?: "Reverse Engineering"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.report != null) TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Simple" else "Advanced") }
                },
            )
        },
        bottomBar = {
            if (vm.report != null) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        vm.status?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = { Text(if (vm.answering) "Say something to redirect…" else "Ask a question, or describe a change") },
                                maxLines = 5,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { vm.send(input, chat.provider, chat.model, chat.effort); input = "" },
                                enabled = input.isNotBlank(),
                            ) { Text(if (vm.answering) "Steer" else "Send") }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.report == null) {
                LoadTargetPanel(vm, url, { url = it }, onPickApk = { apkPicker.launch("application/vnd.android.package-archive") }, onPickZip = { zipPicker.launch("application/zip") })
            } else {
                // Model picker — exactly the Chat dropdowns, right on this screen.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dropdown(label = chat.provider.label, options = ProviderId.entries.toList(), display = { it.label }, onSelect = chat::selectProvider)
                    val modelLabel = when {
                        chat.loadingModels -> "Loading models…"
                        chat.model.isBlank() -> "Pick model"
                        else -> chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model
                    }
                    Dropdown(
                        label = modelLabel, options = chat.models, display = { it.name },
                        onSelect = { chat.selectModel(it.id) }, onOpen = { if (chat.models.isEmpty()) chat.refreshModels() },
                    )
                    Dropdown(label = "Effort: ${chat.effort.label}", options = Effort.entries.toList(), display = { it.label }, onSelect = chat::selectEffort)
                }

                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.transcript) { m -> MessageBubble(m) }
                    if (vm.answering && vm.liveAnswer.isNotBlank()) {
                        item { MessageBubble(ChatMessage(Role.ASSISTANT, vm.liveAnswer)) }
                    }
                    if (vm.localInstallReady != null) {
                        item { Button(onClick = { vm.installLocal() }) { Text("Install") } }
                    }
                }

                if (showAdvanced) AdvancedPanel(vm)
            }
        }
    }
}

@Composable
private fun LoadTargetPanel(
    vm: ReverseViewModel,
    url: String,
    onUrlChange: (String) -> Unit,
    onPickApk: () -> Unit,
    onPickZip: () -> Unit,
) {
    Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Load an app to reverse-engineer — for apps you're authorized to work on: your own, " +
                "open-source, security research, or modding. Decoding runs on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickApk, enabled = !vm.loading) { Text("Upload APK") }
            OutlinedButton(onClick = onPickZip, enabled = !vm.loading) { Text("Upload .zip") }
        }
        Text("or paste a direct download link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url, onValueChange = onUrlChange,
                placeholder = { Text("https://…/app.apk") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !vm.loading,
            )
            Button(onClick = { vm.loadUrl(url) }, enabled = !vm.loading && url.isNotBlank()) { Text("Fetch") }
        }
        vm.status?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AdvancedPanel(vm: ReverseViewModel) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Remote rea backend (optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "Run `npx -y rea-agents@latest mcp --http --host 0.0.0.0 --port 8765` on your PC for " +
                "native-binary, IL2CPP/Unity, Flutter and full-decompiler analysis a phone can't do.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.reaUrl, { vm.reaUrl = it }, Modifier.weight(1f), label = { Text("rea URL") }, singleLine = true)
            Button(onClick = { vm.connectRea() }, enabled = vm.reaUrl.isNotBlank()) { Text(if (vm.reaConnected) "Reconnect" else "Connect") }
        }
        if (vm.reaConnected) {
            Text("Connected: ${vm.reaInfo}  ·  ${vm.reaTools.size} tools", style = MaterialTheme.typography.bodySmall)
        }
    }
}
