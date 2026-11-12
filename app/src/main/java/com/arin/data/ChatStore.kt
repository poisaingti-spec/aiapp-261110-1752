package com.arin.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A file attached to a message. Text files keep their text; photos and PDFs keep a copy in
 * the app's private storage at [path], so the model can be sent them again later.
 */
@Serializable
data class Attachment(
    val name: String,
    val text: String = "",
    val kind: String = TEXT,
    val path: String = "",
    val mime: String = "",
    val size: Long = 0,
    val pages: Int = 0,
    /** Full extracted text, kept in a file so any size fits. [text] then holds only a preview. */
    val textPath: String = "",
    /** Length of the full text, in characters. */
    val chars: Long = 0,
    /** A copy in Download/Arin that terminal tools (Termux, REA) can open; empty if none. */
    val sharedPath: String = "",
) {
    val isMedia: Boolean get() = kind == IMAGE || kind == PDF

    /** Whether there is text the model can read (from a text file, a document, or a PDF). */
    val hasText: Boolean get() = textPath.isNotEmpty() || (kind == TEXT && text.isNotEmpty())

    companion object {
        const val TEXT = "text"
        const val IMAGE = "image"
        const val PDF = "pdf"
        /** Any other file (audio, video, apps…): attached by name, type and size. */
        const val FILE = "file"
    }
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val thinking: String = "",
    val thoughtSeconds: Int = 0,
    val attachments: List<Attachment> = emptyList(),
    /** Set when the reply failed; the text then holds the error to show. */
    val failed: Boolean = false,
    /** Web pages the reply drew on. */
    val sources: List<Source> = emptyList(),
    /** Terminal commands the reply ran, in order. */
    val commands: List<CommandRun> = emptyList(),
    /** Pictures and videos shown with the reply. */
    val media: List<MediaItem> = emptyList(),
)

@Serializable
data class Source(val title: String, val url: String)

/** A picture or video shown in a reply (found on the web, or linked by the model). */
@Serializable
data class MediaItem(
    val video: Boolean,
    val url: String,
    val thumb: String,
    val title: String = "",
    val source: String = "",
)

/** A command Arin ran in the terminal while writing a reply, with what it printed. */
@Serializable
data class CommandRun(
    val command: String,
    val output: String,
    val exitCode: Int? = null,
    /** False when the person chose not to run it. */
    val ran: Boolean = true,
    /** "terminal" for a shell command, "tool" for an MCP tool call. */
    val kind: String = "terminal",
)

@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val updated: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val projectId: String? = null,
)

/** What the chats list needs, without loading every message. */
@Serializable
data class ChatSummary(val id: String, val title: String, val updated: Long, val projectId: String? = null)

/** Keeps each chat as a JSON file in the app's private storage, plus a small index for the list. */
class ChatStore(private val context: Context) {
    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun list(): List<ChatSummary> {
        val stored = runCatching { json.decodeFromString<List<ChatSummary>>(indexFile.readText()) }.getOrNull()
        return (stored ?: rebuildIndex()).sortedByDescending { it.updated }
    }

    fun load(id: String): Chat? = runCatching { json.decodeFromString<Chat>(File(dir, "$id.json").readText()) }.getOrNull()

    @Synchronized
    fun save(chat: Chat) {
        writeAtomically(File(dir, "${chat.id}.json"), json.encodeToString(chat))
        val rest = list().filterNot { it.id == chat.id }
        writeIndex(rest + ChatSummary(chat.id, chat.title, chat.updated, chat.projectId))
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val chat = load(id) ?: return
        save(chat.copy(title = title))
    }

    @Synchronized
    fun moveToProject(id: String, projectId: String?) {
        val chat = load(id) ?: return
        save(chat.copy(projectId = projectId))
    }

    /** Chats in a project lose their project when it's deleted; they stay in the main list. */
    @Synchronized
    fun detachProject(projectId: String) {
        list().filter { it.projectId == projectId }.forEach { moveToProject(it.id, null) }
    }

    @Synchronized
    fun delete(id: String) {
        load(id)?.let { chat -> Attachments.deleteFiles(chat.messages.flatMap { it.attachments }) }
        File(dir, "$id.json").delete()
        writeIndex(list().filterNot { it.id == id })
    }

    @Synchronized
    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        Attachments.deleteAllFiles(context)
    }

    /** Text search across titles and messages. */
    fun search(query: String): List<ChatSummary> {
        val q = query.trim()
        if (q.isEmpty()) return list()
        return list().filter { s ->
            s.title.contains(q, ignoreCase = true) ||
                load(s.id)?.messages?.any { it.text.contains(q, ignoreCase = true) } == true
        }
    }

    /**
     * The parts of saved chats that match [query], for the model to read: each matching message
     * with the message before and after it. Newest chats first; [exclude] is the current chat.
     */
    fun recall(query: String, exclude: String?, maxChars: Int = 12_000): String {
        val words = query.lowercase().split(Regex("""[^\p{L}\p{N}._-]+""")).filter { it.length >= 3 }.distinct()
        if (words.isEmpty()) return ""
        data class Hit(val score: Int, val text: String)
        val hits = mutableListOf<Hit>()
        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH)
        for (s in list()) {
            if (s.id == exclude) continue
            val chat = load(s.id) ?: continue
            val titleScore = words.count { s.title.contains(it, ignoreCase = true) } * 2
            val msgs = chat.messages.filterNot { it.failed }
            val scored = msgs.mapIndexed { i, m -> i to words.count { m.text.contains(it, ignoreCase = true) } }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
            if (scored.isEmpty() && titleScore == 0) continue
            val picked = (if (scored.isEmpty()) listOf(0, 1) else scored.flatMap { (i, _) -> listOf(i - 1, i, i + 1) })
                .filter { it in msgs.indices }.distinct().sorted()
            val body = picked.joinToString("\n") { i ->
                val m = msgs[i]
                (if (m.fromUser) "Person: " else "Arin: ") + m.text.replace(Regex("\\s+"), " ").take(700)
            }
            hits += Hit(
                titleScore + scored.sumOf { it.second },
                "### Chat \"${s.title}\" (${date.format(java.util.Date(s.updated))})\n$body",
            )
        }
        if (hits.isEmpty()) return ""
        val out = StringBuilder()
        for (h in hits.sortedByDescending { it.score }.take(6)) {
            if (out.length + h.text.length > maxChars) break
            out.append(h.text).append("\n\n")
        }
        return out.toString().trim()
    }

    /** Titles and dates of the newest chats, one per line, for the model to know what exists. */
    fun recentTitles(exclude: String?, count: Int = 25): String {
        val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH)
        return list().filter { it.id != exclude }.take(count)
            .joinToString("\n") { "- ${it.title} (${date.format(java.util.Date(it.updated))})" }
    }

    private fun rebuildIndex(): List<ChatSummary> {
        val all = dir.listFiles { f -> f.name.endsWith(".json") && f != indexFile }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString<Chat>(f.readText()) }.getOrNull() }
            .map { ChatSummary(it.id, it.title, it.updated, it.projectId) }
        writeIndex(all)
        return all
    }

    private fun writeIndex(items: List<ChatSummary>) = writeAtomically(indexFile, json.encodeToString(items))

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
