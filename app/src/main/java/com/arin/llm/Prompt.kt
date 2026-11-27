package com.arin.llm

import com.arin.data.Memory
import com.arin.data.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the instructions sent with every request, and reads Arin's tool tags out of replies. */
object Prompt {
    const val MAX_SEARCHES = 3

    /** Searches one reply may run in Deep research mode. */
    const val MAX_DEEP_SEARCHES = 12

    /** Commands one reply may run in the terminal before it has to answer. */
    const val MAX_COMMANDS = 60

    private val imagesTag = Regex("""<images>\s*(.{1,200}?)\s*</images>""", RegexOption.DOT_MATCHES_ALL)
    private val videosTag = Regex("""<videos>\s*(.{1,200}?)\s*</videos>""", RegexOption.DOT_MATCHES_ALL)
    private val recallTag = Regex("""<recall>(.{1,200}?)</recall>""", RegexOption.DOT_MATCHES_ALL)

    /** Words to look for in past chats, if the reply asks to recall one. */
    fun recall(reply: String): String? = recallTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

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
    private val retryableError = Regex(
        """(?i)incomplete|invalid response|discard|truncat|malformed|empty (response|reply)|no response|unexpected end|connection reset|reset by peer|disconnect|stream (closed|ended)|read timeout|timed out|bad gateway|502|503|504|overloaded|upstream|unable to resolve host|failed to connect|network is unreachable|connection abort|connection refused|no address associated|socket|broken pipe|unexpected end of stream""",
    )
    private val notRetryable = Regex(
        """(?i)key was rejected|not available to this account|isn'?t available|check it in settings|pick another model|add your|require|link an? .*github""",
    )

    /** True for an error worth retrying on its own: a dropped or incomplete reply, or a passing server hiccup. */
    fun isRetryable(message: String?): Boolean =
        message != null && retryableError.containsMatchIn(message) && !notRetryable.containsMatchIn(message)

    fun selfUpdate(reply: String): String? = updateTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private val buildTag = Regex("""<build(?:\s+name\s*=\s*["']([^"']{1,60})["'])?\s*>(.+?)</build>""", RegexOption.DOT_MATCHES_ALL)

    /** A <build> left open at the end of a reply (the model ran out or forgot to close it). */
    private val buildTagOpen = Regex("""<build(?:\s+name\s*=\s*["']([^"']{1,60})["'])?\s*>([\s\S]{40,})$""")

    /** An app the reply asks Arin to build: name (may be blank) and description. */
    fun buildRequest(reply: String): Pair<String, String>? = withCodeMasked(reply, { buildRequestRaw(it) }) { r, _ -> r }

    private fun buildRequestRaw(reply: String): Pair<String, String>? =
        (buildTag.find(reply) ?: buildTagOpen.find(reply.trimEnd())?.takeIf { m ->
            // An unclosed tag counts only when it's really a tag: with a name, or starting its own line.
            m.groupValues[1].isNotBlank() || reply.substring(0, m.range.first).substringAfterLast('\n').isBlank()
        })
            ?.let { it.groupValues[1].trim() to it.groupValues[2].trim().removeSuffix("```").trim() }
            ?.takeIf { it.second.isNotBlank() }

    private val notYet = Regex(
        """(?i)\b(don'?t|do not|not yet|no need to|never|without|before (you |we )?(build|start)|later|wait)\b[^.!?\n]{0,40}\b(build|compile|apk|start)""",
    )
    private val retryWord = Regex("""(?i)\b(try again|retry|again|fix (it|this|the)|continue|resume|keep going|carry on|rebuild)\b""")
    private val asksQuestion = Regex("""(?i)^\W*(why|how|what|when|where|who|which|can|could|is|are|was|does|do|did|will|would|should)\b""")
    private val politeOrder = Regex("""(?i)\b(can|could|will|would) you (please |now |just )*(re)?(build|compile)\b""")
    private val buildOrder = Regex(
        """(?i)(^|[.!,;:\n]\s*)((ok(ay)?|yes|yep|now|please|so|then|great|good|fine|perfect|just)\W+)*(re)?(build|compile)""" +
            """(\s+(it|now|this|that|the|an?|me|my|apk|app|everything|all|please)\b|\s*[.!]*\s*$)""" +
            """|\b(build|compile|make|generate|create) (it|now|the app|everything)\b|\bstart (the )?build""" +
            """|\b(build|make|generate|create|give me|get me|compile)( it| this| that)?( in| into| to| as)?( the| an| a)? apk\b""",
    )

    private val strictBuild = Regex(
        """^((ok|okay|yes|yeah|yep|now|please|so|then|go|go ahead|alright|fine|great|good|perfect|just|done|thanks|thank you|and)\s+)*""" +
            """((re)?build|compile|start( the)? build(ing)?|make( the| an)? apk|generate( the| an)? apk|create( the| an)? apk|give me( the| an)? apk)""" +
            """(\s+(it|now|this|that|the|an?|my|app|apk|the app|the apk|everything|all|please|now please|it now|it please|"""+
            """with everything|everything now|for me|again))*$""",
    )
    private val strictRetry = Regex("""^((ok|okay|yes|now|please|so)\s+)*(try again|retry|rebuild|build again|try the build again|resume|continue( the)? build)(\s+(now|please|it))*$""")
    private val strictUpdate = Regex("""^((ok|okay|yes|now|please|so|go ahead)\s+)*(update( arin| the app| yourself| it)?|apply( the)? update|install( the)? update)(\s+(now|please|it))*$""")

