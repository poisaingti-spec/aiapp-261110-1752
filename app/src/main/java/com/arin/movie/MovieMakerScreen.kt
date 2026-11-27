package com.arin.movie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel

/**
 * Movie Maker: one box in, a film out. Your own model plans the film (characters, shots, dialogue);
 * free tools render it in Arin's Linux; optional real motion uses your own free Hugging Face GPU quota.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MovieMakerScreen(vm: ChatViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshMovie() }
    val colors = MaterialTheme.colorScheme
    // Explicit colours so the box is readable in both light and dark themes.
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.onSurface,
        unfocusedTextColor = colors.onSurface,
        focusedPlaceholderColor = colors.onSurfaceVariant,
        unfocusedPlaceholderColor = colors.onSurfaceVariant,
        cursorColor = colors.primary,
        focusedBorderColor = colors.primary,
        unfocusedBorderColor = colors.outline,
        focusedContainerColor = colors.surface,
        unfocusedContainerColor = colors.surface,
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.onBackground) }
            Column {
                Text("Movie Maker", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                // Which model is directing — the one picked in chat, shown like the chat header.
                Text("Director: ${vm.movieDirectorLabel}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                com.arin.selffix.ModelSwitcher(vm) // switch the director's API or model (only when you tap)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            com.arin.selffix.AiHelperButton(vm) // ARIN-PROTECTED: ai-helper in every tab
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Describe a film or paste a script. Your model writes the characters, shots and dialogue; " +
                    "Arin makes a photoreal picture and voice for every shot, adds real motion when free GPU is " +
                    "available, checks each shot, and cuts it all into one video with subtitles.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )

            val script = vm.movieScript
            OutlinedTextField(
                value = script,
                onValueChange = { vm.movieScript = it },
                placeholder = { Text("e.g. A short film about a lighthouse keeper who befriends a storm…") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
            )
            com.arin.selffix.ActivityBar("movie") // what Arin is doing (helper help marked 🟣)
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.arin.selffix.AttachButton(onUris = { vm.movieAttach(it) }, onProblem = { vm.notice = it })
                Text("Attach a script, photos, videos or any file", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Button(onClick = { vm.makeMovie(script) }, enabled = !vm.movieBusy && script.isNotBlank()) {
                Text(if (vm.movieBusy) "Working…" else "Make movie")
            }

            // ---- the saved film: resume / add motion / stop ----
            if (vm.movieHasProject) {
                Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🎬 ${vm.movieTitle.ifBlank { "Current film" }}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Saved on your phone. Finished shots are kept, so you can close Arin and continue later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vm.movieBusy) {
                                OutlinedButton(onClick = { vm.stopMovie() }) { Text("Stop") }
                            } else {
                                OutlinedButton(onClick = { vm.resumeMovie() }) { Text("Resume") }
                                OutlinedButton(onClick = { vm.addMotionToMovie() }) { Text("Add motion") }
                            }
                        }
                    }
                }
            }

            vm.movieError?.let { err ->
                Surface(color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Text(err, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (vm.movieBusy || vm.movieProgress.isNotEmpty() || vm.movieLog.isNotEmpty()) {
                Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Progress", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            vm.movieFraction?.takeIf { vm.movieBusy }?.let {
                                Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            }
                        }
                        if (vm.movieBusy) {
                            Spacer(Modifier.height(6.dp))
                            Text(vm.movieStep, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            val f = vm.movieFraction
                            if (f != null) {
                                LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().height(6.dp))
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                            }
                            if (vm.movieActivity.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(vm.movieActivity, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        vm.movieLog.takeLast(4).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (vm.movieProgress.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(vm.movieProgress, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (vm.movieBusy) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Keeps going if you leave Arin or turn the screen off — progress is in your notifications.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (vm.movieBusy || vm.movieHasProject || vm.movieChat.isNotEmpty()) {
                DirectorChat(vm, fieldColors)
            }

            if (vm.movieReady) {
                Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (vm.moviePartial) "Your movie is ready — some shots are missing" else "Your movie is ready 🎬",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        vm.movieSummary?.let { s ->
                            Text(
                                "${s.shots - s.missing} shots · ${s.seconds.toInt()} s · ${s.motion} with real motion" +
                                    (if (s.missing > 0) " · ${s.missing} missing (tap Resume to retry)" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { vm.playMovie() }) { Text("Play") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { vm.shareMovie() }) { Text("Share / Save") }
                        }
                    }
                }
            }

            MotionSettings(vm, fieldColors)

            Text(
                "Real motion is optional and uses your own free Hugging Face GPU time (only a few clips a day). " +
                    "When it runs out, shots get a camera move over the picture instead, and Add motion upgrades them " +
                    "later. Faces stay similar but not identical between shots — no free tool can guarantee that yet. " +
                    "Nothing paid is ever used.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MotionSettings(vm: ChatViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Real motion & subtitles", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (vm.movieHfToken.isBlank()) "Off — pictures with camera moves" else "On — free Hugging Face GPU",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (open) {
                OutlinedTextField(
                    value = vm.movieHfToken,
                    onValueChange = { vm.updateMovieHfToken(it) },
                    label = { Text("Hugging Face token (free account)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { uri.openUri("https://huggingface.co/settings/tokens") }) { Text("Get a free token (Read access is enough)") }
                OutlinedTextField(
                    value = vm.movieSpace,
                    onValueChange = { vm.updateMovieSpace(it) },
                    label = { Text("Video Space") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Image-to-video Space (Wan 2.2). Change it if this one goes offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Burn subtitles into the video", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = vm.movieSubtitles, onCheckedChange = { vm.updateMovieSubtitles(it) })
                }
            }
        }
    }
}

/** Talk to the director while the film is being made: notes steer the shots that aren't filmed yet. */
@Composable
private fun DirectorChat(vm: ChatViewModel, fieldColors: androidx.compose.material3.TextFieldColors) {
    val colors = MaterialTheme.colorScheme
    var note by rememberSaveable { mutableStateOf("") }
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurface, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Director chat", style = MaterialTheme.typography.titleSmall)
            Text(
                "Steer the film any time — e.g. “make it night”, “more close-ups of Mara”, “end on the sea”. " +
                    "Changes apply to shots not filmed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            vm.movieChat.takeLast(8).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (line.fromUser) colors.primaryContainer else colors.surface,
                        contentColor = if (line.fromUser) colors.onPrimaryContainer else colors.onSurface,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(line.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (vm.movieSteering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(vm.movieActivity.ifBlank { "${vm.movieDirectorLabel} is rewriting the shots…" }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Message the director") },
                    colors = fieldColors,
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { vm.sendMovieNote(note); note = "" },
                    enabled = note.isNotBlank() && !vm.movieSteering,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (note.isNotBlank()) colors.primary else colors.onSurfaceVariant) }
            }
        }
    }
}
