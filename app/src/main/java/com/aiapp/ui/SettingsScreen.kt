package com.aiapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "API keys are stored encrypted on this phone only.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ProviderId.entries.filter { it.needsKey && it != ProviderId.CUSTOM }.forEach { KeyField(it, vm) }
            CustomApiSection(vm)
            GithubTokenField(vm)
            OfflineSection(vm)
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
    ProviderId.OPEN -> "openrouter.ai/keys (one key for Kimi, DeepSeek, GLM, Qwen; some are free)"
    ProviderId.CUSTOM -> com.aiapp.llm.CustomEndpoint.presetFor()?.keyPage ?: "the site you entered above"
    ProviderId.OFFLINE -> ""
}

@Composable
private fun KeyField(p: ProviderId, vm: ChatViewModel) {
    var text by remember(p) { mutableStateOf(vm.key(p)) }
    var note by remember(p) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                note = null
            },
            label = { Text("${p.label} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveKey(p, text)
                note = "Saved"
            }) { Text("Save") }
            TextButton(onClick = {
                text = ""
                vm.saveKey(p, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

/** Any OpenAI-compatible site: base URL (typed or a preset), its own key, and an optional model id. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomApiSection(vm: ChatViewModel) {
    var base by remember { mutableStateOf(vm.customBase()) }
    var urlText by remember { mutableStateOf(base) }
    var key by remember(base) { mutableStateOf(vm.key(ProviderId.CUSTOM)) }
    var modelId by remember(base) { mutableStateOf(vm.customModel()) }
    var note by remember(base) { mutableStateOf<String?>(null) }

    fun useUrl(url: String) {
        vm.saveCustomBase(url)
        base = vm.customBase()
        urlText = base
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Custom API (any OpenAI-compatible site)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use any service that speaks the OpenAI chat format. Pick one or paste its base URL; " +
                "each site keeps its own key. Then choose \"Custom API\" in the model picker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            com.aiapp.llm.CustomEndpoint.presets.forEach { preset ->
                androidx.compose.material3.FilterChip(
                    selected = base == preset.baseUrl,
                    onClick = { useUrl(preset.baseUrl) },
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
        Button(
            onClick = { useUrl(urlText) },
            enabled = urlText.isNotBlank() && com.aiapp.llm.CustomEndpoint.normalize(urlText) != base,
        ) { Text("Use this URL") }
        Text(
            "Now using $base",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = key,
            onValueChange = {
                key = it
                note = null
            },
            label = { Text("API key for ${com.aiapp.llm.CustomEndpoint.label(base)}") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(ProviderId.CUSTOM)}" +
                if (base.contains("tokenharbor.ai")) ". For free models, turn on :free routes in the dashboard." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveKey(ProviderId.CUSTOM, key)
                note = "Saved"
            }) { Text("Save") }
            TextButton(onClick = {
                key = ""
                vm.saveKey(ProviderId.CUSTOM, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }

        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = { Text("Model id (optional)") },
            placeholder = { Text("e.g. deepseek-v4.1-flash:free") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Only needed if the site has no model list, or to use a model that isn't listed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.saveCustomModel(modelId) }) { Text("Save model id") }
    }
}

@Composable
private fun OfflineSection(vm: ChatViewModel) {
    val recommended = vm.recommendedOffline()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Offline models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run on this phone with no internet or API key. Your phone has about ${vm.deviceRamGb()} GB " +
                "of RAM, so ${recommended.name} is recommended. Keep the app open while it downloads; " +
                "an interrupted download resumes where it stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.offlineModels().forEach { OfflineRow(it, vm) }
        AddOfflineModel(vm)
    }
}

@Composable
private fun OfflineRow(m: OfflineModel, vm: ChatViewModel) {
    val progress = vm.downloads[m.id]
    val downloaded = vm.offlineDownloaded(m)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                downloaded -> "Downloaded · ready offline"
                progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                m.approxSizeGb == 0.0 -> "From huggingface.co/${m.repo}"
                else -> "About ${m.approxSizeGb} GB · needs ${m.minRamGb} GB+ RAM"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                downloaded -> {
                    Button(onClick = { vm.useOffline(m) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Delete") }
                }
                progress != null -> OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                else -> {
                    Button(onClick = { vm.downloadOffline(m) }) { Text("Download") }
                    if (m.id.startsWith("hf-")) OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Remove") }
                }
            }
        }
        vm.downloadErrors[m.id]?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GithubTokenField(vm: ChatViewModel) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdvanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(vm.githubClientId()) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("GitHub (for Build app)", style = MaterialTheme.typography.titleMedium)
        val code = vm.githubCode
        when {
            vm.githubToken().isNotBlank() -> {
                Text(
                    "Signed in as ${vm.githubUser.ifBlank { "your GitHub account" }}. " +
                        "New apps get their own repo on your account automatically.",
                )
                OutlinedButton(onClick = vm::signOutGithub) { Text("Sign out") }
            }
            code != null -> {
                Text("Enter this code on GitHub, then come back:")
                Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode)) }) { Text("Copy code") }
                    OutlinedButton(onClick = { uri.openUri(code.verificationUri) }) { Text("Open GitHub") }
                }
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Button(onClick = { vm.signInGithub { link -> uri.openUri(link) } }) { Text("Sign in with GitHub") }
                Text(
                    "You approve once on github.com; the app can then create repos and build APKs for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        vm.githubLoginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

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
                "From github.com/settings/developers → OAuth Apps (tick \"Enable Device Flow\").",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.saveGithubClientId(clientId.trim()) }) { Text("Save Client ID") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Or paste a personal access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Classic token with public_repo and workflow scopes, from github.com/settings/tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                vm.saveGithubToken(token)
                token = ""
            }, enabled = token.isNotBlank()) { Text("Save token") }
        }
    }
}

@Composable
private fun AddOfflineModel(vm: ChatViewModel) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf(vm.hfToken()) }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a model", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a Hugging Face link to any LiteRT-LM model (.litertlm), for example from huggingface.co/litert-community.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            label = { Text("Hugging Face link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            error = vm.addOfflineModel(link)
            if (error == null) link = ""
        }, enabled = link.isNotBlank()) { Text("Add and download") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                tokenNote = null
            },
            label = { Text("Hugging Face token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Only needed for models that ask you to accept a licence. Create one at huggingface.co/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveHfToken(token)
                tokenNote = "Saved"
            }) { Text("Save token") }
            tokenNote?.let { Text("  $it", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
