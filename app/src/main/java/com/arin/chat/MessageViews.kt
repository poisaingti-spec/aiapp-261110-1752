package com.arin.chat

import com.arin.builder.BuildEvent.Kind as StepKind

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import com.arin.artifact.Artifact
import com.arin.artifact.ArtifactCard
import com.arin.artifact.ArtifactViewer
import com.arin.artifact.Artifacts
import com.arin.data.Attachment
import com.arin.data.Message
import com.arin.data.Source
import com.arin.design.ArinMark
import com.arin.design.ImageThumb
import com.arin.design.ImageViewer
import com.arin.design.LocalArinExtras
import com.arin.design.MarkdownText

/** The person's message: a teal bubble on the right, attachments above it, and Edit below. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UserMessage(message: Message, onEdit: (() -> Unit)?) {
    val extras = LocalArinExtras.current
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalAlignment = Alignment.End) {
        val photos = message.attachments.filter { it.kind == Attachment.IMAGE }
        if (photos.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                photos.forEach { p -> ImageThumb(p.path, if (photos.size == 1) 180.dp else 96.dp) { viewing = p.path } }
            }
        }
        message.attachments.filterNot { it.kind == Attachment.IMAGE }.forEach { FileChip(it, onRemove = null) }
        if (message.text.isNotBlank()) {
            Surface(
                color = extras.userBubble,
                contentColor = extras.onUserBubble,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            ) {
                SelectionContainer {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (onEdit != null) TextButton(onClick = onEdit) { Text("Edit") }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

/** Arin's reply: full width under the Arin mark, with Copy and (for the last reply) Retry. */
@Composable
fun ArinMessage(
    message: Message,
    showThinking: Boolean,
    isLast: Boolean,
    onRetry: () -> Unit,
    onSummary: (() -> Unit)? = null,
    /** Set on a reply that was cut off (crash, closed app, lost connection): Resume / Start over buttons. */
    onResume: (() -> Unit)? = null,
    onStartOver: (() -> Unit)? = null,
    onConfirmBuild: ((BuildTarget) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth()) {
        ArinMark(size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showThinking && message.thinking.isNotBlank()) {
                ThinkingPanel(message.thinking, message.thoughtSeconds, live = false, who = message.by)
                Spacer(Modifier.size(8.dp))
            }
            message.commands.forEach { CommandCard(it) }
            if (message.failed) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
            } else if (onSummary != null && message.text.startsWith("🛠️") && com.arin.builder.BuildRunner.anyRunning && !message.text.contains("— steps**")) {
                // Only a build's own progress message turns into the one-line step view; ordinary replies always show their text.
                // A build in progress: shown right away with its summary and what is really happening, with Minimise / Maximise.
                BuildProgressPanel(onSummary, message.text, message.id)
            } else {
                SelectionContainer { MarkdownText(message.text) }
                val artifacts = remember(message.text) { Artifacts.find(message.text) }
                var open by remember { mutableStateOf<Artifact?>(null) }
                artifacts.forEach { art -> ArtifactCard(art) { open = art } }
                open?.let { ArtifactViewer(it) { open = null } }
                if (message.media.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    com.arin.media.MediaRow(message.media)
                }
                if (message.sources.isNotEmpty()) Sources(message.sources)
            }
            if (onConfirmBuild != null && message.pendingBuild.isNotBlank()) {
                if (message.pendingBuild.startsWith("update\u0000")) {
                    androidx.compose.material3.Button(onClick = { onConfirmBuild(BuildTarget.CHAT) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Update Arin now")
                    }
                } else if (message.pendingBuild.startsWith("where\u0000")) {
                    // The code is written: where to build it.
                    com.arin.selffix.WhereToBuild(onPick = { w ->
                        onConfirmBuild(when (w) {
                            "phone" -> BuildTarget.PHONE; "codespace" -> BuildTarget.CODESPACE; "buildtab" -> BuildTarget.BUILD_TAB
                            "auto" -> BuildTarget.AUTO; else -> BuildTarget.CHAT
                        })
                    }, showChat = false)
                } else {
                    // Write the code first; where to build is asked once it's written.
                    androidx.compose.material3.Button(onClick = { onConfirmBuild(BuildTarget.AUTO) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("✍️ Write the code")
                    }
                }
            }
            if (onResume != null || onStartOver != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (onResume != null) androidx.compose.material3.Button(onClick = onResume) { Text("Resume") }
                    if (onStartOver != null) androidx.compose.material3.OutlinedButton(onClick = onStartOver) { Text("Start over") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onSummary != null) TextButton(onClick = onSummary) { Text("Summary") }
                if (!message.failed) {
                    val context = LocalContext.current
                    TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text("Copy") }
                    TextButton(onClick = { com.arin.voice.ReadAloud.toggle(context, message.id, message.text) }) {
                        Text(if (com.arin.voice.ReadAloud.speakingId == message.id) "Stop" else "Listen")
                    }
                    TextButton(onClick = {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message.text)
                            context.startActivity(Intent.createChooser(send, "Share reply").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("Share") }
                }
                if (isLast) TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** A terminal command and its output, folded to one line until tapped. */
@Composable
fun CommandCard(run: com.arin.data.CommandRun) {
    if (run.kind == "steer") {
        // A message the person sent while Arin was working, shown where Arin picked it up.
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Top) {
            Text("↪ ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(
                "You: " + run.command,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    val failed = run.ran && run.exitCode != 0
    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    !run.ran -> "⊘"
                    failed -> "✗"
                    else -> "✓"
                },
                color = when {
                    !run.ran -> MaterialTheme.colorScheme.onSurfaceVariant
                    failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$ " + run.command.lineSequence().first(),
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (open) " ▴" else " ▾", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            Spacer(Modifier.size(6.dp))
            if (run.command.contains('\n')) {
                Text(run.command, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                Spacer(Modifier.size(6.dp))
            }
            SelectionContainer {
                Text(
                    run.output.take(20_000) + if (run.ran) "\n[exit code ${run.exitCode ?: "?"}]" else "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Web pages a reply drew on; tap one to open it in the browser. */
@Composable
private fun Sources(sources: List<Source>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sources.forEachIndexed { i, src ->
            Text(
                "${i + 1}. ${src.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(src.url))) }
                }.padding(vertical = 4.dp),
            )
        }
    }
}

/** The reply as it streams in. */
@Composable
fun LiveReply(
    text: String,
    thinking: String,
    showThinking: Boolean,
    status: String?,
    commands: List<com.arin.data.CommandRun> = emptyList(),
    onSummary: (() -> Unit)? = null,
    who: String = "",
) {
    Row(Modifier.fillMaxWidth()) {
        ArinMark(size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showThinking && thinking.isNotBlank()) {
                ThinkingPanel(thinking, 0, live = text.isEmpty(), who = who)
                Spacer(Modifier.size(8.dp))
            }
            commands.forEach { CommandCard(it) }
            when {
                status != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                text.isNotEmpty() -> MarkdownText(text)
                thinking.isEmpty() || !showThinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Arin is thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onSummary != null && commands.any { it.kind != "steer" }) TextButton(onClick = onSummary) { Text("Summary") }
        }
    }
}

/** The steps a chat reply took (commands, installs, file reads/writes/edits, tool calls), for the Summary sheet. */
fun chatSteps(runs: List<com.arin.data.CommandRun>, finished: Boolean, failed: Boolean = false): List<Pair<String, com.arin.builder.BuildEvent.Kind>> {
    val out = runs.map { r ->
        val label = r.command.lineSequence().first().trim()
        when {
            r.kind == "steer" -> "You said: ${label.take(160)}" to StepKind.THINKING
            !r.ran -> "Skipped (you didn't allow it)" to StepKind.ERROR
            r.exitCode != null && r.exitCode != 0 -> (plainStep(r, label).first + " — didn't work") to StepKind.ERROR
            label.startsWith("install ") -> "Installing ${label.removePrefix("install ")}" to StepKind.LIBRARY
            label.startsWith("write ") -> "Writing ${filePart(label.removePrefix("write "))}" to StepKind.FILE
            label.startsWith("edit ") -> "Editing ${filePart(label.removePrefix("edit "))}" to StepKind.FILE
            label.startsWith("read ") -> "Reading ${filePart(label.removePrefix("read "))}" to StepKind.STEP
            label.startsWith("fetch ") -> "Reading a web page" to StepKind.WEB
            label.startsWith("download ") -> "Downloading ${label.removePrefix("download ")}" to StepKind.WEB
            label.startsWith("pictures: ") -> "Finding pictures: ${label.removePrefix("pictures: ")}" to StepKind.WEB
            label.startsWith("videos: ") -> "Finding videos: ${label.removePrefix("videos: ")}" to StepKind.WEB
            r.kind == "tool" -> plainTool(label)
            else -> plainStep(r, label)
        }
    }
    return if (finished) out + ((if (failed) "Stopped with an error" else "Done — the answer is in the chat") to (if (failed) StepKind.ERROR else StepKind.DONE)) else out
}

/** Short file name from a path (keeps the last folder for context). */
private fun filePart(path: String): String {
    val p = path.trim().trim('"', '\'').substringBefore(' ')
    val parts = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return parts.takeLast(2).joinToString("/").ifBlank { p }
}

/** An MCP tool call in plain words (tool names like open_binary → "Open binary"). */
private fun plainTool(label: String): Pair<String, StepKind> {
    if (label.startsWith("find_tools")) return "Looking up the right tool" to StepKind.STEP
    if (label.startsWith("recall:")) return "Remembering an earlier chat" to StepKind.STEP
    val name = label.substringBefore(' ').substringAfterLast('.').replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
    return name to StepKind.STEP
}

/**
 * Turns a shell command into a plain-English sentence for the Summary, so the person reads what Arin is doing
 * instead of raw code. Reads the meaningful part of a compound command (after cd … &&, before the first pipe).
 */
fun plainStep(r: com.arin.data.CommandRun, labelIn: String): Pair<String, StepKind> {
    var c = labelIn.trim()
    // Drop a leading "cd <path> &&" or "cd <path>;" — it just sets the folder.
    Regex("""^cd\s+\S+\s*(&&|;)\s*""").find(c)?.let { c = c.removeRange(it.range) }
    // The first real action in a chain (skip echo/cd/mkdir/rm-temp noise when something better follows).
    val parts = c.split(Regex("""\s*(&&|;)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }
    val main = parts.firstOrNull { p ->
        val w = p.substringBefore(' ')
        w !in setOf("echo", "cd", "true", "false", "set", "export") &&
            !(w == "mkdir") && !(w == "rm" && (p.contains("/tmp") || p.contains("-rf")))
    } ?: parts.firstOrNull() ?: c
    val head = main.substringBefore('|').trim()
    val word = head.substringBefore(' ').substringAfterLast('/')
    val rest = head.substringAfter(' ', "")
    fun arg() = rest.split(' ').lastOrNull { it.isNotBlank() && !it.startsWith('-') }?.let { filePart(it) }.orEmpty()
    val text = when (word) {
        "python3", "python", "python2" -> "Running a Python script"
        "node", "nodejs" -> "Running a script"
        "bash", "sh" -> "Running a script"
        "javac" -> "Compiling Java code"
        "java" -> "Running a Java tool"
        "kotlinc" -> "Compiling Kotlin code"
        "gcc", "g++", "cc", "make", "cmake" -> "Compiling code"
        "pip", "pip3" -> if (rest.contains("install")) "Installing a Python package" else "Using pip"
        "npm", "npx", "yarn", "pnpm" -> if (rest.contains("install") || rest.contains("i ")) "Installing a package" else "Running a Node tool"
        "apt", "apt-get", "pkg", "dpkg" -> if (rest.contains("install")) "Installing a tool" else "Updating the package list"
        "curl", "wget" -> "Downloading from the web"
        "git" -> when {
            rest.startsWith("clone") -> "Downloading a code repository"
            rest.startsWith("pull") -> "Updating a repository"
            rest.startsWith("commit") -> "Saving changes"
            rest.startsWith("push") -> "Uploading changes"
            else -> "Using git"
        }
        "grep", "rg", "egrep", "fgrep", "ag" -> {
            val inFile = rest.split(' ').lastOrNull { it.isNotBlank() && !it.startsWith('-') && !it.startsWith('"') }?.let { filePart(it) }
            val pat = Regex(""""([^"]{1,40})"""").find(rest)?.groupValues?.get(1)
            when {
                pat != null && inFile != null -> "Searching $inFile for \"$pat\""
                inFile != null -> "Searching $inFile"
                else -> "Searching for text"
            }
        }
        "ls", "find", "tree", "stat", "du", "wc" -> "Listing files" + (arg().takeIf { it.isNotEmpty() }?.let { " in $it" } ?: "")
        "cat", "head", "tail", "less", "nl", "sed", "awk", "jq" -> "Reading ${arg().ifBlank { "a file" }}"
        "cp" -> "Copying files"
        "mv" -> "Moving files"
        "rm" -> "Removing files"
        "mkdir" -> "Making a folder"
        "chmod", "chown" -> "Setting file permissions"
        "unzip", "jar", "tar", "7z", "unrar", "gzip", "gunzip" -> "Extracting files"
        "zip" -> "Packing files into an archive"
        "strings" -> "Scanning a file for text"
        "aapt", "aapt2" -> "Reading the app's details"
        "apktool" -> if (rest.trimStart().startsWith("b")) "Rebuilding the app" else "Unpacking the app"
        "keytool" -> "Making a signing key"
        "jarsigner", "apksigner" -> "Signing the app"
        "d2j-dex2jar", "dex2jar", "baksmali", "smali", "jadx", "d2j" -> "Reading the app's code"
        "adb" -> "Talking to a device over ADB"
        "ffmpeg", "ffprobe" -> "Converting media"
        "echo", "printf" -> "Writing a note"
        "" -> "Working"
        else -> "Running $word"
    }
    val kind = when (word) {
        "javac", "java", "kotlinc", "gcc", "g++", "cc", "make", "cmake", "apktool", "jarsigner", "apksigner", "keytool" -> StepKind.BUILD
        "pip", "pip3", "npm", "npx", "yarn", "apt", "apt-get", "pkg" -> StepKind.LIBRARY
        "curl", "wget", "git" -> StepKind.WEB
        "unzip", "tar", "zip", "jar", "7z" -> StepKind.DOWNLOAD
        else -> StepKind.STEP
    }
    return text to kind
}

/** The model's reasoning: open while it streams, then folded to "Reasoned for N s" (tap to open). */
@Composable
fun ThinkingPanel(text: String, seconds: Int, live: Boolean, who: String = "") {
    var open by remember(live) { mutableStateOf(live) }
    val extras = LocalArinExtras.current
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
            .clickable(enabled = !live) { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    live -> if (who.isNotBlank()) "$who is reasoning…" else "Reasoning…"
                    seconds > 0 -> (if (who.isNotBlank()) "$who reasoned" else "Reasoned") + " for $seconds s"
                    else -> if (who.isNotBlank()) "$who's reasoning" else "Reasoning"
                } + if (live) "" else if (open) "  ▴" else "  ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Text(
                if (live) text.takeLast(1500) else text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = extras.subtle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** An attached file (or, in the message box, a photo). With [onRemove], shows an × to take it off the next message. */
@Composable
fun FileChip(file: Attachment, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 260.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, end = if (onRemove == null) 10.dp else 0.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (file.kind) {
                Attachment.IMAGE -> ImageThumb(file.path, 36.dp)
                Attachment.PDF -> Text("📕", style = MaterialTheme.typography.titleMedium)
                Attachment.FILE -> Text(
                    when {
                        file.mime.startsWith("audio/") -> "🎵"
                        file.mime.startsWith("video/") -> "🎬"
                        else -> "📦"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                else -> Text("📄", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(file.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (file.kind) {
                        Attachment.IMAGE -> "Photo"
                        Attachment.PDF -> "PDF · ${file.pages} page${if (file.pages == 1) "" else "s"}" +
                            if (file.size > 0) " · ${com.arin.data.Attachments.readableSize(file.size)}" else ""
                        Attachment.FILE -> listOf(file.mime.substringBefore('/').ifBlank { "File" }.replaceFirstChar { it.uppercase() },
                            com.arin.data.Attachments.readableSize(file.size)).joinToString(" · ")
                        else -> listOfNotNull(
                            file.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() && it.length <= 5 },
                            if (file.size > 0) com.arin.data.Attachments.readableSize(file.size)
                            else "${(maxOf(file.chars, file.text.length.toLong())) / 1000 + 1}k characters",
                        ).joinToString(" · ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onRemove != null) TextButton(onClick = onRemove) { Text("✕") }
        }
    }
}


/** The build's current step on one line, with moving dots, opening the Summary when tapped. */
@Composable
private fun BuildNowLine(onOpen: () -> Unit, progressText: String) {
    // This message's own build (several can run at once): its newest step is the last "- " line of its progress text.
    val step = progressText.lines().lastOrNull { it.startsWith("- ") }?.removePrefix("- ")?.substringAfter(' ')?.trim()?.take(120)
        ?.ifBlank { null } ?: "Starting the build"
    val accent = androidx.compose.ui.graphics.Color(0xFFD97757)
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable<Float>(
            androidx.compose.animation.core.tween<Float>(1200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "phase",
    )
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Four dots in a square; the lit one goes round.
        androidx.compose.foundation.layout.Box(Modifier.size(18.dp)) {
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1).forEachIndexed { i, (cx, cy) ->
                val on = phase.toInt() % 4 == i
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .padding(start = (cx * 10).dp, top = (cy * 10).dp)
                        .size(if (on) 7.dp else 5.dp)
                        .androidx_background(accent.copy(alpha = if (on) 1f else 0.45f)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(step, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false), maxLines = 2)
        Text("  ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The build's progress, shown in the chat as soon as the build starts: a summary of the steps so far, the step running
 * now, and (Maximise) every line of what the models and the build are actually doing, live. Minimise folds it to one line.
 */
@Composable
private fun BuildProgressPanel(onOpen: () -> Unit, progressText: String, key: String) {
    // 0 = minimised (one line), 1 = summary (default), 2 = maximised (summary + live process).
    var mode by androidx.compose.runtime.saveable.rememberSaveable(key) { mutableStateOf(1) }
    val accent = androidx.compose.ui.graphics.Color(0xFFD97757)
    val started = androidx.compose.runtime.saveable.rememberSaveable(key) { System.currentTimeMillis() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
    fun ago(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s" }
    val lines = progressText.lines()
    val steps = lines.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
    val earlier = lines.firstOrNull { it.startsWith("_…") && it.contains("earlier steps") }?.trim('_')
    val doing = lines.lastOrNull { it.startsWith("_") && it.endsWith("_") && !it.contains("earlier steps") }?.trim('_')?.trim()
        ?.ifBlank { null }
    val current = doing ?: steps.lastOrNull()?.substringAfter(' ')?.trim()?.ifBlank { null } ?: "Starting the build"
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
            // Header: what's happening now, the clock, and the Minimise / Maximise buttons.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛠️ Build progress", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { mode = if (mode == 0) 1 else 0 }) { Text(if (mode == 0) "▢ Restore" else "— Minimise") }
                TextButton(onClick = { mode = if (mode == 2) 1 else 2 }) { Text(if (mode == 2) "⤡ Smaller" else "⤢ Maximise") }
            }
            BuildNowLine(onOpen, if (steps.isEmpty() && doing != null) "- • $doing" else progressText)
            if (mode == 0) return@Column
            Text(
                "⏱ building for ${ago(now - started)}" + com.arin.builder.LiveFeed.lastAt.takeIf { it > 0 }
                    ?.let { " · last activity ${ago(now - it)} ago" }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text("Summary", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp, bottom = 2.dp))
            if (earlier != null) Text(earlier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (steps.isEmpty()) Text("Starting the build…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEachIndexed { i, s ->
                val last = i == steps.lastIndex
                Text(
                    s,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (last) accent else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (mode == 2) 6 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            Text(
                "Now: $current",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (mode == 2) {
                // Everything that is really going on, word for word (models' thinking, searches, commands, files, build steps).
                Text("What's happening — live", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                val feed = com.arin.builder.LiveFeed.lines
                val feedList = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(feed.size) { if (feed.isNotEmpty()) feedList.scrollToItem(feed.lastIndex) }
                val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = feedList,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    ) {
                        if (feed.isEmpty()) item { Text("Lines appear here as soon as the build starts working.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(feed.size) { i ->
                            val l = feed[i]
                            Text(
                                fmt.format(java.util.Date(l.time)) + "  " + l.who + "  " + l.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                color = when {
                                    l.who.startsWith("Claude") -> accent
                                    l.who == "…" -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.androidx_background(c: androidx.compose.ui.graphics.Color) =
    this.background(c, androidx.compose.foundation.shape.CircleShape)

/** The build-location chooser shown under a prepared build: pick where to build it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BuildWhere(onPick: (BuildTarget) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("Where should I build it?", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = { onPick(BuildTarget.CHAT) }) { Text("In chat") }
            androidx.compose.material3.OutlinedButton(onClick = { onPick(BuildTarget.BUILD_TAB) }) { Text("Build tab") }
            androidx.compose.material3.OutlinedButton(onClick = { onPick(BuildTarget.PHONE) }) { Text("On phone") }
            androidx.compose.material3.OutlinedButton(onClick = { onPick(BuildTarget.CODESPACE) }) { Text("Codespace") }
            androidx.compose.material3.OutlinedButton(onClick = { onPick(BuildTarget.AUTO) }) { Text("Let Arin decide") }
        }
    }
}
