package com.arin.llm

import com.arin.data.Memory
import com.arin.data.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the instructions sent with every request, and reads Arin's tool tags out of replies. */
object Prompt {
    const val MAX_SEARCHES = 3

    /** Commands one reply may run in the terminal before it has to answer. */
    const val MAX_COMMANDS = 25

    private val toolTag = Regex("""<tool\s+name\s*=\s*"([^"]+)"\s*>(.*?)</tool>""", RegexOption.DOT_MATCHES_ALL)

    /** A tool call in a reply: name and its JSON (or, for find_tools, the keywords). */
    fun toolCall(reply: String): Pair<String, String>? =
        toolTag.find(reply)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

    private val runTag = Regex("""<run>\s*(.+?)\s*</run>""", RegexOption.DOT_MATCHES_ALL)

    private val searchTag = Regex("""^\s*<search>(.{1,200}?)</search>""", RegexOption.DOT_MATCHES_ALL)
    private val rememberTag = Regex("""<remember>(.{1,300}?)</remember>""", RegexOption.DOT_MATCHES_ALL)

    fun system(
        webSearch: Boolean,
        memoryOn: Boolean,
        memories: List<Memory>,
        project: Project?,
        terminal: Boolean = false,
        tools: String = "",
        terminalInfo: String = "",
    ): String = buildString {
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

        if (terminal) {
            append("Terminal: you can run shell commands on the person's phone. Environment: ").append(terminalInfo).append(" ")
            append("To run one, write <run>the command</run> as the LAST thing in your ")
            append("reply and stop there. The app runs it with bash in the home folder ")
            append("and sends you the output and exit code; then continue. You may run up to $MAX_COMMANDS commands per answer, ")
            append("one at a time. Use this to install and set up tools (npm install -g …, ")
            append("pip install …, git clone …), run scripts, inspect files and check that things really work. ")
            append("Rules: always use non-interactive flags (-y, --yes, CI=1); never start a command that waits for input or ")
            append("runs forever (start servers with nohup … & and check them separately); chain related steps with &&; ")
            append("read the output before the next step and fix errors yourself. Commands the person may refuse. ")
            append("Only say something is installed or working after a command's output shows it. ")
            append("Things that need a desktop (GUI apps, Ghidra, IDA, Hopper, Frida server without root) may not work on a phone: ")
            append("say so plainly rather than pretending.\n\n")
        }

        if (tools.isNotBlank()) append(tools)

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
            if (project.files.isNotEmpty()) {
                append("The project's files are attached to the first message of this chat.\n")
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

    /** The command the reply asks to run, if any. */
    fun command(reply: String): String? = runTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** The reply with tool tags taken out, for showing and saving. */
    fun clean(reply: String): String =
        reply.replace(rememberTag, "").replace(Regex("""<remember>[^<]*$"""), "")
            .replace(runTag, "").replace(Regex("""<run>[\s\S]*$"""), "")
            .replace(toolTag, "").replace(Regex("""<tool[\s\S]*$"""), "")
            .trimEnd()
}
