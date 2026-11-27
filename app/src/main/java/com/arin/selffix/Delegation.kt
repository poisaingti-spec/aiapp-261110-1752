package com.arin.selffix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ARIN-PROTECTED: delegation-firewall — every request to a supporting model (the worker/middle model such as Claude,
 * and the helper) passes through here. The main model owns the conversation; the others get only a task brief:
 *  - API keys, tokens, passwords and private keys are redacted (all saved secrets + common secret formats),
 *  - lines that look like a conversation transcript (User:/Assistant: …) are removed,
 *  - passages copied from the person's own messages are removed (their words go only to the main model),
 *  - only the project requirements relevant to this task are added (never the whole store),
 *  - an audit record says what kind of information was shared — without storing the content.
 * The pure parts ([Brief]) have unit tests.
 */
object Delegation {
    /** Pure, testable rules. */
    object Brief {
        private val patterns = listOf(
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
            Regex("""\bsk-ant-[A-Za-z0-9_\-]{16,}"""),
            Regex("""\bsk-(?:proj-)?[A-Za-z0-9_\-]{20,}"""),
            Regex("""\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""),
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
            Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
            Regex("""\bxox[abposr]-[A-Za-z0-9\-]{10,}"""),
            Regex("""\bhf_[A-Za-z0-9]{20,}"""),
            Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{16,}"""),
            Regex("""(?m)^([A-Z0-9_]*(?:KEY|TOKEN|SECRET|PASSWORD|PASS)[A-Z0-9_]*)(\s*=\s*)()[^\s$]{8,}$"""),
            // Last: a quoted value given to a secret-named setting (code such as prefs.getString(...) isn't touched).
            Regex("""(?i)\b(api[_-]?key|apikey|secret|token|password|passwd|access[_-]?key|client[_-]?secret)(\s*[:=]\s*)(["'])[^"'\s$(){}]{8,}\3"""),
        )
        /** Every hidden value becomes "[REDACTED-1]", "[REDACTED-2]"…: the same number for the same value. */
        const val MASK = "[REDACTED"

        /** Replaces every known secret value and every secret-looking string. Returns the text and how many were hidden. */
        fun redact(text: String, known: Collection<String> = emptyList()): Pair<String, Int> = redactMap(text, known).let { it.first to it.second.size }

        /**
         * Like [redact], and also returns the hidden values (in memory only, never logged) so a model's answer can get
         * them back with [restore] — e.g. the owner's own API key in their app's code, which the worker never sees.
         */
        fun redactMap(text: String, known: Collection<String> = emptyList()): Pair<String, List<String>> {
            val found = mutableListOf<String>()
            fun mask(v: String): String {
                val i = found.indexOf(v).let { if (it >= 0) it else { found += v; found.lastIndex } }
                return "[REDACTED-${i + 1}]"
            }
            var t = text
            known.filter { it.length >= 8 }.sortedByDescending { it.length }.forEach { v -> if (t.contains(v)) t = t.replace(v, mask(v)) }
            for ((i, p) in patterns.withIndex()) {
                t = p.replace(t) { m ->
                    if (m.value.contains(MASK)) m.value
                    else if (i >= patterns.lastIndex - 1) {
                        val head = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
                        val value = m.value.removePrefix(head).removeSuffix(m.groupValues[3])
                        head + mask(value) + m.groupValues[3]
                    } else mask(m.value)
                }
            }
            return t to found
        }

        /** Puts the hidden values back into a model's answer. */
        fun restore(text: String, secrets: List<String>): String =
            secrets.foldIndexed(text) { i, acc, v -> acc.replace("[REDACTED-${i + 1}]", v) }

        // A transcript turn starts at the beginning of the line with a capitalised speaker ("User: …", "**Assistant:** …").
        // Indented code such as `user: User,` is never matched.
        private val turn = Regex("""^(?:\*\*)?(?:User|Human|Owner|Person|The person|Me|You|Assistant|AI|Arin|Claude|DeepSeek)(?:\*\*)?\s*(?:\(\w+\))?\s*:(?:\*\*)?\s""")

