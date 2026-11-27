package com.arin.selffix

import android.content.Context

/**
 * ARIN-PROTECTED-RULE: team-work — the same three-model team in every tab when worker mode is on (chats, REA, Rebuild,
 * Arin Update; the Build tab has its own boss/worker flow in BuildAgent):
 *
 *  1. Your model (the boss) only directs and checks. It writes short directions, never the commands or long files.
 *  2. The worker (e.g. GPT-6 Luna) writes the commands, new files and research summaries — WITHOUT seeing your chat,
 *     the files or any command output. It may ask the helper (e.g. Claude) when stuck.
 *  3. Arin itself (no AI) runs the commands and shrinks every result before the boss reads it.
 *
 * Only the boss reads results and code. Arin removes code blocks from directions before the worker sees them.
 * No self-update may remove this rule or let the worker/helper see the chat, files or output, unless the owner approves.
 */
object TeamWork {
    fun on(c: Context?): Boolean = Worker.on(c)

    private val doTag = Regex("(?s)<worker_do>(.*?)</worker_do>")
    private val writeTag = Regex("""(?s)<worker_write\s+path\s*=\s*"([^"]+)"\s*>(.*?)</worker_write>""")
    private val openTag = Regex("(?s)<worker_(do|write)\\b.*$")

    /** The boss's direction for the next terminal step, if it wrote one. */
    fun doTask(reply: String): String? = doTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** The boss's direction for a file the worker writes: (path, what it must contain). */
    fun writeTask(reply: String): Pair<String, String>? = writeTag.find(reply)
        ?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() }

    fun clean(reply: String): String = reply.replace(doTag, "").replace(writeTag, "").replace(openTag, "")

    /** What the boss is told in every tab when worker mode is on. */
    fun instructions(c: Context): String {
        if (!on(c)) return ""
        return "TEAM MODE IS ON (saves your tokens — follow it strictly). You are the boss: you direct and check; you do NOT " +
            "write terminal commands, scripts or files yourself — not even short ones. For EVERY terminal step write <worker_do>one short direction: what " +
            "to find, run or change, with the paths and names you know</worker_do> as the LAST thing in your reply and stop. " +
            "The worker writes the command without seeing any file or output; Arin runs it and gives you a shortened result " +
            "(ask for narrower steps — grep, line ranges — if you need more). For a new file or a long rewrite write " +
            "<worker_write path=\"/full/path\">exactly what the file must contain: purpose, names, signatures, behaviour" +
            "</worker_write>; Arin saves what the worker writes and shows you a short view to check. You may still read files " +
            "and make small exact <edit> changes yourself. Never put file contents, code blocks or the person's words into a " +
            "direction (Arin removes code blocks): the worker and the helper must never see them — describe instead. " +
            "Web research is read by the worker and summarised for you.\n\n"
    }

    // ---- Arin itself, no AI ----

    private val toolEnd = Regex("""</(run|worker_do|worker_write|write|edit|install|tool|ask_worker|ask_helper|fetch|download|search|read_source|read)>\s*$|<read\s+path\s*=\s*"[^"]+"[^>]*/>\s*$""")

    /**
     * Speed: true as soon as the reply ends with a complete tool step (outside a code block), so Arin can act on it at
     * once instead of waiting for the model to finish writing.
     */
    fun stepComplete(reply: String): Boolean {
        val tail = reply.takeLast(200)
        if (!toolEnd.containsMatchIn(tail)) return false
        if (Regex("```").findAll(reply).count() % 2 == 1) return false
        val i = reply.lastIndexOf("</")
        return i < 0 || i == 0 || reply[i - 1] != '`'
    }

    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val errLine = Regex("(?i)(error|exception|failed|fatal|denied|not found|cannot|can't|unable|invalid|missing|warning|traceback|exit code)")

    /** Shrinks a tool result for the boss: no colours, repeats collapsed, the middle cut (error lines kept). */
    fun shrink(output: String, max: Int = 3_500): String {
        val src = output.replace(ansi, "").replace("\r", "")
        val lines = mutableListOf<String>()
        var prev: String? = null
        var repeats = 0
        fun flush() { if (repeats > 0) lines += "   (same line repeated $repeats more times)"; repeats = 0 }
        for (raw in src.lines()) {
            val l = raw.trimEnd().let { if (it.length > 400) it.take(400) + " …(line cut)" else it }
            if (l == prev && l.isNotBlank()) { repeats++; continue }
            flush(); lines += l; prev = l
        }
        flush()
        var kept: List<String> = lines
        if (lines.size > 110) {
            val head = lines.take(50)
            val tail = lines.takeLast(40)
            val middle = lines.subList(50, lines.size - 40)
            val errs = middle.filter { errLine.containsMatchIn(it) }.take(20)
            kept = head + listOf("…(${middle.size - errs.size} lines skipped by Arin" + (if (errs.isNotEmpty()) "; their error lines:" else "") + ")") +
                errs + listOf("…") + tail
        }
        val text = kept.joinToString("\n").trim()
        if (text.length <= max) return text
        val h = (max * 0.6).toInt()
        return text.take(h) + "\n…(${text.length - max} characters skipped by Arin — ask for a narrower step if you need them)…\n" + text.takeLast(max - h)
    }

    /**
     * Arin removes `set -e` / `set -o pipefail` (also inside `set -euo pipefail`): with them, a grep or find that simply
     * finds nothing, or a `| head`, makes the whole step look failed.
     */
    fun noStrictShell(cmd: String): String =
        cmd.replace(Regex("""(?m)(^|;|&&|>)[ \t]*set[ \t]+-[euxo]+([ \t]+pipefail)?[ \t]*(;|&&|$)""")) { m ->
            val before = m.groupValues[1]
            if (before == ";" || before == "&&") "$before " else before
        }.replace(Regex("""(;|&&)[ \t]*(;|&&)"""), "$1")

    /**
     * True when a "failed" step really just found nothing: grep/find/diff exit 1 or a closed pipe (141) with no error
     * text in the output. Arin then tells the model "nothing found" instead of researching an error that isn't there.
     */
    fun nothingFound(exitCode: Int?, output: String): Boolean =
        (exitCode == 1 || exitCode == 141) &&
            !Regex("(?i)(error|exception|fatal|denied|invalid|traceback|not found|no such file|cannot|can't|unable|usage:)").containsMatchIn(output)

    /** A direction as the worker may see it: no code blocks, not too long. */
    fun safeDirection(d: String): String = d
        .replace(Regex("(?s)```.*?```"), "[code removed by Arin]")
        .replace(Regex("(?s)```.*$"), "[code removed by Arin]")
        .trim().take(1_500)

    /** An error or query as the worker may see it: no paths, addresses or long numbers. */
    fun scrub(text: String): String = text
        .replace(Regex("""(/[\w.@+$-]+){2,}/?"""), " ")
        .replace(Regex("""0x[0-9a-fA-F]+|\b\d{5,}\b"""), " ")
        .replace(Regex("""[ \t]+"""), " ")
        .trim().take(400)

    /** A short view of a file the worker wrote, for the boss to check: its outline and start. */
    fun preview(content: String, max: Int = 2_500): String {
        val lines = content.lines()
        val outline = lines.withIndex().filter { (_, l) ->
            Regex("""^\s*(?:(?:public|private|internal|protected|static|override|suspend|data|async|export|abstract|open|final)\s+)*(fun|class|object|interface|def|function|const|\.method|\.class|import|package)\b""").containsMatchIn(l)
        }.take(40).joinToString("\n") { (i, l) -> "${i + 1}: ${l.trim().take(160)}" }
        val start = lines.take(25).joinToString("\n")
        return ("${lines.size} lines.\nOUTLINE:\n" + outline.ifBlank { "(none)" } + "\nSTART:\n" + start).take(max)
    }

    // ---- The worker ----

    internal const val COMMAND_SYSTEM = "You are the WORKER. Your supervisor gives you ONE direction for a step on a Linux " +
        "machine (Ubuntu inside an Android app, as root; bash, apt, git, python, node, java, jadx, apktool may be there). Write " +
        "the shell command(s) for it. You can NOT see any files or outputs — the supervisor reads the result. Reply with ONLY " +
        "<run>the commands</run> (one tag; chain with && or a short heredoc script), or <install>package</install> if a tool " +
        "must be installed. Keep the output SMALL: grep -rn with -m limits, head, sed -n ranges, wc -l, ls | head, quiet flags; " +
        "never print whole big files or binaries. NEVER use set -e, set -u or set -o pipefail (a search that finds nothing " +
        "would look like a failure); end searches that may find nothing with || true. If you truly can't, ask the helper: <ask_helper>NEED: …</ask_helper>."

    internal const val WRITE_SYSTEM = "You are the WORKER. Your supervisor tells you what a file must contain. Write the " +
        "COMPLETE file, working and ready to use. Reply with ONLY the file's content in one ``` block — no explanations. " +
        "When stuck, ask the helper: <ask_helper>NEED: …</ask_helper>."

    internal const val DIGEST_SYSTEM = "You are the WORKER. You get web research (search results and pages). Return ONLY " +
        "what the supervisor needs: the likely cause and the exact fix (steps, commands, flags, versions), or the facts asked " +
        "for — at most 12 short lines, then up to 3 source URLs. No chit-chat."

    /**
     * The worker writes the command for [direction]. Returns (worker model, the tool tag to run) or ("", "") if no worker
     * could. The worker sees only the direction (code blocks removed).
     */
    suspend fun command(c: Context, direction: String, boss: String, onStatus: (String, String) -> Unit): Pair<String, String> {
        val (who, out) = kotlinx.coroutines.withTimeoutOrNull(45_000) {
            Worker.run(c, "DIRECTION FROM THE SUPERVISOR:\n" + safeDirection(direction), boss, COMMAND_SYSTEM, onStatus)
        } ?: ("" to "")
        if (who.isBlank()) return "" to ""
        val tag = Regex("""(?s)<run>.*?</run>|<install>.*?</install>""").find(out)?.value
            ?: out.replace(Regex("(?s)^.*?```(?:bash|sh|shell)?\\n"), "").substringBefore("```").trim()
                .takeIf { it.isNotBlank() && it.lines().size <= 40 && !out.contains("<ask_helper>") }?.let { "<run>$it</run>" }
        return if (tag == null) "" to "" else Worker.nice(who) to noStrictShell(tag)
    }

    /** The worker writes the file [path] from the boss's [spec]. Returns (worker model, content) or ("", ""). */
    suspend fun write(c: Context, path: String, spec: String, boss: String, onStatus: (String, String) -> Unit): Pair<String, String> {
        val (who, out) = kotlinx.coroutines.withTimeoutOrNull(150_000) {
            Worker.run(c, "FILE: ${path.substringAfterLast('/')}\nWHAT IT MUST CONTAIN:\n" + safeDirection(spec), boss, WRITE_SYSTEM, onStatus)
        } ?: ("" to "")
        if (who.isBlank() || out.isBlank()) return "" to ""
        val body = if (out.contains("```")) out.substringAfter("```").substringAfter('\n').substringBeforeLast("```") else out
        return Worker.nice(who) to body.trimEnd() + "\n"
    }

    /**
     * The worker reads web research and gives the boss a short summary. [about] is scrubbed of paths first. Falls back to
     * Arin's mechanical shrink when no worker answers.
     */
    suspend fun digest(c: Context, about: String, web: String, boss: String, scrubAbout: Boolean = true, onStatus: (String, String) -> Unit = { _, _ -> }): String {
        if (web.isBlank()) return web
        // Speed: the worker gets 30 s; if it's slower, Arin shortens the research itself instead.
        val (who, out) = kotlinx.coroutines.withTimeoutOrNull(30_000) {
            Worker.run(c, "ABOUT: ${if (scrubAbout) scrub(about) else about.take(400)}\n\nRESEARCH:\n" + web.take(16_000), boss, DIGEST_SYSTEM, onStatus)
        } ?: ("" to "")
        return if (who.isBlank() || out.isBlank()) shrink(web, 3_000) else "[Research read and summarised by ${Worker.nice(who)}:]\n" + out.take(2_500)
    }
}
