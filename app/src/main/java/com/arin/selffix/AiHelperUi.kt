package com.arin.selffix

// ARIN-PROTECTED-RULE: ai-helper
// PROTECTED (set by the owner): the AI helper — its on/off switch and "Talk to Claude, ChatGPT, Gemini or a Custom
// API model" — is available in EVERY tab. No update may remove, hide or change it, and only the owner (by tapping)
// can switch it on or off. Changing this needs the owner's approval (see PROTECTED_RULES.md).

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.data.SavedApi
import com.arin.llm.ModelOption

/** Where the "Talk to…" flow is: choosing a Custom API, choosing a model, then asking. */
class AiHelperFlow {
    /** "claude", "chatgpt", "gemini" or "custom" while picking; null = closed. */
    var kind by mutableStateOf<String?>(null)
    var api by mutableStateOf<SavedApi?>(null)
    var asking by mutableStateOf(false)
    var question by mutableStateOf("")
    fun close() { kind = null; api = null; asking = false }
}

@Composable
fun rememberAiHelperFlow() = remember { AiHelperFlow() }

/**
 * The AI helper's menu items, for any ⋮ menu: switch it on or off, and "Talk to" Claude, ChatGPT, Gemini or a
 * Custom API (pick one of the saved APIs, then any of its models).
 */
@Composable
fun AiHelperMenuItems(vm: ChatViewModel, flow: AiHelperFlow, close: () -> Unit, afterStart: () -> Unit = {}) {
    // Each tab has its own switch: this one changes only the tab it is in.
    val tab = LocalHelperTab.current
    val on = vm.helperOn(tab)
    if (vm.helperTalk && on) DropdownMenuItem(
        text = { Text("← Back to ${vm.modelLabel()}", color = MaterialTheme.colorScheme.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
        onClick = { close(); vm.endHelperTalk() },
    )
    DropdownMenuItem(
        text = { Text(if (on) "✓ AI helper on in this tab (tap to turn off)" else "AI helper off in this tab: models work alone (tap to turn on)") },
        onClick = { close(); vm.setClaudeHelper(!on, tab) },
    )
    if (on) {
        DropdownMenuItem(
            text = { Text((if (vm.helperTalk) "✓ Talking to " else "Talk to ") + "${vm.helperLabel} (in the message box)", style = MaterialTheme.typography.bodySmall) },
            onClick = { close(); vm.startHelperTalk(); afterStart() },
            enabled = !vm.askingClaude,
        )
        // The helper in use is ticked, with its model (helperLabel changes when another one is picked, so this updates).
        val active = remember(vm.helperLabel) { vm.helperKind() }
        listOf("claude" to "Talk to Claude", "chatgpt" to "Talk to ChatGPT", "gemini" to "Talk to Gemini", "free" to "Talk to Free models", "offline" to "Talk to the offline model", "custom" to "Talk to Custom API…")
            .forEach { (k, label) ->
                val on = k == active
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (on) "✓ " + label.removeSuffix("…") + " — ${vm.helperLabel}" else label) + (if (vm.askingClaude && on) " (answering…)" else ""),
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                            color = if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    },
                    enabled = !vm.askingClaude,
                    onClick = { close(); flow.api = null; flow.asking = false; flow.kind = k },
                )
            }
    }
}

