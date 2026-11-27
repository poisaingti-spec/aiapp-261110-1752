package com.arin.claudecode

import android.content.Context
import com.arin.llm.Delta
import com.arin.llm.LlmException
import com.arin.llm.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * ARIN-PROTECTED: claude-harness — the Claude Code harness for every tab, used the way the Claude app uses your limit.
 *
 * One Claude Code process stays running per conversation (stream-json in and out, through a pipe), so each new message
 * sends only what is new and the rest comes from the prompt cache — instead of starting Claude Code again for every
 * step and resending the whole chat. Claude Code's own tools (Bash, Read, Edit, Write, Glob, Grep, web) and Arin's
 * tools (through ArinMcp) are both available. Long tool output is cut short, and Claude Code compacts long chats itself.
 * Real plan usage (the same 5-hour and weekly numbers as the Claude app) comes from Claude Code's rate-limit events.
 */
object Harness {
    private const val BASE = "/root/.arin/h"
    /** Claude sessions running at once (each uses 200–400 MB): more stay saved and resume when needed. */
    private const val MAX_LIVE = 2
    private const val TOOLS = "Bash,Read,Edit,Write,Glob,Grep,WebFetch,WebSearch,TodoWrite"

    /** Arin's tools + the person's MCP servers (config JSON, server names) and GitHub token; set by the app. */
    @Volatile var extras: (() -> Triple<String, List<String>, String>)? = null

    private class Session(val dir: String, val key: String, val model: String, val tokenFile: String, val account: String, val custom: Custom? = null, val kind: String = "") {
        var sessionId: String? = null
        val sentUser = mutableListOf<Int>()   // what this session already has: the person's / tool turns, in order
        var lastUse = System.currentTimeMillis()
        var readFrom = 0L
        val lock = Mutex()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val bridgePort = ConcurrentHashMap<String, Int>()

    private fun ctx(): Context = com.arin.ArinApp.instance
    private fun root(): File = com.arin.terminal.Linux.rootfs(ctx())
    private fun f(path: String) = File(root(), path.removePrefix("/"))

    private fun turnHash(t: Turn) = (t.text.hashCode() * 31 + t.images.size) * 31 + t.pdfs.size

    /**
     * A reply through the harness. [purpose] keeps different users apart ("chat", "helper:build"…); the conversation is
     * recognised by its first message, so the next step of the same conversation reuses the running session.
     */
    /** A Custom API as the harness's brain: [anthropic] = it speaks Claude's format, else OpenAI's (through Arin's bridge). */
    data class Custom(val base: String, val key: String, val anthropic: Boolean)

    // Off unless switched on (Settings → Storage): a Custom API answers directly, which starts instantly; the harness
    // adds a whole Claude Code process and a translator in between, which made replies slow.
    fun customOn(c: Context) = com.arin.data.Settings(c).secret("HARNESS_CUSTOM") == "on"
    fun setCustomOn(c: Context, on: Boolean) = com.arin.data.Settings(c).setSecret("HARNESS_CUSTOM", if (on) "on" else "off")

