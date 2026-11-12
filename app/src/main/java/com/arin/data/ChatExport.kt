package com.arin.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Totals for a chat. */
data class ChatStats(val messages: Int, val words: Int, val readingMinutes: Int)

/**
 * Turns a chat into something people can keep or send: a Markdown file, or plain text.
 * Ported from the ChatKeeper demo app.
 */
object ChatExport {
    /** Average adult reading speed, in words per minute. */
    private const val WORDS_PER_MINUTE = 220

    fun stats(messages: List<Message>): ChatStats {
        val words = messages.sumOf { m -> m.text.split(Regex("\\s+")).count { it.isNotBlank() } }
        val minutes = if (words == 0) 0 else maxOf(1, (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE)
        return ChatStats(messages.size, words, minutes)
    }

    fun toMarkdown(title: String, messages: List<Message>, at: Date = Date()): String {
        val s = stats(messages)
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(at)
        return buildString {
            append("# ").append(title.ifBlank { "Conversation" }).append("\n\n")
            append("_Exported from Arin, ").append(date).append(" · ")
            append(s.messages).append(" messages · ").append(s.words).append(" words · about ")
            append(s.readingMinutes).append(" min read_\n\n")
            messages.filterNot { it.failed }.forEach { m ->
                append("---\n\n")
                append("**").append(if (m.fromUser) "You" else "Arin").append("**\n\n")
                if (m.attachments.isNotEmpty()) {
                    append("_Attached: ").append(m.attachments.joinToString { it.name }).append("_\n\n")
                }
                append(m.text.trim()).append("\n\n")
                if (m.sources.isNotEmpty()) {
                    append("Sources:\n")
                    m.sources.forEach { append("- [").append(it.title).append("](").append(it.url).append(")\n") }
                    append('\n')
                }
            }
        }
    }

    fun toPlainText(title: String, messages: List<Message>): String = buildString {
        append(title.ifBlank { "Conversation" }).append("\n\n")
        messages.filterNot { it.failed }.forEach { m ->
            append(if (m.fromUser) "You" else "Arin").append(":\n")
            append(m.text.trim()).append("\n\n")
        }
    }

    /** Letters, digits, spaces and dashes only; at most 60 characters; never empty. */
    fun fileName(title: String, extension: String): String {
        val base = title.replace(Regex("[^\\p{L}\\p{N} -]"), "").trim().replace(Regex("\\s+"), "-").take(60).trimEnd('-')
        return base.ifEmpty { "conversation" } + "." + extension
    }

    /** Writes the chat as a Markdown file and opens the share sheet (Drive, WhatsApp, Files and so on). */
    fun shareMarkdown(context: Context, title: String, messages: List<Message>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() } // keep only the latest export
        val file = File(dir, fileName(title, "md"))
        file.writeText(toMarkdown(title, messages))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export “$title”").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
