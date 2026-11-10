package com.aiapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiapp.llm.Role

/**
 * Reverse Engineering tab.
 *
 * A mobile APK-analysis / reverse-engineering workbench (same category as `morluto/rea`, Apktool,
 * and JADX). Load an APK, decode it on the phone, ask the model about it, connect an optional
 * remote `rea` backend for deep analysis, and rebuild a re-signed APK with authorized edits.
 *
 * For apps you are authorized to inspect and modify — your own apps, open-source apps, security
 * research, learning, and personal modding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseScreen(chat: ChatViewModel, vm: ReverseViewModel, onBack: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.loadApk(uri)
    }
    var question by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var newLabel by remember { mutableStateOf("") }
    var newVersion by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Reverse Engineering") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Analyse and modify APKs you are authorized to work on — your own apps, open-source " +
                    "apps, security research or personal modding. Decoding runs on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Model tip: planning edits and fixing builds needs a capable model. Small offline models " +
                    "often can't. Pick a strong FREE one in Chat first: Gemini (free AI Studio key) or an " +
                    "Open model like DeepSeek / Qwen-Coder / GLM / Kimi (free OpenRouter key). " +
                    "Current model: ${chat.provider.label}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch("application/vnd.android.package-archive") }, enabled = !vm.busy) {
                    Text("Load APK")
                }
                OutlinedButton(onClick = { picker.launch("*/*") }, enabled = !vm.busy) { Text("Pick any file") }
            }

            vm.status?.let { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  $it", style = MaterialTheme.typography.bodySmall)
            } }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            vm.report?.let { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(r.appLabel.ifBlank { r.packageName }, style = MaterialTheme.typography.titleMedium)
                        Field("Package", r.packageName)
                        Field("Version", "${r.versionName} (${r.versionCode})")
                        Field("SDK", "min ${r.minSdk} · target ${r.targetSdk}")
                        Field("Runtime", r.runtimeHint)
                        Field("ABIs", r.abis.joinToString().ifBlank { "—" })
                        Field("Signing", r.signature.summary)
                        Field("Permissions", r.permissions.size.toString())
                        Field("Activities", r.activities.size.toString())
                        Field("Services / Receivers", "${r.services.size} / ${r.receivers.size}")
                        Field("DEX / native libs", "${r.dexFiles.size} / ${r.nativeLibs.size}")
                    }
                }

                // Ask the model about the decoded app.
                Text("Ask about this app", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = question, onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. How does it store settings? Where are the ads?") },
                    minLines = 2,
                )
                Button(
                    onClick = { vm.ask(question.trim(), chat.provider, chat.model, chat.effort); question = "" },
                    enabled = !vm.answering && question.isNotBlank(),
                ) { Text(if (vm.answering) "Thinking…" else "Ask ${chat.provider.label}") }

                vm.transcript.forEach { m ->
                    Text(
                        (if (m.role == Role.USER) "You: " else "AI: ") + m.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (vm.answering && vm.liveAnswer.isNotBlank()) {
                    Text("AI: " + vm.liveAnswer, style = MaterialTheme.typography.bodyMedium)
                }

                // Instruction-driven change: model plans edits, engine applies + rebuilds in one tap.
                Text("Change the app", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Describe a change to the app's text, name or version. The model plans it, and it's " +
                        "applied, rebuilt and signed in one step. Deeper code changes need the rea backend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instruction, onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. change the welcome text to \"Hello\" and rename the app to Demo") },
                    minLines = 2,
                )
                Button(
                    onClick = { vm.applyInstruction(instruction.trim(), chat.provider, chat.model, chat.effort) },
                    enabled = !vm.busy && !vm.answering && instruction.isNotBlank(),
                ) { Text("Apply change & rebuild") }
                vm.planSummary?.let { Text("Plan: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                // Manual rebuild + re-sign (rename / version only).
                Text("Rebuild & re-sign", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(newLabel, { newLabel = it }, Modifier.fillMaxWidth(), label = { Text("New app name (optional)") }, singleLine = true)
                OutlinedTextField(newVersion, { newVersion = it }, Modifier.fillMaxWidth(), label = { Text("New version name (optional)") }, singleLine = true)
                Button(
                    onClick = { vm.rebuild(newLabel.ifBlank { null }, newVersion.ifBlank { null }, null, null) },
                    enabled = !vm.busy && (newLabel.isNotBlank() || newVersion.isNotBlank()),
                ) { Text("Rebuild signed APK") }

                vm.rebuilt?.let { res ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Rebuilt & signed", style = MaterialTheme.typography.titleSmall)
                            res.appliedNotes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            Text("Signed with ${res.signedWith}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { vm.install() }) { Text("Install") }
                        }
                    }
                }
            }

            // Remote rea backend (deep desktop-grade analysis).
            Text("Remote rea backend (optional)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Run `npx -y rea-agents@latest mcp --http --host 0.0.0.0 --port 8765` on your PC for " +
                    "native-binary, IL2CPP/Unity, Flutter and full-decompiler analysis a phone can't do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(vm.reaUrl, { vm.reaUrl = it }, Modifier.fillMaxWidth(), label = { Text("rea URL (http://<pc-ip>:8765/mcp)") }, singleLine = true)
            Button(onClick = { vm.connectRea() }, enabled = !vm.busy && vm.reaUrl.isNotBlank()) {
                Text(if (vm.reaConnected) "Reconnect rea" else "Connect rea")
            }
            if (vm.reaConnected) {
                Text("Connected: ${vm.reaInfo}  ·  ${vm.reaTools.size} tools", style = MaterialTheme.typography.bodySmall)
                vm.reaTools.take(12).forEach {
                    Text("• ${it.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