    fun stream(model: String, system: String, turns: List<Turn>, purpose: String = "chat", tools: String = TOOLS, custom: Custom? = null): Flow<Delta> = channelFlow {
        val context = ctx()
        if (!com.arin.terminal.Linux.installed(context)) throw LlmException("The Claude Code login needs Arin's Linux: Settings → Linux → Install Linux.")
        if (custom != null) {
            if (!ClaudeCode.installed(context)) throw LlmException("Claude Code isn't installed")
            if (!custom.anthropic) bridgePort[custom.base + custom.key.hashCode()] = ClaudeCode.ensureBridge(context, custom.base, custom.key)
                ?: throw LlmException("Arin's bridge didn't start")
        }
        // "sonnet@<account>": that account's own sign-in (several Claude accounts, each picked per chat).
        val account = ClaudeAccounts.accountOf(model)
        val tokenFile = ClaudeAccounts.tokenFileFor(context, model)
        if (custom == null && !f(tokenFile).let { it.exists() && it.length() > 20 }) throw LlmException("Sign in to Claude in the Claude Code tab first, or pick another AI from the model menu.")
        val m = ClaudeAccounts.modelOf(model).ifBlank { "sonnet" }
        val firstUser = turns.firstOrNull { it.fromUser } ?: throw LlmException("Nothing to answer.")
        val kind = "$purpose|$m|${custom?.base ?: account ?: "main"}"
        val userTurns = turns.filter { it.fromUser }
        val hashes = userTurns.map(::turnHash)

        // The session that already has THIS conversation (everything it saw is the start of these turns). Two chats that
        // both began with "Hi" are different conversations: each gets its own session, and neither ever stops the other.
        var s: Session? = sessions.values.filter { it.kind == kind && it.sentUser.isNotEmpty() && it.sentUser.size < hashes.size &&
            hashes.subList(0, it.sentUser.size) == it.sentUser }.maxByOrNull { it.sentUser.size }
        val fresh = s == null
        if (fresh) {
            val dir = "$BASE/${System.currentTimeMillis()}${(100..999).random()}"
            s = Session(dir, dir, m, tokenFile, account ?: "main", custom, kind)
            sessions[dir] = s
        }
        val session = s!!
        // A turn still stuck on this session (e.g. its process hung): end it, so this one isn't left waiting behind it.
        if (!session.lock.tryLock()) { stop(session, keepSession = true); session.lock.lock() }
        try {
            session.lastUse = System.currentTimeMillis()
            val newTurns = if (fresh) turns else userTurns.drop(session.sentUser.size)
            val content = JSONArray()
            val text = if (fresh && turns.size > 1) buildString {
                append("THE CONVERSATION SO FAR (you are the assistant; reply to the last message):\n\n")
                turns.dropLast(1).forEach { t -> append(if (t.fromUser) "USER:\n" else "ASSISTANT:\n").append(cap(t.text)).append("\n\n") }
                append("LATEST MESSAGE:\n").append(cap(turns.last().text))
            } else newTurns.joinToString("\n\n") { cap(it.text) }
            newTurns.forEach { t ->
                t.images.forEach { img ->
                    content.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64")
                        .put("media_type", img.mime.ifBlank { "image/png" }).put("data", img.base64)))
                }
                t.pdfs.forEach { p ->
                    content.put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64")
                        .put("media_type", "application/pdf").put("data", p.base64)))
                }
            }
            content.put(JSONObject().put("type", "text").put("text", text.ifBlank { "Continue." }))
            val line = JSONObject().put("type", "user").put("message", JSONObject().put("role", "user").put("content", content)).toString() + "\n"

            if (!alive(session)) start(session, system, tools)
            val out = f("${session.dir}/out.jsonl")
            session.readFrom = withContext(Dispatchers.IO) { if (out.exists()) out.length() else 0L }
            send(session, line)
            session.sentUser.clear(); session.sentUser.addAll(hashes)

            // Read this turn's events until its result.
            var sentText = false
            var newBlock = false
            val thinkSplit = ThinkSplit()
            var finished = false
            var waited = 0L
            var quiet = 0L
            var retries = 0
            var toolRunning = false // a command of Claude Code's is running (it stays silent meanwhile)
            try {
                while (!finished) {
                    val lines = withContext(Dispatchers.IO) { readNew(session, out) }
                    if (lines.isEmpty()) quiet += 150 else quiet = 0
                    for (l in lines) {
                        val o = runCatching { JSONObject(l) }.getOrNull() ?: continue
                        when (o.optString("type")) {
                            "system" -> {
                                o.optString("session_id").takeIf { it.isNotBlank() }?.let { session.sessionId = it }
                                // The model's service keeps failing: stop early instead of waiting through every retry.
                                if (o.optString("subtype") == "api_retry" && ++retries >= 3 && !sentText)
                                    throw LlmException("The model's service isn't answering (${o.optString("error").ifBlank { "retrying" }.take(120)})")
                            }
                            "stream_event" -> {
                                val ev = o.optJSONObject("event") ?: continue
                                when (ev.optString("type")) {
                                    "message_start" -> if (sentText) newBlock = true
                                    "content_block_delta" -> {
                                        val d = ev.optJSONObject("delta") ?: continue
                                        when (d.optString("type")) {
                                            "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }?.let {
                                                if (newBlock) { send(Delta.Text("\n\n")); newBlock = false }
                                                // Some models (DeepSeek and others) write their reasoning as <think>…</think>
                                                // inside the answer: it goes to the reasoning, not the reply.
                                                com.arin.builder.LiveFeed.stream("Claude", it)
                                                for ((isThought, part) in thinkSplit.feed(it)) {
                                                    if (isThought) send(Delta.Thinking(part)) else { sentText = true; send(Delta.Text(part)) }
                                                }
                                            }
                                            "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }?.let { com.arin.builder.LiveFeed.stream("Claude (thinking)", it); send(Delta.Thinking(it)) }
                                        }
                                    }
                                }
                            }
                            "assistant" -> {
                                // Tool steps show in the reasoning, so the person sees what Claude is doing.
                                val c = o.optJSONObject("message")?.optJSONArray("content") ?: continue
                                for (i in 0 until c.length()) {
                                    val b = c.optJSONObject(i) ?: continue
                                    if (b.optString("type") == "tool_use") { toolRunning = true; com.arin.builder.LiveFeed.add("Claude ▸", describe(b)); send(Delta.Thinking("\n▸ " + describe(b) + "\n")) }
                                }
                            }
                            "user" -> toolRunning = false // the tool's result came back
                            "rate_limit_event" -> UsageMeter.fromRateLimit(o.optJSONObject("rate_limit_info"), session.account)
                            "result" -> {
                                finished = true
                                o.optString("session_id").takeIf { it.isNotBlank() }?.let { session.sessionId = it }
                                UsageMeter.fromResult(o)
                                if (o.optBoolean("is_error") && !sentText) {
                                    val msg = o.optString("result").ifBlank { o.optString("subtype") }
                                    if (ClaudeLimit.isLimit(msg)) throw LlmException("CLAUDE_LIMIT: " + ClaudeLimit.hit(context, session.account.takeIf { it != "main" }, msg))
                                    throw LlmException("Claude: " + msg.take(600))
                                }
                                if (!sentText) o.optString("result").takeIf { it.isNotBlank() }?.let { sentText = true; send(Delta.Text(it)) }
                            }
                            "arin_end" -> if (!finished) {
                                val tail = withContext(Dispatchers.IO) { runCatching { out.readText().takeLast(800) }.getOrDefault("") }
                                throw LlmException("Claude Code stopped: " + tail.lineSequence().filter { !it.startsWith("{") }.joinToString(" ").take(500).ifBlank { "no answer" })
                            }
                        }
                    }
                    if (!finished) {
                        delay(150); waited += 150
                        if (waited > 40 * 60_000L) throw LlmException("The model took too long to answer.")
                        // Every few seconds: is the process still there? A dead one never answers.
                        if (waited % 3_000L == 0L && !withContext(Dispatchers.IO) { alive(session) })
                            throw LlmException("Claude Code stopped before answering")
                        // Nothing at all for 3 minutes (not even "thinking"): it's stuck.
                        if (quiet > (if (toolRunning) 30 else 3) * 60_000L) throw LlmException("No answer from the model for a long time")
                    }
                }
            } catch (e: Throwable) {
                // Stopped or failed mid-turn: end the process (the session resumes next time) so it doesn't keep working.
                if (!finished) withContext(NonCancellable) { stop(session, keepSession = true) }
                throw e
            }
            session.lastUse = System.currentTimeMillis()
        } finally { session.lock.unlock() }
        trim()
    }

    /** Splits streamed text into (reasoning?, text) parts at <think> and </think>, even when a tag arrives in pieces. */
    private class ThinkSplit {
        private var inside = false
        private var held = ""
        fun feed(chunk: String): List<Pair<Boolean, String>> {
            var s = held + chunk
            held = ""
            val out = mutableListOf<Pair<Boolean, String>>()
            while (s.isNotEmpty()) {
                val tag = if (inside) "</think>" else "<think>"
                val i = s.indexOf(tag)
                if (i >= 0) {
                    if (i > 0) out += inside to s.substring(0, i)
                    inside = !inside
                    s = s.substring(i + tag.length)
                    continue
                }
                // Keep a possible half tag at the end for the next chunk.
                val keep = (1 until tag.length).lastOrNull { s.endsWith(tag.substring(0, it)) } ?: 0
                if (s.length > keep) out += inside to s.substring(0, s.length - keep)
                held = s.takeLast(keep)
                break
            }
            return out
        }
    }

    private fun cap(t: String): String = if (t.length <= 24_000) t else t.take(16_000) + "\n…(cut: ${t.length - 22_000} characters)…\n" + t.takeLast(6_000)

    private fun describe(b: JSONObject): String {
        val name = b.optString("name").removePrefix("mcp__arin__").removePrefix("mcp__")
        val inp = b.optJSONObject("input") ?: JSONObject()
        val what = inp.optString("command").ifBlank { inp.optString("description") }.ifBlank { inp.optString("file_path") }
            .ifBlank { inp.optString("pattern") }.ifBlank { inp.optString("url") }.ifBlank { inp.optString("query") }
        return (name + if (what.isNotBlank()) ": " + what.take(140) else "")
    }

    private fun readNew(s: Session, out: File): List<String> {
        if (!out.exists() || out.length() <= s.readFrom) return emptyList()
        java.io.RandomAccessFile(out, "r").use { r ->
            r.seek(s.readFrom)
            val bytes = ByteArray((r.length() - s.readFrom).toInt()); r.readFully(bytes)
            val str = String(bytes, Charsets.UTF_8)
            val end = str.lastIndexOf('\n')
            if (end < 0) return emptyList()
            s.readFrom += str.substring(0, end + 1).toByteArray(Charsets.UTF_8).size
            return str.substring(0, end).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun alive(s: Session): Boolean {
        val pid = runCatching { f("${s.dir}/pid").readText().trim().toInt() }.getOrNull() ?: return false
        if (!File("/proc/$pid").exists()) return false
        return runCatching {
            java.io.RandomAccessFile(f("${s.dir}/out.jsonl"), "r").use { r ->
                val n = minOf(300L, r.length()); r.seek(r.length() - n)
                val b = ByteArray(n.toInt()); r.readFully(b); !String(b).contains("\"arin_end\"")
            }
        }.getOrDefault(true)
    }

    private suspend fun send(s: Session, line: String) = withContext(Dispatchers.IO) {
        val msg = f("${s.dir}/msg.json")
        msg.writeText(line)
        // Straight into the pipe (the wrapper holds it open, so this doesn't block); the shell is the fallback.
        val ok = runCatching { java.io.FileOutputStream(f("${s.dir}/in")).use { it.write(line.toByteArray(Charsets.UTF_8)) } }.isSuccess
        if (!ok) com.arin.terminal.Shell.run(ctx(), "cat ${s.dir}/msg.json > ${s.dir}/in; echo ok", 30_000)
    }

    private suspend fun start(s: Session, system: String, tools: String) {
        val context = ctx()
        // Too many running at once uses a lot of memory: the least recently used one stops (it resumes later).
        // Busy ones (working in another tab) are never stopped: tabs run side by side.
        sessions.values.filter { it !== s && !it.lock.isLocked && alive(it) }.sortedBy { it.lastUse }.dropLast(MAX_LIVE - 1).forEach { stop(it, keepSession = true) }
        f(s.dir).mkdirs()
        f("$BASE/home/.claude").mkdirs()
        f("$BASE/home/.claude.json").takeIf { !it.exists() }?.writeText("{\"hasCompletedOnboarding\":true}")
        // Arin's own instructions REPLACE Claude Code's long built-in ones: the model you picked acts as Arin and answers
        // what you asked (not Claude Code's environment notes), and every request is smaller, so it uses less.
        val who = if (s.custom != null) "the model \"${s.model}\" chosen by the person (you are NOT Claude; ignore any line " +
            "that calls you a Claude agent — that is only the tool harness's label)" else "Claude (${s.model}), chosen by the person"
        f("${s.dir}/system.txt").writeText("IDENTITY: you are $who, running inside the Arin app.\n\n" + system.take(120_000) + "\n\nTOOLS: besides Arin's own tools, you can call the tools " +
            "provided here (Bash, Read, Edit, Write, Glob, Grep, web fetch/search and Arin's tools) when the task needs them. " +
            (if (s.kind.startsWith("helper")) "Work in /root/.arin/helper-work. " else "Work in /root/files. ") + "Run git only inside a repository (check with git rev-parse first); if a git, search or other command fails, don't run " +
            "it again the same way — find another way. Answer the person's actual message directly; for a greeting, just greet back.")
        val (mcp, servers, gh) = runCatching { extras?.invoke() }.getOrNull() ?: Triple("", emptyList(), "")
        val mcpFlag = if (mcp.isNotBlank()) { f("$BASE/mcp.json").writeText(mcp); "--mcp-config $BASE/mcp.json " } else ""
        val allowed = (tools.split(',') + servers.map { "mcp__$it" }).filter { it.isNotBlank() }.joinToString(" ")
        val ghEnv = if (gh.isNotBlank()) "export GH_TOKEN='${gh.replace("'", "")}' GITHUB_TOKEN='${gh.replace("'", "")}'; " else ""
        val resume = s.sessionId?.let { "--resume '$it' " } ?: ""
        val token = s.tokenFile
        // ARIN-PROTECTED: helper-sees-only-the-boss — a helper session runs in its own folder, away from the person's files.
        val work = if (s.kind.startsWith("helper")) "/root/.arin/helper-work" else "/root/files"
        // ARIN-PROTECTED: git-in-repo — the work folder is a git repository, so git commands there never fail with exit 128.
        val cmd = "mkdir -p ${s.dir} $work; cd $work; [ -d .git ] || git init -q . >/dev/null 2>&1; rm -f ${s.dir}/in ${s.dir}/out.jsonl ${s.dir}/pid; mkfifo ${s.dir}/in; " +
            "setsid bash -c \"exec 3<>${s.dir}/in; echo \\$\\$ > ${s.dir}/pid; " +
            "unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " + brainEnv(s, token) +
            "export HOME=$BASE/home DISABLE_AUTOUPDATER=1 CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 " + // ARIN-PROTECTED: tab-isolation
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 DISABLE_TELEMETRY=1 DISABLE_ERROR_REPORTING=1 " +
            "BASH_MAX_OUTPUT_LENGTH=8000 MAX_MCP_OUTPUT_TOKENS=6000 CLAUDE_CODE_MAX_RETRIES=2 API_TIMEOUT_MS=180000; $ghEnv" +
            "claude -p --input-format stream-json --output-format stream-json --verbose --include-partial-messages " +
            "--model '${s.model.replace("'", "")}' $resume--tools '$tools' --allowedTools $allowed $mcpFlag" +
            "--add-dir ${s.dir} --system-prompt \\\"\\$(cat ${s.dir}/system.txt)\\\" " +
            "< ${s.dir}/in >> ${s.dir}/out.jsonl 2>&1; echo '{\\\"type\\\":\\\"arin_end\\\"}' >> ${s.dir}/out.jsonl\" >/dev/null 2>&1 & " +
            "for i in 1 2 3 4 5 6 7 8 9 10; do [ -s ${s.dir}/pid ] && break; sleep 0.3; done; echo started"
        val r = com.arin.terminal.Shell.run(context, cmd, 60_000)
        if (!r.stdout.contains("started")) throw LlmException("Couldn't start Claude Code: " + r.stderr.take(300))
    }

    /** Who answers: the Claude account's sign-in, or the Custom API (directly, or through Arin's bridge). */
    private fun brainEnv(s: Session, token: String): String {
        val c = s.custom ?: return "export CLAUDE_CODE_OAUTH_TOKEN=\\$(cat $token 2>/dev/null); "
        val m = s.model.replace("'", "")
        val base = if (c.anthropic) c.base.trimEnd('/').removeSuffix("/v1") else "http://127.0.0.1:${bridgePort[c.base + c.key.hashCode()] ?: ClaudeCode.BRIDGE_PORT_PUBLIC}"
        val key = if (c.anthropic) c.key.replace("'", "") else "arin-bridge"
        return "unset CLAUDE_CODE_OAUTH_TOKEN; export ANTHROPIC_BASE_URL='$base' ANTHROPIC_AUTH_TOKEN='$key' ANTHROPIC_MODEL='$m' " +
            "ANTHROPIC_SMALL_FAST_MODEL='$m' ANTHROPIC_DEFAULT_OPUS_MODEL='$m' ANTHROPIC_DEFAULT_SONNET_MODEL='$m' " +
            "ANTHROPIC_DEFAULT_HAIKU_MODEL='$m' CLAUDE_CODE_SUBAGENT_MODEL='$m' ENABLE_TOOL_SEARCH=false; "
    }

    /** Ends a session's process. With [keepSession] its conversation can be resumed later (same session id). */
    private suspend fun stop(s: Session, keepSession: Boolean = false) {
        val pid = runCatching { f("${s.dir}/pid").readText().trim() }.getOrNull()
        runCatching {
            com.arin.terminal.Shell.run(ctx(), "pkill -f '[a]dd-dir ${s.dir}' 2>/dev/null; " + (if (pid != null) "kill $pid 2>/dev/null; " else "") +
                (if (keepSession) "" else "rm -rf ${s.dir}; ") + "echo ok", 20_000)
        }
        if (!keepSession) sessions.remove(s.key)
    }

    /** Sessions not used for 30 minutes stop (they resume if the conversation continues). */
    private suspend fun trim() {
        val now = System.currentTimeMillis()
        // Idle for 10 minutes: its process stops to free memory (the conversation resumes when you continue).
        sessions.values.filter { now - it.lastUse > 10 * 60_000L && !it.lock.isLocked && alive(it) }.forEach { stop(it, keepSession = true) }
        sessions.values.filter { now - it.lastUse > 24 * 3_600_000L }.forEach { stop(it) }
    }

    /** Ends everything (Free up space, sign-out). */
    suspend fun stopAll() { sessions.values.toList().forEach { stop(it) } }
}
