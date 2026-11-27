package com.arin.selffix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel

/**
 * Which AI answers in this chat: the Claude Code login, Claude, ChatGPT, Gemini or the free models (Custom APIs are
 * listed under "Your APIs"). The ✓ shows the one in use. It changes only when the person taps one — Arin never switches
 * by itself.
 */
@Composable
fun AiChoices(vm: ChatViewModel, close: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.checkCcLogin() }
    Text(
        "AI for this chat",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    listOf(
        com.arin.llm.Provider.ClaudeCode to "Claude (your Claude Code login)",
        com.arin.llm.Provider.Claude to "Claude (API key)",
        com.arin.llm.Provider.ChatGPT to "ChatGPT",
        com.arin.llm.Provider.Gemini to "Gemini",
        com.arin.llm.Provider.Free to "Free models",
    ).forEach { (p, label) ->
        val on = vm.provider == p
        val note = when {
            p == com.arin.llm.Provider.ClaudeCode && !vm.ccConnected -> " · sign in in the Claude Code tab"
            p.needsKey && !vm.hasKey(p) -> " · no key"
            else -> ""
        }
        DropdownMenuItem(
            text = {
                Text(
                    (if (on) "✓ " else "") + label + note,
                    color = if (on) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
            },
            onClick = { if (!on) vm.chooseProvider(p); close() },
        )
    }
    HorizontalDivider()
}

/**
 * The same API and model switcher as the chat tab, for every tab: your saved Custom APIs (switch with one tap, edit,
 * or add another) and the models of the one in use. It changes only when you tap it — never by itself.
 */
@Composable
fun ModelSwitcher(vm: ChatViewModel, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var addApi by remember { mutableStateOf(false) }
    var editApi by remember { mutableStateOf<com.arin.data.SavedApi?>(null) }
    if (addApi || editApi != null) com.arin.settings.ApiForm(vm, editApi, onClose = { addApi = false; editApi = null })
    Box(modifier) {
        Surface(onClick = { menu = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.modelLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Choose API and model", modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            AiChoices(vm) { menu = false }
            Text(
                "Your APIs",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            vm.savedApis.forEach { api ->
                val inUse = vm.currentSavedApi()?.slotId == api.slotId
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(api.name + if (inUse) "  ✓" else "")
                            Text(
                                (api.model.ifBlank { com.arin.llm.CustomApi.host(api.baseUrl) }) + if (vm.hasKeyFor(api)) "" else " · no key",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = { vm.useSavedApi(api); menu = false },
                    trailingIcon = { TextButton(onClick = { editApi = api; menu = false }) { Text("Edit") } },
                )
            }
            DropdownMenuItem(text = { Text("+ Add API", color = MaterialTheme.colorScheme.primary) }, onClick = { addApi = true; menu = false })
            HorizontalDivider()
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
}
