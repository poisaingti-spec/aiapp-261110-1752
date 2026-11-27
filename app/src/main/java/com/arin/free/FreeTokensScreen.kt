package com.arin.free

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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.llm.ApiFormat
import com.arin.llm.Provider

/** The "Free tokens" tab: every free way to power Arin, in one place. Keyless first, then one-key tiers. */
@Composable
fun FreeTokensScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Free tokens", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            com.arin.selffix.ModelSwitcher(vm)
            com.arin.selffix.AiHelperButton(vm) // ARIN-PROTECTED: ai-helper in every tab
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Free AI for Arin. The keyless option works right away with nothing to set up. " +
                    "Add each provider's free key once (no card for most). Arin pools them under \"Free\" with automatic fallover, and each is also pickable in any chat. Stack several to reach billions of free tokens a month.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FreeProviders.all.forEach { p -> FreeCard(vm, p, onBack) }
            Spacer(Modifier.height(4.dp))
            Text(
                "These are not Claude — there's no free Claude anywhere. Free tiers are rate-limited, so they're great for " +
                    "everyday use but not heavy all-day work. You add each key only once; getting a key is a quick, card-free signup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FreeCard(vm: ChatViewModel, p: FreeProvider, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val added = !p.keyless && vm.savedApis.any { it.baseUrl == p.baseUrl }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text(p.tokens, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(p.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (p.keyless) {
                Button(onClick = { vm.chooseProvider(Provider.Free); onBack() }) { Text("Use now (no key)") }
            } else {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); problem = null },
                    label = { Text(if (added) "Paste a new key to replace it" else "Paste your free ${p.name} key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { uri.openUri(p.keyPage) } }) { Text("Get free key") }
                    Button(
                        enabled = key.isNotBlank(),
                        onClick = {
                            problem = vm.saveApi(null, "${p.name} (free)", p.baseUrl, key, p.model, ApiFormat.OpenAI, free = true)
                            if (problem == null) onBack()
                        },
                    ) { Text(if (added) "Update key" else "Save & use") }
                }
                if (added) {
                    Text("✓ Added to your free pool — used under \"Free\", and pickable in any chat's model menu.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = { vm.savedApis.firstOrNull { it.baseUrl == p.baseUrl }?.let { vm.removeSavedApi(it) } }) { Text("Remove") }
                }
            }
        }
    }
}