    /** The person's message reduced to words: lower case, no punctuation or emoji. */
    private fun plainWords(text: String) = text.lowercase().replace(Regex("""[^a-z0-9\s]"""), " ").replace(Regex("""\s+"""), " ").trim()

    // An imperative to build/push THE app (not "I want to build an app…"), even inside a longer sentence.
    private val buildClause = Regex(
        """(?i)\bbuild( it| now| the app| the apk| this app| that app| this| that| and (push|publish|give))\b""" +
            """|\bmake the apk\b|\bcompile( it| the app| the apk| to apk| into (an )?apk)?\b""" +
            """|\bpush\b[^.?!]{0,40}\bgithub\b|\bbuild\b[^.?!]{0,20}\bon github\b|\bgive me the (apk|app)\b""",
    )
    private val discussIntent = Regex(
        """(?i)\b(i (want|would like|wanna|plan|intend|hope|am planning|'?m planning) to (build|make|create)|thinking (of|about) (build|mak|creat)|how (do|to|can) i|should i|idea for)\b""",
    )

    /**
     * True for an order to build/push THE app, including inside a longer sentence ("push to github and build the apk").
     * Still false for plain discussion ("I want to build an app…"), questions, and "don't build yet".
     */
    fun ordersBuild(text: String): Boolean {
        if (notYet.containsMatchIn(text) || text.trim().endsWith("?") || discussIntent.containsMatchIn(text)) return false
        val w = plainWords(text)
        if (w.split(' ').size <= 8 && strictBuild.matches(w)) return true
        return buildClause.containsMatchIn(text)
    }

    /** True when the person's latest message lets a build start: an order to build, or "try again" after a build in this chat. */
    fun saysBuild(text: String, hasChatBuild: Boolean): Boolean {
        val w = plainWords(text)
        return ordersBuild(text) || (hasChatBuild && w.split(' ').size <= 6 && strictRetry.matches(w))
    }

    /** When a build order names where to build, return it ("on github"/"cloud" → chat, "phone"/"local" → phone, "codespace", "build tab"). */
    fun namedBuildTarget(text: String): String? {
        val t = plainWords(text)
        return when {
            Regex("\\bcodespace\\b").containsMatchIn(t) -> "CODESPACE"
            Regex("\\b(on|to|in|using|via)?\\s*(this )?(phone|device|locally|local|offline)\\b").containsMatchIn(t) &&
                !t.contains("github") -> "PHONE"
            Regex("\\bbuild tab\\b").containsMatchIn(t) -> "BUILD_TAB"
            Regex("\\b(github|git hub|cloud|online)\\b").containsMatchIn(t) -> "CHAT"
            else -> null
        }
    }

    /** True for a short, plain order to update Arin itself ("update", "update arin now"), or to build. */
    fun ordersUpdate(text: String): Boolean {
        val w = plainWords(text)
        return ordersBuild(text) || (w.split(' ').size <= 6 && strictUpdate.matches(w))
    }

    private val drawTag = Regex("""<draw>\s*(.{3,6000}?)\s*(?:</draw>|$)""", RegexOption.DOT_MATCHES_ALL)

    /** Pictures the reply asks Arin to create (made by a free image service from the description). */
    fun drawings(reply: String): List<com.arin.data.MediaItem> = drawTag.findAll(reply).take(2).map { m ->
        // The picture service takes the description in a web address, so keep it to a sensible length.
        val prompt = m.groupValues[1].replace(Regex("\\s+"), " ").trim().let { d ->
            if (d.length <= 700) d else d.take(700).substringBeforeLast(". ", d.take(700)).trim()
        }
        val url = "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=1024&height=1024&nologo=true&seed=" + (prompt.hashCode() and 0x7fffffff)
        com.arin.data.MediaItem(video = false, url = url, thumb = url, title = prompt.take(120), source = url)
    }.toList()

    private val promisedAction = Regex(
        """(?i)\b(build|apk|compile|update arin|updating arin|spec|kicking off|starting|going out|launching|relaunching)\b""",
    )

    /** A reply that announces a build or update (e.g. "Build spec going out now:") without its tag. */
    fun promisedBuildWithoutTag(reply: String): Boolean {
        if (buildRequest(reply) != null || selfUpdate(reply) != null) return false
        val t = clean(reply).trim()
        if (t.isEmpty() || t.endsWith("?")) return false
        val tail = t.takeLast(250)
        return (t.endsWith(":") || t.endsWith("…") || t.endsWith("...") || tail.contains("now", true)) && promisedAction.containsMatchIn(tail)
    }

    private val toolTag = Regex("""<tool\s+name\s*=\s*"([^"]+)"\s*>(.*?)</tool>""", RegexOption.DOT_MATCHES_ALL)