        /**
         * Removes conversation-transcript lines from a task brief. Code and build logs
         * are kept: a transcript line is only one that starts with a speaker name and a colon.
         */
        fun stripConversation(text: String): Pair<String, Int> {
            val out = StringBuilder(); var removed = 0; var inCode = false
            for (line in text.lines()) {
                if (line.trimStart().startsWith("```")) { inCode = !inCode; out.appendLine(line); continue }
                if (!inCode && turn.containsMatchIn(line)) { removed++; continue }
                out.appendLine(line)
            }
            return out.toString().trimEnd() to removed
        }

        // ---- The owner's own words stay with the main model ----
        private val word = Regex("""[\p{L}\p{N}'’]+""")
        /** A line that is code, a build log or a stack trace (technical material, not the person's own words). */
        private fun technical(line: String): Boolean {
            val t = line.trim()
            return t.isEmpty() || Regex("""^(e: |w: |at |Caused by|import |package |fun |val |var |class |object |@|//|<|\*|#|\$ |> |\d+[:|])""").containsMatchIn(t) ||
                Regex("""[{};]|\)\s*\{|=>|->|::|\w+\(.*\)|\w+\.\w+\.\w+|\.kt\b|\.xml\b|Exception|Error:""").containsMatchIn(t) && t.count { !it.isLetterOrDigit() && it != ' ' } * 6 > t.length / 2
        }

        private const val SHINGLE = 8

        /**
         * ARIN-PROTECTED: owner-words-only-to-main — removes from a brief every passage copied from the person's own
         * messages: any run of [SHINGLE]+ words in a row, and a whole short message (25+ characters) copied word for word.
         * Code, logs and stack traces the person pasted stay (technical material); their own sentences don't. Each removed
         * passage becomes one "[…]" and the count is returned.
         */
        fun withoutOwnerText(text: String, ownerTexts: Collection<String>): Pair<String, Int> {
            if (ownerTexts.isEmpty() || text.isBlank()) return text to 0
            val norm = { s: String -> s.lowercase().replace('’', '\'') }
            // The person's natural-language lines (technical lines are left out of the comparison).
            val prose = ownerTexts.flatMap { it.lines() }.filterNot { technical(it) }
            val shingles = HashSet<String>()
            val shortWhole = mutableListOf<String>()
            for (line in prose) {
                val ws = word.findAll(norm(line)).map { it.value }.toList()
                if (ws.size >= SHINGLE) for (i in 0..ws.size - SHINGLE) shingles += ws.subList(i, i + SHINGLE).joinToString(" ")
                else if (line.trim().length >= 25) shortWhole += ws.joinToString(" ")
            }
            if (shingles.isEmpty() && shortWhole.isEmpty()) return text to 0
            val out = StringBuilder(); var removed = 0
            for (line in text.split("\n")) {
                if (technical(line)) { out.append(line).append('\n'); continue }
                val ms = word.findAll(line).toList()
                val ws = ms.map { norm(it.value) }
                val cut = BooleanArray(ws.size)
                for (i in 0..ws.size - SHINGLE) if (ws.subList(i, i + SHINGLE).joinToString(" ") in shingles) for (k in i until i + SHINGLE) cut[k] = true
                // A whole short message copied (e.g. "make the background dark blue please").
                val lineWords = ws.joinToString(" ")
                for (sw in shortWhole) {
                    val at = Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(sw) + """(?![\p{L}\p{N}])""").find(lineWords) ?: continue
                    val first = lineWords.substring(0, at.range.first).split(' ').count { it.isNotEmpty() }
                    val n = sw.split(' ').size
                    for (k in first until minOf(ws.size, first + n)) cut[k] = true
                }
                if (cut.none { it }) { out.append(line).append('\n'); continue }
                val sb = StringBuilder(); var pos = 0; var i = 0
                while (i < ms.size) {
                    if (!cut[i]) { i++; continue }
                    var j = i; while (j + 1 < ms.size && cut[j + 1]) j++
                    sb.append(line, pos, ms[i].range.first).append("[…]")
                    pos = ms[j].range.last + 1
                    // drop closing punctuation of the removed passage
                    while (pos < line.length && line[pos] in ".,!?;:\"'’”)") pos++
                    removed++; i = j + 1
                }
                sb.append(line.substring(pos))
                out.append(sb).append('\n')
            }
            return out.toString().removeSuffix("\n") to removed
        }

