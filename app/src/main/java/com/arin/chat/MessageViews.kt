package com.arin.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
fun ArinMessage(message: Message, showThinking: Boolean, isLast: Boolean, onRetry: () -> Unit, onSummary: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth()) {
        ArinMark(size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showThinking && message.thinking.isNotBlank()) {
                ThinkingPanel(message.thinking, message.thoughtSeconds, live = false)
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
) {
    Row(Modifier.fillMaxWidth()) {
        ArinMark(size = 22.dp, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (showThinking && thinking.isNotBlank()) {
                ThinkingPanel(thinking, 0, live = text.isEmpty())
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
        }
    }
}

/** The model's reasoning: open while it streams, then folded to "Reasoned for N s" (tap to open). */
@Composable
fun ThinkingPanel(text: String, seconds: Int, live: Boolean) {
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
                    live -> "Reasoning…"
                    seconds > 0 -> "Reasoned for $seconds s"
                    else -> "Reasoning"
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
