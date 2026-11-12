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

    private val imagesTag = Regex("""<images>\s*(.{1,200}?)\s*</images>""", RegexOption.DOT_MATCHES_ALL)
    private val videosTag = Regex("""<videos>\s*(.{1,200}?)\s*</videos>""", RegexOption.DOT_MATCHES_ALL)
    private val fetchTag = Regex("""<fetch>\s*(https?://\S{1,800}?)\s*</fetch>""", RegexOption.DOT_MATCHES_ALL)
    private val downloadTag = Regex("""<download(?:\s+as\s*=\s*"([^"]{1,120})")?\s*>\s*(https?://\S{1,1500}?)\s*</download>""", RegexOption.DOT_MATCHES_ALL)
    private val updateTag = Regex("""<update_arin>(.+?)</update_arin>""", RegexOption.DOT_MATCHES_ALL)

    /** Web steps (media searches, page reads, downloads) one reply may take. */
    const val MAX_WEB_STEPS = 12

    /** A media search the reply asks for: (true for videos, query). */
    fun mediaRequest(reply: String): Pair<Boolean, String>? =
        videosTag.find(reply)?.let { true to it.groupValues[1].trim() }
            ?: imagesTag.find(reply)?.let { false to it.groupValues[1].trim() }

    fun fetchUrl(reply: String): String? = fetchTag.find(reply)?.groupValues?.get(1)?.trim()

    /** A file to download: (file name or blank, URL). */
    fun download(reply: String): Pair<String, String>? =
        downloadTag.find(reply)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

    /** A change to Arin itself the reply asks for. */
    fun selfUpdate(reply: String): String? = updateTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private val buildTag = Regex("""<build(?:\s+name\s*=\s*"([^"]{1,60})")?\s*>(.+?)</build>""", RegexOption.DOT_MATCHES_ALL)

    /** An app the reply asks Arin to build: name (may be blank) and description. */
    fun buildRequest(reply: String): Pair<String, String>? =
        buildTag.find(reply)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }?.takeIf { it.second.isNotBlank() }

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
            append("Only say something is installed or working after a command's output shows it; check exit codes, and don't ")
            append("hide failures by piping into head or tail without set -o pipefail (the app sets it). ")
            append("Arin is your MCP client: to use MCP servers such as REA, call their tools with <tool> (when listed below), ")
            append("never by configuring Claude Code or another agent. ")
            append("Things that need a desktop (GUI apps, Ghidra, IDA, Hopper, Frida server without root) may not work on a phone: ")
            append("say so plainly rather than pretending.\n\n")
        }

        append("Internet: you have full internet access. Write ONE of these as the LAST thing in your reply and stop; the app ")
        append("runs it and sends you the result, then continue: <fetch>https://url</fetch> reads any web page, document, ")
        append("README or API; <download as=\"name.ext\">https://url</download> downloads any file (it is saved for the person ")
        append("and for terminal tools) — use it to get files a task or project needs; ")
        append("<images>what to show</images> finds pictures and <videos>what to show</videos> finds videos, which are shown ")
        append("right in the chat under your reply — use them whenever the person asks to see, show or display something. ")
        append("You can also show a picture you know the address of with ![description](https://…/image.jpg), and a video by ")
        append("putting its YouTube or .mp4 link in your reply; it plays in the chat. Up to $MAX_WEB_STEPS such steps per answer.\n\n")

        append("Updating Arin itself: Arin's own source code (Kotlin, Android) is on the person's GitHub and you can change it. ")
        append("When they ask for a feature Arin lacks, report a bug in Arin, or ask you to update or improve the app, end your ")
        append("reply with <update_arin>exactly what to change in Arin and why, in detail</update_arin>. Arin then edits its code ")
        append("(searching the web and downloading files or libraries as needed), builds a new version on GitHub, fixes build ")
        append("errors, and offers to install the update. Agree on the change first if it's unclear.\n\n")

        append("Building Android apps: Arin can turn a description into a complete Android app (Kotlin, Jetpack Compose, ")
        append("Material 3), put the source on the person's GitHub and compile it into an APK with GitHub Actions, fixing ")
        append("build errors automatically. When the person asks you to make, build or compile an app or APK, first agree ")
        append("on what it should do (ask briefly only if it's really unclear), then end your reply with ")
        append("<build name=\"App name\">a complete, detailed description: screens, features, data, look</build>. ")
        append("Arin then opens Build mode and starts. For an uploaded Android Studio project (.zip), use Build mode's Upload. ")
        append("Don't paste whole app sources in chat when they asked for an APK.\n\n")

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

    private val promiseWords = Regex(
        """\b(let me|let's|i'll|i will|i'm going to|now i|next,? i|going to|fixing (it |this )?by|retrying|trying again|re-?running|running it|calling)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * True when a reply says what it will do next but stops without doing it, e.g. ends with
     * "fixing by dumping the XML directly:" and no <run> or <tool>. Used to nudge the model on.
     */
    fun looksUnfinished(reply: String): Boolean {
        val t = clean(reply).trim()
        if (t.isEmpty()) return true
        if (t.endsWith("?")) return false
        if (t.count { it == '`' } >= 3 && t.split("```").size % 2 == 0) return true // code block left open
        val tail = t.takeLast(300)
        return t.endsWith(":") || t.endsWith("…") || t.endsWith("...") ||
            (promiseWords.containsMatchIn(tail.substringAfterLast("\n\n")) && tail.length < 300 && !tail.contains("Summary", true))
    }

    /** The command the reply asks to run, if any. */
    fun command(reply: String): String? = runTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** The reply with tool tags taken out, for showing and saving. */
    fun clean(reply: String): String =
        reply.replace(rememberTag, "").replace(Regex("""<remember>[^<]*$"""), "")
            .replace(runTag, "").replace(Regex("""<run>[\s\S]*$"""), "")
            .replace(toolTag, "").replace(Regex("""<tool[\s\S]*$"""), "")
            .replace(buildTag, "").replace(Regex("""<build[\s\S]*$"""), "")
            .replace(updateTag, "").replace(Regex("""<update_arin>[\s\S]*$"""), "")
            .replace(imagesTag, "").replace(videosTag, "").replace(fetchTag, "").replace(downloadTag, "")
            .replace(Regex("""<(images|videos|fetch|download)\b[\s\S]*$"""), "")
            .replace(Regex("""</?(run|tool|build|update_arin|images|videos|fetch|download)\b[^>]*>"""), "")
            .trimEnd()
}