    /** A tool call in a reply: name and its JSON (or, for find_tools, the keywords). */
    fun toolCall(reply: String): Pair<String, String>? =
        toolTag.find(reply)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() } ?: invokedTool(reply)

    private val installTag = Regex("""<install>\s*([\s\S]+?)\s*</install>""")
    private val writeTag = Regex("""<write\s+path\s*=\s*"([^"]+)"\s*>([\s\S]*?)</write>""")
    private val readTag = Regex("""<read\s+path\s*=\s*"([^"]+)"(?:\s+from\s*=\s*"?(\d+)"?)?\s*/?>(?:\s*</read>)?|<read>\s*([^<]+?)\s*</read>""")
    private val editTag = Regex("""<edit\s+path\s*=\s*"([^"]+)"\s*>\s*<old>([\s\S]*?)</old>\s*<new>([\s\S]*?)</new>\s*</edit>""")

    /** File and install actions (Claude Code-style tools) the model can ask the app to do. */
    sealed class AgentAction {
        data class Install(val spec: String) : AgentAction()
        data class Read(val path: String, val from: Int) : AgentAction()
        data class Write(val path: String, val content: String) : AgentAction()
        data class Edit(val path: String, val old: String, val new: String) : AgentAction()
    }

    private fun trimOneNewline(t: String) = t.removePrefix("\r").removePrefix("\n").removeSuffix("\n").removeSuffix("\r")

    /** File content without a Markdown fence the model may have wrapped it in. */
    private fun unfence(t: String): String {
        val x = trimOneNewline(t)
        val m = Regex("""^```[\w.+-]*\n([\s\S]*?)\n?```\s*$""").find(x)
        return m?.groupValues?.get(1) ?: x
    }

    /** The first install/read/write/edit action in the reply, if any. */
    fun agentAction(reply: String): AgentAction? {
        val found = listOfNotNull(
            installTag.find(reply)?.let { it.range.first to AgentAction.Install(it.groupValues[1].trim()) },
            writeTag.find(reply)?.let { it.range.first to AgentAction.Write(it.groupValues[1].trim(), unfence(it.groupValues[2]) + "\n") },
            readTag.find(reply)?.let { m ->
                val path = m.groupValues[1].ifBlank { m.groupValues[3] }.trim()
                m.range.first to AgentAction.Read(path, m.groupValues[2].toIntOrNull() ?: 1)
            },
            editTag.find(reply)?.let { it.range.first to AgentAction.Edit(it.groupValues[1].trim(), trimOneNewline(it.groupValues[2]), trimOneNewline(it.groupValues[3])) },
        )
        return found.minByOrNull { it.first }?.second
    }

    private val runTag = Regex("""<run>\s*(.+?)\s*</run>""", RegexOption.DOT_MATCHES_ALL)

    // Some models (free DeepSeek among them) emit tool calls in a function-call wire format as plain text, e.g.
    // <|DSML| invoke name="run">the command</|DSML| parameter>. Read those too, so the command still runs.
    private val invokeTag = Regex(
        """invoke\s+name\s*=\s*["']([A-Za-z_][\w.-]*)["']\s*>([\s\S]*?)(?=</\s*\|?[^>]*?(?:parameter|invoke|function|calls)[^>]*>|\z)""",
        RegexOption.IGNORE_CASE,
    )
    private val dsmlTag = Regex("""</?\s*\|?\s*(DSML|channel|message|function|tool_call|tool_calls|invoke|parameter|calls|antml:[\w-]+)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val dsmlOpen = Regex("""<\|[^>]*$""")

    /** A run command written in the function-call format, if any (used when <run> wasn't found). */
    private fun invokedRun(reply: String): String? =
        invokeTag.findAll(reply).firstOrNull { it.groupValues[1].equals("run", true) || it.groupValues[1].equals("bash", true) || it.groupValues[1].equals("shell", true) }
            ?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }

    /** A tool call written in the function-call format, if any. */
    private fun invokedTool(reply: String): Pair<String, String>? =
        invokeTag.findAll(reply).firstOrNull { it.groupValues[1].lowercase() !in setOf("run", "bash", "shell") }
            ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }

    private val searchTag = Regex("""^\s*<search>(.{1,200}?)</search>""", RegexOption.DOT_MATCHES_ALL)

    /** While working on a task (terminal, tools), a search may come after some text, like the other tools. */
    private val searchTagAnywhere = Regex("""<search>(.{1,200}?)</search>""", RegexOption.DOT_MATCHES_ALL)

    /** Searches allowed per answer while working on a task (fixing errors needs more). */
    const val MAX_TASK_SEARCHES = 8

    fun searchQueryAnywhere(reply: String): String? = searchTagAnywhere.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The most telling line of a failure, as a web search query: the last line that mentions an error,
     * with paths, addresses and long numbers removed.
     */
    fun errorQuery(output: String, command: String = ""): String {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val line = lines.lastOrNull { Regex("""(?i)\b(error|exception|failed|fatal|not found|cannot|can't|unable|denied|invalid|missing|undefined|traceback)\b""").containsMatchIn(it) }
            ?: lines.lastOrNull() ?: command
        val cleaned = line
            .replace(Regex("""(/[\w.@+-]+){2,}"""), " ")
            .replace(Regex("""0x[0-9a-fA-F]+|\b\d{4,}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim().take(160)
        val tool = command.trim().split(Regex("""\s+""")).firstOrNull { it.isNotBlank() && it != "cd" && it != "set" && !it.startsWith("-") && it != "pipefail;" && !it.contains('=') && !it.startsWith("sudo") }.orEmpty()
        return (if (tool.isNotBlank() && !cleaned.contains(tool, true)) "$tool " else "") + cleaned
    }
    private val rememberTag = Regex("""<remember>(.{1,300}?)</remember>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A short system prompt for models running offline on the phone: the full one is thousands of tokens,
     * which a phone CPU would spend minutes reading before every reply.
     */
    fun localSystem(memories: List<Memory>, aboutPerson: String = "", howToAnswer: String = "", style: String = ""): String = buildString {
        append("You are Arin, a helpful assistant running offline on the person's phone. Answer directly and clearly. ")
        append("Use Markdown when it helps (short lists, fenced code blocks). If you don't know something, say so.\n")
        when (style) {
            "Concise" -> append("Keep answers short.\n")
            "Detailed" -> append("Give thorough, detailed answers.\n")
            "Formal" -> append("Use a formal tone.\n")
        }
        if (aboutPerson.isNotBlank()) append("About the person: ").append(aboutPerson.take(800)).append('\n')
        if (howToAnswer.isNotBlank()) append("How they want answers: ").append(howToAnswer.take(800)).append('\n')
        if (memories.isNotEmpty()) {
            append("Things the person asked you to remember:\n")
            memories.takeLast(20).forEach { append("- ").append(it.text.take(200)).append('\n') }
        }
    }

    private val topicWords = mapOf(
        "build" to Regex("""\b(build|built|apk|app|apps|compile|github|gradle|kotlin|compose|screen|screens|plan|feature|features|android|try again|retry|repo|codespace|zip)\b""", RegexOption.IGNORE_CASE),
        "terminal" to Regex("""\b(run|install|tool|tools|setup|set up|download|command|terminal|shell|bash|linux|file|files|folder|script|code|program|project|edit|write|read|npm|pip|python|node|git|rea|reverse|decompile|disassemble|binary|apk|jadx|frida|ffmpeg|server|port|error|log|debug|convert|compress)\b""", RegexOption.IGNORE_CASE),
        "update" to Regex("""\b(arin|update|upgrade|bug|crash|this app|your app|new version|feature)\b""", RegexOption.IGNORE_CASE),
        "draw" to Regex("""\b(draw|drawing|picture|image|illustrat\w*|generate|imagine|logo|wallpaper|art|sketch|paint\w*|design)\b""", RegexOption.IGNORE_CASE),
        "web" to Regex("""\b(download|video|videos|image|images|photo|photos|show me|link|url|http|website|page|youtube|fetch|open)\b|https?://""", RegexOption.IGNORE_CASE),
        "artifact" to Regex("""\b(html|web ?page|website|svg|document|program|script|code|landing|game|demo|calculator)\b""", RegexOption.IGNORE_CASE),
        "recall" to Regex("""\b(remember|earlier|before|last time|previous|we discussed|you said|you suggested|continue|left off|my app|my project|again)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Which long rule blocks this conversation needs (Save credits), from the person's last few messages and
     * what the chat already did. Generous on purpose: a block sent when unneeded costs a little, a missing one breaks a task.
     */
    fun topics(recentUserText: String, hasBuild: Boolean, usedTerminal: Boolean, reaChat: Boolean): Set<String> {
        val t = topicWords.filter { (_, re) -> re.containsMatchIn(recentUserText) }.keys.toMutableSet()
        if (hasBuild) t += "build"
        if (usedTerminal || reaChat) t += "terminal"
        if ("build" in t || "update" in t) t += "artifact"
        return t
    }

    fun system(
        webSearch: Boolean,
        memoryOn: Boolean,
        memories: List<Memory>,
        project: Project?,
        terminal: Boolean = false,
        tools: String = "",
        terminalInfo: String = "",
        pastChats: String = "",
        aboutPerson: String = "",
        howToAnswer: String = "",
        style: String = "",
        deepResearch: Boolean = false,
        /** Save credits: send each long rule block only when [topics] says this conversation needs it. */
        lean: Boolean = false,
        topics: Set<String> = emptySet(),
    ): String = buildString {
        fun need(t: String) = !lean || t in topics
        append("You are Arin, a helpful, friendly assistant in an Android app. Answer clearly and concisely. ")
        runCatching { com.arin.ArinApp.versionLabel(com.arin.ArinApp.instance) }.getOrNull()
            ?.let { append("This is Arin version ").append(it).append(". ") }
        append("Use Markdown when it helps: short headings, lists, and fenced code blocks with a language.\n")
        append("Be quick: answer straight away whenever you can. Most questions need no tools, no commands and no long thinking; ")
        append("a simple question gets a direct answer in a few sentences. Use tools only when the task truly needs them.\n")
        append("Be accurate: never guess or make things up, and never present a guess as a fact. If you aren't sure, or a fact may ")
        append("have changed, check it with a web search when search is on; if you still can't confirm it, say plainly what you ")
        append("know, what you don't, and how sure you are. For an attached picture, you can already see it: describe what is ")
        append("actually visible and answer at once. If a brand name, label or text isn't visible in it, say so, then give the ")
        append("most likely options as possibilities and say what would confirm it (for example a marking on the base).\n")
        append("Today is ").append(SimpleDateFormat("EEEE d MMMM yyyy", Locale.ENGLISH).format(Date())).append(".\n\n")

        if (aboutPerson.isNotBlank() || howToAnswer.isNotBlank()) {
            append("Custom instructions from the person (always follow them):\n")
            if (aboutPerson.isNotBlank()) append("About them: ").append(aboutPerson.trim().take(1500)).append('\n')
            if (howToAnswer.isNotBlank()) append("How to answer: ").append(howToAnswer.trim().take(1500)).append('\n')
            append('\n')
        }
        when (style) {
            "Concise" -> append("Reply style: concise. Short, direct answers; no preamble; lists only when they help.\n\n")
            "Detailed" -> append("Reply style: detailed. Explain step by step, with reasons and examples, as a patient teacher would.\n\n")
            "Formal" -> append("Reply style: formal. Clear, polished, professional wording.\n\n")
        }
        if (!need("draw")) append("To create a picture, write <draw>a description in English, under 90 words</draw>.\n\n") else {
        append("Pictures you create: when the person asks you to draw, generate, create or imagine a picture (not find a real photo), ")
        append("write <draw>a detailed description of the picture in English: subject, setting, style, lighting, colours</draw> ")
        append("in your reply. Arin creates the picture and shows it under your reply; add one short sentence about it. ")
        append("Draw at once: when they ask for a picture, don't ask questions first and don't ask for confirmation; choose any ")
        append("missing details yourself and send the <draw> tag in that same reply (they can ask for changes after seeing it). ")
        append("Keep the description under 90 words, and always close the tag with </draw>. ")
        append("At most two per reply. For real things that exist (a person, a place, a product), use <images> instead.\n\n")
        }

        if (deepResearch) {
            append("Deep research mode is ON for this question. Work like a careful researcher: plan what you need to find out, ")
            append("run up to $MAX_DEEP_SEARCHES different searches (one <search> per reply, each from a different angle), open the ")
            append("most useful pages with <fetch>https://…</fetch> to read them properly, compare what the sources say and note ")
            append("where they disagree. Then write a structured report: a short answer first, then sections with headings, key ")
            append("facts and figures with their dates, and a Sources list naming each source. Say what you could not confirm.\n")
            append("NEVER stop with just a line like \"searching next\" or \"continuing\": every reply either carries ONE <search> or ")
            append("<fetch> as its last action, or is the finished report. When you have enough, write the full report straight away.\n\n")
        }
        append("Finishing any task: when you have used searches, tools or the terminal, don't end with a bare status line. Finish with ")
        append("a short clear summary of what you found or did, then one line telling the person what they can do next.\n\n")

        if (need("artifact")) {
        append("Artifacts: when you write a complete web page, interactive demo, SVG image, standalone document or ")
        append("program the person will want to keep, put the whole thing in ONE fenced block (```html, ```svg, ")
        append("```markdown, or the code's language). For web pages, use one self-contained HTML file with inline CSS and JS. ")
        append("The app shows such blocks in a viewer with a live preview.\n\n")
        }

        if (webSearch) {
            append("Web search: if the answer needs current or specific facts you are not sure of, reply with ONLY ")
            append("<search>your query</search> and nothing else. The app will run the search and send you the results; ")
            append("then answer using them and mention the sources by name. Search at most $MAX_SEARCHES times per answer. ")
            append("Don't search for things you already know well.\n\n")
        }

        if (!terminal) {
            append("Tools: if a task needs a program, a terminal or files, you can still do it: write <install>tool names</install>, ")
            append("<run>command</run>, <read path=\"…\"/>, <write path=\"…\">content</write> or <edit path=\"…\"><old>…</old><new>…</new></edit> ")
            append("as the LAST thing in your reply. The app asks the person first and sets up its Linux if needed.\n\n")
        }
        if (terminal && !need("terminal")) {
            append("Terminal: run a shell command on the phone with <run>command</run> as the LAST thing in your reply, only when the ")
            append("task truly needs it. Install any missing tool yourself with <install>names</install>. Files: <read path=\"…\"/>, ")
            append("<write path=\"…\">content</write>, <edit path=\"…\"><old>exact text</old><new>new text</new></edit>. ")
            append("Never use the terminal to build APKs or push to GitHub.\n\n")
        }
        if (terminal && need("terminal")) {
            append("NEVER use the terminal, git or gradle to build an APK, push to GitHub, or publish a release — that always fails ")
            append("(the GitHub token is not in the terminal). Use the <build> tag for building and pushing; see the Building section.\n")
            append("Terminal: you can run shell commands on the person's phone. Use it ONLY for work that needs it: installing or ")
            append("running software, working with files or code, reverse engineering, or when the person asks you to run something. ")
            append("NEVER run commands (python, ImageMagick or anything else) to answer a question, to look at, crop or zoom an ")
            append("attached picture, to do simple maths, or to double-check an answer you can give directly: that makes the person wait. ")
            append("Environment: ").append(terminalInfo).append(" ")
            append("To run one, write <run>the command</run> as the LAST thing in your ")
            append("reply and stop there. The app runs it with bash in the home folder ")
            append("and sends you the output and exit code; then continue. You may run up to $MAX_COMMANDS commands per answer, ")
            append("one at a time. Use this to install and set up tools (npm install -g …, ")
            append("pip install …, git clone …), run scripts, inspect files and check that things really work. ")
            append("Rules: always use non-interactive flags (-y, --yes, CI=1); never start a command that waits for input or ")
            append("runs forever (start servers with nohup … & and check them separately); chain related steps with &&; ")
            append("read the output before the next step. Commands the person may refuse. ")
            append("Fixing errors: when something fails, fix it yourself like a good engineer, without stopping to ask: read the ")
            append("error, find the cause, apply a fix (install what's missing, change the command or flags, correct the code or ")
            append("config) and run it again. If you don't know the fix or your fix didn't work, search the web for the exact error ")
            append("with <search>the error message</search> or read docs and issues with <fetch>url</fetch>, then apply what you ")
            append("find. Try different approaches before giving up; only ask the person when it really needs them (a password, ")
            append("a choice, something only they can do). ")
            append("Only say something is installed or working after a command's output shows it; check exit codes, and don't ")
            append("hide failures by piping into head or tail without set -o pipefail (the app sets it). ")
            append("\nInstalling tools: you may install ANY program, library or tool a task needs, yourself, without asking first. ")
            append("Write <install>name another-name</install>: the app tries the system package manager (apt in Linux, pkg in Termux), ")
            append("then pip, then npm, and reports what worked. Force one with a prefix: apt:ffmpeg, pip:requests, npm:typescript. ")
            append("For things not in those (a GitHub release, a Go/Rust tool, a JDK), download and set them up with <run> (curl/wget, ")
            append("tar, chmod +x, add to PATH) or build from source. Check a tool works (--version) before relying on it. ")
            append("Everything installed or written earlier stays on the phone: when continuing a task, check first ")
            append("(command -v, ls, --version) and skip what's already done instead of reinstalling or rewriting it.\n")
            append("Files (prefer these over cat, echo or heredocs): <read path=\"src/main.py\" from=\"1\"/> shows a file with line ")
            append("numbers (400 lines at a time; from= for more) or lists a folder; <write path=\"src/main.py\">the WHOLE file</write> ")
            append("creates or replaces a file; <edit path=\"src/main.py\"><old>exact existing text</old><new>replacement</new></edit> ")
            append("changes one place — <old> must match the file exactly and only once, so read the file first and include a few ")
            append("surrounding lines. Paths are relative to the home folder unless they start with /. One action per reply, as the ")
            append("LAST thing in it; the app sends you the result.\n")
            append("Long tasks: work like Claude Code. Start a multi-step task with a short numbered plan, then do it step by step, ")
            append("checking each result, and finish with what was done and what the person can do next.\n")
            append("Arin is your MCP client: to use MCP servers such as REA, call their tools with <tool> (when listed below), ")
            append("never by configuring Claude Code or another agent. ")
            append("Things that need a desktop (GUI apps, Ghidra, IDA, Hopper, Frida server without root) may not work on a phone: ")
            append("say so plainly rather than pretending.\n\n")
        }

        if (!need("web")) {
            append("Internet: write <fetch>https://url</fetch> to read a page, <images>what</images> or <videos>what</videos> to show ")
            append("pictures or videos, as the LAST thing in your reply; the app runs it and sends you the result.\n\n")
        } else {
        append("Internet: you have full internet access. Write ONE of these as the LAST thing in your reply and stop; the app ")
        append("runs it and sends you the result, then continue: <fetch>https://url</fetch> reads any web page, document, ")
        append("README or API; <download as=\"name.ext\">https://url</download> downloads any file (it is saved for the person ")
        append("and for terminal tools) — use it to get files a task or project needs; ")
        append("<images>what to show</images> finds pictures and <videos>what to show</videos> finds videos, which are shown ")
        append("right in the chat under your reply — use them whenever the person asks to see, show or display something. ")
        append("You can also show a picture you know the address of with ![description](https://…/image.jpg), and a video by ")
        append("putting its YouTube or .mp4 link in your reply; it plays in the chat. Up to $MAX_WEB_STEPS such steps per answer.\n\n")
        }

        if (need("update")) {
        append("Updating Arin itself: Arin's own source code (Kotlin, Android) is on the person's GitHub and you can change it. ")
        append("When they ask for a feature Arin lacks, report a bug in Arin, or ask you to update or improve the app, end your ")
        append("reply with <update_arin>exactly what to change in Arin and why, in detail</update_arin>. Arin then edits its code ")
        append("(searching the web and downloading files or libraries as needed), builds a new version on GitHub, fixes build ")
        append("errors, and offers to install the update. Agree on the change first if it's unclear.\n\n")
        } else append("Arin can update its own code with <update_arin>…</update_arin>; full rules are given when the person asks to change Arin.\n\n")
        // Any model can call Claude for help; errors are always researched on the internet first (protected rules).
        // AI helper (Claude, ChatGPT, Gemini or a Custom API model): only when it is switched on.
        append(runCatching { com.arin.selffix.ClaudeHelper.instructions(com.arin.ArinApp.instance) }.getOrDefault("")) // ARIN-PROTECTED: ai-helper
        append(runCatching { com.arin.selffix.Worker.instructions(com.arin.ArinApp.instance) }.getOrDefault("")) // ARIN-PROTECTED: worker-mode
        append(runCatching { com.arin.selffix.TeamWork.instructions(com.arin.ArinApp.instance) }.getOrDefault("")) // ARIN-PROTECTED: team-work in every tab
        append(com.arin.selffix.TaskSummary.RULE).append('\n') // ARIN-PROTECTED: task-summary in every tab
        append(com.arin.selffix.TabIsolation.RULE).append('\n') // ARIN-PROTECTED: tab-isolation
        append(com.arin.selffix.AutoFix.RULE_GENERAL).append("\n\n") // ARIN-PROTECTED: research-first auto-fix rule for every model

        if (!need("build")) {
            append("Building apps: Arin can turn a conversation into an Android APK built on GitHub. Never build or send a <build> ")
            append("tag unless the person orders it; the full build rules are given when they talk about building.\n\n")
        } else {
        append("Building Android apps: Arin can turn a conversation into a complete Android app (Kotlin, Jetpack Compose, ")
        append("Material 3; also large apps with many screens, a database, networking, camera, media, background work), put ")
        append("the source on the person's GitHub and compile it into an APK, fixing build errors automatically.\n")
        append("RULE 1, build only on command: talking about an app is NOT a request to build it. While the person describes, ")
        append("adds or changes what they want, discuss it: confirm what you understood, suggest improvements, ask about what is ")
        append("unclear, and keep track of every point. Do NOT send a <build> tag until the person tells you to build (\"build\", ")
        append("\"build it\", \"make the APK\", \"compile it\", or \"try again\" after a failed build). If they haven't, end with ")
        append("one short line that they can say \"build\" when they're ready. The app ignores a <build> tag sent without that command.\n")
        append("RULE 1b, show the plan first: before the first build of an app, lay out a short, clear plan the person can follow — ")
        append("the screens and main features, the data it keeps, and the libraries it needs — as a short numbered list in plain words, ")
        append("and ask them to say \"build\" when it looks right. Don't send the <build> tag in that planning reply.\n")
        append("RULE 1c, THE TAG IS THE ONLY WAY — never the terminal: to build or compile an app into an APK, OR to push a ")
        append("project to GitHub and get the APK and source-zip links, you MUST use the <build> tag. Build mode does the GitHub ")
        append("push, builds the APK, fixes errors, and posts the APK + source-zip links in THIS chat (and the errors if it fails). ")
        append("You CANNOT do any of this from the terminal: the person's GitHub token lives inside the app, NOT in the terminal, so ")
        append("git push / gh / git clone / gradle / keytool will ALWAYS fail. NEVER run terminal or git commands to build, push or ")
        append("publish — don't look for a token, don't check git config, don't try to clone or push. The terminal and REA are only ")
        append("for inspecting and reverse-engineering apps. When asked to build or push to GitHub, go straight to the <build> tag.\n")
        append("RULE 2, build everything: when the person says build, don't ask more questions. End your reply with ")
        append("<build name=\"App name\">the COMPLETE specification</build>. Go back through the WHOLE conversation and write down ")
        append("every single thing that was asked for or agreed, as a numbered list: every screen, feature, button, option, text, ")
        append("colour, behaviour, data to keep, permission and special case, including small details mentioned only once and ")
        append("corrections made later (the latest version of each point wins). Leave nothing out and don't summarise: a point ")
        append("missing from this list is a feature missing from the app. Arin also passes the conversation itself to the builder.\n")
        append("The build then runs right here in this chat: its steps show live, in plain English, and the APK and source zip links ")
        append("are posted here when done. To change, fix or retry the app built in this chat (\"try again\", \"add dark mode\"), send ")
        append("<build name=\"the same app name\">what to change, or \"try again\"</build>: it continues the same app and GitHub ")
        append("repository instead of starting over. For an uploaded Android Studio project (.zip), use Build mode's Upload. ")
        append("The build setup is already done and tested (Android Gradle plugin, Kotlin, Compose, Gradle, signing, GitHub ")
        append("workflow): don't plan or discuss versions, Gradle files or CI, just describe the app. ")
        append("Never say a build is starting without including the tag. ")
        append("Don't paste whole app sources in chat when they asked for an APK.\n\n")
        }

        if (tools.isNotBlank()) append(tools)

        if (memoryOn) {
            append("Memory: when the person tells you a lasting fact about themselves or a preference for how you should ")
            append("answer, add <remember>the fact, in a short sentence</remember> at the end of your reply. ")
            append(com.arin.selffix.TabIsolation.MEMORY_RULE) // ARIN-PROTECTED: tab-isolation
            append("Never remember passwords, card or ID numbers, or health, money or other sensitive details. ")
            append("Don't mention this tag in your answer.\n")
            if (memories.isNotEmpty()) {
                append("What you know about the person (use it only where it helps):\n")
                memories.forEach { append("- ").append(it.text).append('\n') }
            }
            append('\n')
            if (!need("recall")) {
                append("Earlier in this tab: to look something up from earlier in THIS conversation, reply with ONLY <recall>a few keywords</recall>. Other tabs are never read.\n")
            } else {
            append("Past conversation (THIS TAB ONLY — owner's rule): when the person says \"remember our past conversation\" or refers to " +
                "something from before, it means earlier in THIS tab, never another tab or what was built there. The whole history of " +
                "this tab is saved and you can read it. ")
            append("When they refer to something from before (\"my app\", \"the error we fixed\", \"what you suggested\", ")
            append("\"continue where we left off\", \"do you remember…\"), or it would help to know what was said earlier, reply with ")
            append("ONLY <recall>a few keywords</recall> (topic words that were likely said, not words like \"discussed\"). ")
            append("The app sends you the matching parts of past chats; then answer from them. Never say you can't remember ")
            append("past chats or that you have no memory before recalling.\n")
            // Other tabs' titles are not listed: their conversations are never used here.
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


    /** True when the reply is a real, finished report (long or structured) rather than a status line. */
    fun looksLikeReport(reply: String): Boolean {
        val t = clean(reply).trim()
        return t.length > 700 || t.lines().count { it.trim().isNotEmpty() } >= 8 || t.contains("##") ||
            Regex("(?i)\\b(in summary|in conclusion|overall|to sum up|here (is|are)|the answer is)\\b").containsMatchIn(t)
    }

    /** A short status line ("Got X. Now the Y.", "Searching next.") that isn't a finished report or a question. */
    fun isShortStatus(reply: String): Boolean {
        val t = clean(reply).trim()
        if (t.isEmpty()) return true
        if (t.endsWith("?")) return false
        return !looksLikeReport(reply) && t.length < 450
    }

    /** The command the reply asks to run, if any. */
    fun command(reply: String): String? =
        runTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: invokedRun(reply)

    /** The reply with tool tags taken out, for showing and saving. */
    private val inlineCode = Regex("""`[^`\n]*<[^`\n]*`""")

    /**
     * Tags the model only mentions, like "I never sent the `<build>` tag", are text, not requests: hide such
     * inline-code spans while tags are handled, then put them back.
     */
    private fun <T> withCodeMasked(text: String, block: (String) -> T, restore: (T, (String) -> String) -> T): T {
        val saved = mutableListOf<String>()
        val masked = inlineCode.replace(text) { m -> saved += m.value; "\u0000${saved.size - 1}\u0000" }
        val unmask: (String) -> String = { t -> Regex("\u0000(\\d+)\u0000").replace(t) { saved[it.groupValues[1].toInt()] } }
        return restore(block(masked), unmask)
    }

    /** An unfinished tag at the end of a reply: on its own line, or with attributes, so prose mentions stay. */
    private fun openTail(tag: String) = Regex("""(?:(?m:^)[ \t]*<$tag\b|<$tag\s+\w+=)[\s\S]*$""")

    fun clean(reply: String): String = withCodeMasked(reply, { cleanTags(com.arin.selffix.TeamWork.clean(com.arin.selffix.ClaudeHelper.clean(it))) }) { r, unmask -> unmask(r) }

    private fun cleanTags(reply: String): String =
        reply.replace(rememberTag, "").replace(Regex("""<remember>[^<]*$"""), "")
            .replace(runTag, "").replace(openTail("run"), "")
            .replace(installTag, "").replace(writeTag, "").replace(readTag, "").replace(editTag, "")
            .replace(openTail("write"), "").replace(openTail("edit"), "").replace(openTail("install"), "")
            .replace(toolTag, "").replace(openTail("tool"), "")
            .replace(searchTagAnywhere, "").replace(Regex("""<search>[^<]*$"""), "")
            .replace(buildTag, "").replace(openTail("build"), "")
            .replace(updateTag, "").replace(openTail("update_arin"), "")
            .replace(recallTag, "").replace(Regex("""<recall>[^<]*$"""), "")
            .replace(drawTag, "")
            .replace(imagesTag, "").replace(videosTag, "").replace(fetchTag, "").replace(downloadTag, "")
            .replace(Regex("""<(images|videos|fetch|download)\b[\s\S]*$"""), "")
            .replace(Regex("""</?(run|tool|build|update_arin|images|videos|fetch|download|recall|draw|install|write|read|edit|old|new)\b[^>]*>"""), "")
            .replace(invokeTag, "").replace(dsmlTag, "").replace(dsmlOpen, "")
            .trimEnd()
}