/** The dialogs of the "Talk to…" flow. [intoChat] = put the question and answer in the open chat. */
@Composable
fun AiHelperDialogs(vm: ChatViewModel, flow: AiHelperFlow, intoChat: Boolean, afterStart: () -> Unit = {}) {
    val context = LocalContext.current
    val kind = flow.kind
    when {
        kind == null -> Unit
        // 1. Custom API: choose one of the APIs already added.
        kind == "custom" && flow.api == null && !flow.asking -> {
            val apis = remember { ClaudeHelper.savedApis(context) }
            AlertDialog(
                onDismissRequest = { flow.close() },
                title = { Text("Choose a Custom API") },
                text = {
                    if (apis.isEmpty()) Text("No Custom API is saved yet. Add one in Settings → Custom API, then come back.")
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apis) { a ->
                            Column(Modifier.fillMaxWidth().clickable { flow.api = a }.padding(vertical = 10.dp)) {
                                Text(a.name.ifBlank { a.baseUrl })
                                Text(a.baseUrl + (if (a.model.isNotBlank()) " · ${a.model}" else ""), style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { flow.close() }) { Text("Cancel") } },
            )
        }
        // 2. Choose the model that assists.
        !flow.asking -> {
            var models by remember(kind, flow.api) { mutableStateOf<List<ModelOption>?>(null) }
            LaunchedEffect(kind, flow.api) { models = ClaudeHelper.models(context, kind, flow.api) }
            val name = when (kind) { "chatgpt" -> "ChatGPT"; "gemini" -> "Gemini"; "custom" -> flow.api?.name?.ifBlank { null } ?: "Custom API"; else -> "Claude" }
            fun pick(model: String) {
                vm.setHelper(ClaudeHelper.Helper(kind, model, flow.api?.slotId.orEmpty(), flow.api?.name?.ifBlank { flow.api?.baseUrl }.orEmpty()))
                // No question box: type in the tab's own message box; the answer comes back in the conversation.
                flow.close()
                vm.startHelperTalk()
                afterStart()
            }
            AlertDialog(
                onDismissRequest = { flow.close() },
                title = { Text("$name: choose the assistant model") },
                text = {
                    val list = models
                    when {
                        list == null -> CircularProgressIndicator()
                        list.isEmpty() -> Text(
                            if (kind == "custom") "This API listed no models. Check its key in Settings → Custom API."
                            else "No models found. Add a $name key in Settings (or, for Claude, sign in on the Claude Code tab).",
                        )
                        else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(list) { m ->
                                Column(Modifier.fillMaxWidth().clickable { pick(m.id) }.padding(vertical = 10.dp)) {
                                    Text(m.label)
                                    if (m.label != m.id) Text(m.id, style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { if (kind != "custom") TextButton(onClick = { pick("") }) { Text("Use default") } },
                dismissButton = { TextButton(onClick = { flow.close() }) { Text("Cancel") } },
            )
        }
        // 3. No question box: you talk to the helper in the tab's own message box.
        else -> LaunchedEffect(kind) {
            flow.close()
            vm.startHelperTalk()
            afterStart()
        }
    }
    // Tabs that aren't a chat show the answer here.
    if (!intoChat) {
        vm.helperReply?.let { answer ->
            AlertDialog(
                onDismissRequest = { vm.helperReply = null },
                title = { Text(vm.helperLabel) },
                text = { Text(answer, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { vm.helperReply = null }) { Text("OK") } },
            )
        }
    }
}

/** The app's view model, provided once for every screen, so any screen can show the AI helper. */
val LocalAiHelperVm = androidx.compose.runtime.staticCompositionLocalOf<ChatViewModel?> { null }

/** Which tab the screen is: each tab has its own AI helper switch ("chat", "build", "arin-update", "claude-code", …). */
val LocalHelperTab = androidx.compose.runtime.compositionLocalOf { "chat" }

/** ARIN-PROTECTED: the AI helper button for screens that don't hold the view model themselves. */
@Composable
fun AiHelperButtonHere() {
    LocalAiHelperVm.current?.let { AiHelperButton(it) }
}

/** A ready-made ⋮-style button with the AI helper menu, for the top bar of any tab that isn't a chat. */
@Composable
fun AiHelperButton(vm: ChatViewModel, hasInput: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val flow = rememberAiHelperFlow()
    val afterStart: () -> Unit = { if (!hasInput) vm.openChat = true }
    androidx.compose.foundation.layout.Box {
        val on = vm.helperOn(LocalHelperTab.current)
        IconButton(onClick = { open = true }) {
            if (vm.helperTalk && on) {
                // Talking with the helper: a purple dot on the icon; tap it for "Back to my model".
                androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge(containerColor = androidx.compose.ui.graphics.Color(0xFFA855F7)) }) {
                    Icon(Icons.Filled.Face, contentDescription = "AI helper (talking with ${vm.helperLabel}) — tap for Back to my model",
                        tint = androidx.compose.ui.graphics.Color(0xFFA855F7))
                }
            } else Icon(Icons.Filled.Face, contentDescription = "AI helper", tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AiHelperMenuItems(vm, flow, close = { open = false }, afterStart = afterStart)
        }
    }
    AiHelperDialogs(vm, flow, intoChat = false, afterStart = afterStart)
}

/** Shown above a message box while it talks to the AI helper: who, and a way back to the tab's own model. */
@Composable
fun HelperTalkBar(vm: ChatViewModel, modifier: Modifier = Modifier) {
    // Moved out of the way: the 🟣 icon at the top shows a purple dot while talking with the helper, and its menu
    // starts with "← Back to my model". Nothing is drawn over the chat any more.
    if (true) return
    if (!vm.helperTalk || !vm.helperOn(LocalHelperTab.current)) return
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        androidx.compose.foundation.layout.Row(Modifier.padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                (if (vm.askingClaude) "🟣 ${vm.helperLabel} is helping…" else "🟣 With ${vm.helperLabel}'s help — your model reads each message first, then asks it"),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            TextButton(onClick = { vm.endHelperTalk() }) { Text("Back to my model") }
        }
    }
}
