package com.arin.claudecode

import com.arin.llm.Delta
import com.arin.llm.LlmException
import com.arin.llm.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext

/**
 * The person's Claude Code login (Claude Pro/Max) used as a normal chat model in every tab: Chat, Build, Arin Update,
 * Movie… It runs `claude -p` once per reply with no tools of its own (Arin's own tools — run, search, build — still work
 * through the reply's tags), in its own throwaway folder, so the Claude Code tab's conversation is never touched.
 */
object ClaudeCodeModel {
    private const val DIR = "/root/.arin/ccm"
    private val CC_TOOLS = listOf(
        "Bash", "Edit", "Write", "MultiEdit", "Glob", "Grep", "WebFetch", "WebSearch", "TodoWrite", "NotebookEdit",
        "Task", "Agent", "Skill", "SlashCommand", "KillShell", "BashOutput", "ExitPlanMode",
    )

    fun stream(model: String, system: String, turns: List<Turn>): Flow<Delta> = channelFlow {
        val context = com.arin.ArinApp.instance
        if (!com.arin.terminal.Linux.installed(context)) throw LlmException("The Claude Code login needs Arin's Linux: Settings → Linux → Install Linux.")
        if (!ClaudeCode.connected(context)) throw LlmException("Sign in to Claude in the Claude Code tab first, or pick another AI from the model menu.")
        val id = System.currentTimeMillis().toString() + (1000..9999).random()
        val root = com.arin.terminal.Linux.rootfs(context)
        val dir = java.io.File(root, "$DIR/$id".removePrefix("/")).apply { mkdirs() }
        // Pictures and PDFs: saved where Claude can open them with its Read tool.
        var n = 0
        fun save(ext: String, b64: String): String {
            n++
            val f = java.io.File(dir, "file$n.$ext")
            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            return "$DIR/$id/${f.name}"
        }
        // ARIN-PROTECTED: claude-lean — Claude gets only Arin's instructions as its system prompt (Claude Code's own long
        // instructions and tool list are left out) and just the Read tool: about what a message in the Claude app costs.
        java.io.File(dir, "sys.txt").writeText(system.take(60_000))
        val convo = buildString {
            append("THE CONVERSATION SO FAR (reply as the assistant to the last USER message; write only your reply):\n\n")
            turns.forEach { t ->
                append(if (t.fromUser) "USER:\n" else "ASSISTANT:\n").append(t.text)
                val files = t.images.map { save(it.mime.substringAfter('/').substringBefore('+').ifBlank { "png" }, it.base64) } +
                    t.pdfs.map { save("pdf", it.base64) }
                if (files.isNotEmpty()) append("\n[Attached files — open them with the Read tool: ").append(files.joinToString(", ")).append("]")
                append("\n\n")
            }
        }
        java.io.File(dir, "prompt.txt").writeText(convo)
        java.io.File(root, "$DIR/home/.claude".removePrefix("/")).mkdirs()
        java.io.File(root, "$DIR/home/.claude.json".removePrefix("/")).takeIf { !it.exists() }?.writeText("{\"hasCompletedOnboarding\":true}")
        // "sonnet@<account>": that account's own sign-in (it used to always use the main one, so switching accounts
        // didn't help when the main account was out of credit).
        val m = ClaudeAccounts.modelOf(model).ifBlank { "sonnet" }.replace("'", "")
        val tokenFile = ClaudeAccounts.tokenFileFor(context, model)
        val out = java.io.File(dir, "out.jsonl")
        val cmd = "cd $DIR/$id; unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " +
            "export CLAUDE_CODE_OAUTH_TOKEN=\$(cat $tokenFile 2>/dev/null) DISABLE_AUTOUPDATER=1 " +
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 DISABLE_TELEMETRY=1 HOME=$DIR/home; " +
            "timeout 1800 claude -p --model '$m' --output-format stream-json --verbose --include-partial-messages " +
            "--tools Read --system-prompt \"\$(cat $DIR/$id/sys.txt)\" " +
            "--add-dir $DIR/$id --allowedTools Read --disallowedTools ${CC_TOOLS.joinToString(" ")} " +
            "< $DIR/$id/prompt.txt > $DIR/$id/out.jsonl 2>&1"
        val job = async(Dispatchers.IO) { com.arin.terminal.Shell.run(context, cmd, 31 * 60_000L) }
        var read = 0L
        var sent = false
        var resultText: String? = null
        var errorText: String? = null
        fun handle(line: String) {
            val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return
            when (o.optString("type")) {
                "stream_event" -> {
                    val d = o.optJSONObject("event")?.optJSONObject("delta") ?: return
                    when (d.optString("type")) {
                        "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let { com.arin.builder.LiveFeed.stream("Claude", it); sent = true; trySend(Delta.Text(it)) }
                        "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }?.let { trySend(Delta.Thinking(it)) }
                    }
                }
                "result" -> {
                    if (o.optBoolean("is_error")) errorText = o.optString("result").ifBlank { o.optString("subtype") }
                    else resultText = o.optString("result")
                }
            }
        }
        try {
            while (true) {
                val finished = job.isCompleted
                withContext(Dispatchers.IO) {
                    if (out.exists() && out.length() > read) {
                        java.io.RandomAccessFile(out, "r").use { f ->
                            f.seek(read)
                            val bytes = ByteArray((f.length() - read).toInt()); f.readFully(bytes)
                            val s = String(bytes, Charsets.UTF_8)
                            val end = s.lastIndexOf('\n')
                            if (end >= 0) {
                                s.substring(0, end).lineSequence().forEach { handle(it.trim()) }
                                read += s.substring(0, end + 1).toByteArray(Charsets.UTF_8).size
                            }
                        }
                    }
                }
                if (finished) break
                delay(250)
            }
            // The last line may have no newline.
            withContext(Dispatchers.IO) { if (out.exists()) out.readText().drop(read.toInt()).lineSequence().forEach { handle(it.trim()) } }
            if (!sent && !resultText.isNullOrBlank()) { sent = true; send(Delta.Text(resultText!!)) }
            if (!sent) {
                val raw = runCatching { out.readText() }.getOrDefault("").takeLast(600)
                val why = errorText ?: raw.ifBlank { job.await().stderr.takeLast(400) }
                if (ClaudeLimit.isLimit(why)) throw LlmException("CLAUDE_LIMIT: " + ClaudeLimit.hit(context, ClaudeAccounts.accountOf(model), why))
                throw LlmException("Claude (Claude Code login) didn't answer: " + why)
            }
        } finally {
            if (!job.isCompleted) runCatching { com.arin.terminal.Shell.run(context, "pkill -f '[a]dd-dir $DIR/$id'  2>/dev/null; echo ok", 20_000) }
            job.cancel()
            runCatching { dir.deleteRecursively() }
        }
    }
}
