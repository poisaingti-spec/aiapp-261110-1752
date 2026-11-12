package com.arin.llm

import com.arin.data.Attachment
import com.arin.data.Attachments
import com.arin.data.Memory
import com.arin.data.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the instructions sent with every request, and reads Arin's tool tags out of replies. */
object Prompt {
    const val MAX_SEARCHES = 3

    private val searchTag = Regex("""^\s*<search>(.{1,200}?)</search>""", RegexOption.DOT_MATCHES_ALL)
    private val rememberTag = Regex("""<remember>(.{1,300}?)</remember>""", RegexOption.DOT_MATCHES_ALL)

    fun system(webSearch: Boolean, memoryOn: Boolean, memories: List<Memory>, project: Project?): String = buildString {
        append("You are Arin, a helpful, friendly assistant in an Android app. Answer clearly and concisely. ")
        append("Use Markdown when it helps: short headings, lists, and fenced code blocks with a language.\n")
        append("Today is ").append(SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date())).append(".\n\n")

        append("Artifacts: when you write a complete web page, interactive demo, SVG image, standalone document or ")
        append("program the person will want to keep, put the whole thing in ONE fenced block (```html, ```svg, ")
        append("```markdown, or the code's language). For web pages, use one self-contained HTML file with inline CSS and JS. ")
        append("The app shows such blocks in a viewer with a live preview.\n\n")

        if (webSearch) {
            append("Web search: if the answer needs current or specific facts you are not sure of, reply with ONLY ")
            append("<search>your query</search> and nothing else. The app will run the search and send you the results; ")
            append("then answer using them and mention the sources by name. Search at most $MAX_SEARCHES times per answer. ")
            append("Don't search for things you already know well.\n\n")
        }

        if (memoryOn) {
            append("Memory: when the person tells you a lasting fact about themselves or a preference for how you should ")
            append("answer, add <remember>the fact, in a short sentence</remember> at the end of your reply. ")
            append("Never remember passwords, card or ID numbers, or health, money or other sensitive details. ")
            append("Don't mention this tag in your answer.\n")
            if (memories.isNotEmpty()) {
                append("What you know about the person (use it only where it helps):\n")
                memories.forEach { append("- ").append(it.text).append('\n') }
            }
            append('\n')
        }

        if (project != null) {
            append("This chat is in the project \"").append(project.name).append("\".\n")
            if (project.instructions.isNotBlank()) {
                append("Project instructions (follow them):\n").append(project.instructions.trim()).append("\n\n")
            }
            val textFiles = project.files.filter { it.kind == Attachment.TEXT }
            if (textFiles.isNotEmpty()) {
                append("Project files:\n").append(Attachments.promptWithFiles("", textFiles)).append('\n')
            }
        }
    }

    /** The search query if the reply is a search request. */
    fun searchQuery(reply: String): String? = searchTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** While streaming: true if what has arrived so far may still turn into a search request. */
    fun maybeSearch(partial: String): Boolean {
        val t = partial.trimStart()
        return t.isEmpty() || "<search>".startsWith(t) || t.startsWith("<search>")
    }

    /** Facts to remember, found in the reply. */
    fun memories(reply: String): List<String> = rememberTag.findAll(reply).map { it.groupValues[1].trim() }.toList()

    /** The reply with tool tags taken out, for showing and saving. */
    fun clean(reply: String): String =
        reply.replace(rememberTag, "").replace(Regex("""<remember>[^<]*$"""), "").trimEnd()
}