        private val fileRef = Regex("""(?<![\w.-])((?:[\w.-]+/)*[\w.-]+\.(?:kt|kts|java|xml|gradle|toml|json|pro|properties|yml|yaml|md))\b""")

        fun files(text: String): List<String> = fileRef.findAll(text).map { it.groupValues[1] }.distinct().take(40).toList()

        fun categories(text: String): List<String> = buildList {
            if (Regex("""(?m)^\s*(fun |class |val |var |import |package |@Composable|<\w+[ >])""").containsMatchIn(text)) add("source code")
            if (Regex("""(?i)\b(e: |error:|exception|unresolved reference|build failed)""").containsMatchIn(text)) add("build errors")
            if (files(text).isNotEmpty()) add("file references")
            if (text.contains("PROJECT REQUIREMENTS")) add("project requirements")
            if (Regex("""(?i)(stackoverflow|github\.com/|search results|from the internet)""").containsMatchIn(text)) add("web research")
            if (isEmpty()) add("task description")
        }

        /** Requirements that share words with the task (the minimum needed), never the whole store. */
        fun relevant(requirements: List<String>, task: String, max: Int = 6): List<String> {
            val words = Regex("[a-zA-Z]{4,}").findAll(task.lowercase()).map { it.value }.toSet()
            return requirements.map { r -> r to Regex("[a-zA-Z]{4,}").findAll(r.lowercase()).count { it.value in words } }
                .filter { it.second >= 2 }.sortedByDescending { it.second }.take(max).map { it.first }
        }

        /**
         * The brief actually sent: transcript lines out, the person's own words out, secrets out, relevant
         * requirements in (requirements are the main model's restatement, never the person's text).
         */
        fun build(task: String, known: Collection<String>, requirements: List<String> = emptyList(), ownerTexts: Collection<String> = emptyList()): Built {
            val (noChat, turns) = stripConversation(task)
            val (noOwner, ownerRemoved) = withoutOwnerText(noChat, ownerTexts)
            val req = relevant(requirements, noOwner).map { withoutOwnerText(it, ownerTexts).first }.filter { !it.contains("[…]") }
            val withReq = if (req.isEmpty()) noOwner else noOwner + "\n\nPROJECT REQUIREMENTS RELEVANT TO THIS TASK (keep them):\n" + req.joinToString("\n") { "- $it" }
            val (clean, secrets) = redactMap(withReq, known)
            return Built(clean, secrets.size, turns, req.size, files(clean), categories(clean), ownerRemoved, secrets)
        }
    }

    data class Built(val text: String, val secretsHidden: Int, val transcriptLinesRemoved: Int, val requirementsShared: Int,
                     val files: List<String>, val categories: List<String>, val ownerPassagesRemoved: Int = 0,
                     /** The hidden values, in memory only (never written to the audit). */
                     val secrets: List<String> = emptyList()) {
        override fun toString() = "Built(${text.length} chars, secretsHidden=$secretsHidden)"
    }

    private const val MAX_RECORDS = 500
    private fun log(c: Context) = java.io.File(c.filesDir, "delegation-audit.jsonl")

    /** Saved secrets (API keys, GitHub token, Claude tokens on file) — so they are never sent to another model. */
    private fun knownSecrets(c: Context): List<String> = runCatching { com.arin.data.Settings(c).secretValues() }.getOrDefault(emptyList()) +
        runCatching {
            java.io.File(c.filesDir, "claude").walkTopDown().maxDepth(3).filter { it.isFile && it.name.contains("token") && it.length() in 20..4096 }
                .map { it.readText().trim() }.filter { it.length >= 16 }.toList()
        }.getOrDefault(emptyList())

    /**
     * Gates one delegation. [dest]: who gets it ("worker", "helper:Claude"…). [tab]: the conversation tab it belongs to.
     * [project]: the project key whose relevant requirements may be added. Returns the brief to send and the record id.
     */
    fun gate(c: Context, dest: String, task: String, tab: String = "", project: String? = null, requirements: List<String> = emptyList(),
             ownerTexts: Collection<String> = emptyList()): Pair<String, String> =
        gateFull(c, dest, task, tab, project, requirements, ownerTexts).let { it.first to it.second }

    /** [gate], also returning the hidden values so the answer can get them back ([Brief.restore]). */
    fun gateFull(c: Context, dest: String, task: String, tab: String = "", project: String? = null, requirements: List<String> = emptyList(),
                 ownerTexts: Collection<String> = emptyList()): Triple<String, String, List<String>> {
        val b = Brief.build(task, knownSecrets(c), requirements, ownerTexts)
        val id = "d" + System.currentTimeMillis().toString(36) + (0..999).random().toString(36)
        record(c, JSONObject()
            .put("id", id).put("time", System.currentTimeMillis()).put("to", dest).put("tab", tab).put("project", project ?: "")
            .put("task", "t" + (task.take(400).hashCode().toUInt().toString(36))) // an identifier, not the content
            .put("files", JSONArray(b.files)).put("shared", JSONArray(b.categories)).put("chars", b.text.length)
            .put("secretsHidden", b.secretsHidden).put("transcriptLinesRemoved", b.transcriptLinesRemoved)
            .put("requirementsShared", b.requirementsShared).put("ownerPassagesRemoved", b.ownerPassagesRemoved).put("status", "sent"))
        return Triple(b.text, id, b.secrets)
    }

    /**
     * ARIN-PROTECTED: refusal-provenance — how a delegation ended ("done", "refused", "failed"), with WHO actually
     * answered (role + provider/model), the outcome class, the model's own stated reason (secrets redacted, at most 300
     * characters) and how long it took. Only recorded from the real answer, never guessed.
     */
    fun finish(c: Context, id: String, status: String, who: String = "", role: String = "", verdict: Outcome.Verdict? = null,
               ms: Long = 0, attempts: List<JSONObject> = emptyList()) = record(c, JSONObject().put("id", id)
        .put("time", System.currentTimeMillis()).put("status", status)
        .apply {
            if (who.isNotBlank()) put("who", who)
            if (role.isNotBlank()) put("role", role)
            verdict?.let { put("outcome", it.kind.name); if (it.reason.isNotBlank()) put("reason", Brief.redact(it.reason).first.take(300)); if (it.partial) put("partial", true) }
            if (ms > 0) put("ms", ms)
            if (attempts.isNotEmpty()) put("attempts", JSONArray(attempts))
        })

    /** Notes a later step of a delegation (e.g. "boss-informed", "next-worker-succeeded"). */
    fun note(c: Context, id: String, event: String) = record(c, JSONObject().put("id", id).put("time", System.currentTimeMillis()).put("event", event))

    @Synchronized private fun record(c: Context, o: JSONObject) = runCatching {
        val f = log(c)
        f.appendText(o.toString() + "\n")
        if (f.length() > 400_000) f.writeText(f.readLines().takeLast(MAX_RECORDS).joinToString("\n", postfix = "\n"))
    }

    /** The latest records, newest first, for the audit screen / report. */
    fun recent(c: Context, max: Int = 50): List<JSONObject> = runCatching {
        log(c).readLines().takeLast(max * 2).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.reversed()
    }.getOrDefault(emptyList())
}
