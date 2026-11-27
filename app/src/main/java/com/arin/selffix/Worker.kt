package com.arin.selffix

import android.content.Context
import com.arin.llm.Delta
import com.arin.llm.Turn

/**
 * ARIN-PROTECTED: worker-mode in chat — the model the person picked is the boss: it talks to the person and decides.
 * Heavy work (code, long text, long analysis) goes to the worker as a task the boss writes; the worker may ask the
 * helper; the boss inspects the result and answers. The worker sees only the boss's task, never the person's chat.
 */
object Worker {
    private val tag = Regex("(?s)<ask_worker>(.*?)</ask_worker>")
    private val openTag = Regex("(?s)<ask_worker>.*$")
    const val RESULT_TAG = "<worker_result/>"

    fun task(reply: String): String? = tag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    fun clean(reply: String): String = reply.replace(tag, "").replace(openTag, "")

    /** A plain name for a model id: "DeepSeek", "GPT-6 Luna", "Claude"… so the steps say who is working. */
    fun nice(model: String): String {
        val m = model.lowercase()
        return when {
            "luna" in m -> "GPT-6 Luna"
            "terra" in m -> "GPT Terra"
            "gpt" in m && "sol" in m -> "GPT Sol"
            "deepseek" in m -> "DeepSeek"
            "claude" in m || "sonnet" in m || "opus" in m || "haiku" in m -> "Claude"
            "gemini" in m -> "Gemini"
            "glm" in m -> "GLM"
            "qwen" in m -> "Qwen"
            "gemma" in m -> "Gemma"
            m == "local" || m == "offline" -> "Offline model"
            m.startsWith("gpt") -> "GPT"
            else -> model.substringAfterLast('/').substringBefore('@')
        }
    }

    fun on(c: Context?): Boolean = c != null && CreditSaver.on(c) && CreditSaver.workerMode(c) && CreditSaver.workers(c).isNotEmpty()

    private fun names(c: Context): String {
        val apis = com.arin.data.Settings(c).savedApis
        return CreditSaver.workers(c).joinToString(", then ") { w -> if (w.apiId == "offline") "the offline model" else w.model.ifBlank { apis.firstOrNull { it.slotId == w.apiId }?.model ?: BUILT_IN.firstOrNull { it.first == w.apiId }?.second.orEmpty() } }
    }

    /** What the boss is told in chat when worker mode is on. */
    fun instructions(c: Context): String {
        if (!on(c)) return ""
        return instructionsText(names(c))
    }

    /** The boss's worker-mode instructions, as sent ([names] = the workers' names). Checked by [RuleIntegrity]. */
    internal fun instructionsText(names: String): String {
        return "WORKER MODE IS ON. The team: you (the boss, the only one who talks to the person), the worker (${names}, a " +
            "different model that does the heavy work) and the AI helper (helps the worker). OWNER'S STRICT RULE: you only direct and " +
            "inspect; you NEVER write code (not even a few lines), long text, research summaries or long analysis yourself. Keep your " +
            "own replies short: think as deeply as the problem needs, but write little. For any code, " +
            "long text, long analysis or multi-step work, do NOT do it yourself — write <ask_worker>TASK: everything the worker " +
            "needs, in YOUR OWN WORDS (it cannot see the person or this chat; never paste the person's messages, files or " +
            "personal details — Arin removes copied passages)</ask_worker> as the LAST thing in your reply and stop. Its result comes " +
            "back to you: inspect it; if it's wrong, send directions in another <ask_worker>. To show the worker's output to the " +
            "person, write $RESULT_TAG instead of copying it. Short questions and small talk you answer yourself, briefly.\n\n"
    }

    internal val SYSTEM = "You are the WORKER. A supervisor model gives you tasks and checks your work. Do the task completely " +
        "and well; reply with only the result (code in full where asked), no chit-chat. When stuck, ask the helper code-only: " +
        "<ask_helper>FILE: …\nBODY:\n…\nERROR: …\nNEED: <one line></ask_helper> as the last thing; its answer comes back to you."

    private data class Target(val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint?, val w: CreditSaver.Worker,
                              val provider: com.arin.llm.Provider = com.arin.llm.Provider.Custom)

    /** The built-in services a worker (or helper) can be, besides saved Custom APIs and the offline model. */
    val BUILT_IN = listOf("claude" to "Claude (key or Claude Code login)", "chatgpt" to "ChatGPT", "gemini" to "Gemini", "free" to "Free models")

    /** How to reach one worker now (null when it isn't set up: no key, no login, API removed…). */
    data class Resolved(val provider: com.arin.llm.Provider, val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint?)

    suspend fun resolve(c: Context, w: CreditSaver.Worker): Resolved? = targetFor(c, w)?.let { Resolved(it.provider, it.key, it.model, it.endpoint) }

    private suspend fun targetFor(c: Context, w: CreditSaver.Worker): Target? {
        val settings = com.arin.data.Settings(c)
        return when (w.apiId) {
                "offline" -> {
                    val f = com.arin.llm.LocalModel.lastModel(c) ?: return null
                    if (!com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.start(c, f) != null) return null
                    Target(com.arin.llm.LocalModel.DUMMY_KEY, com.arin.llm.LocalModel.MODEL_ID,
                        com.arin.llm.CustomApi.Endpoint(com.arin.llm.LocalModel.BASE_URL, com.arin.llm.ApiFormat.OpenAI), w)
                }
                "claude" -> {
                    val key = settings.key(com.arin.llm.Provider.Claude)
                    val login = runCatching { com.arin.terminal.Linux.installed(c) && com.arin.claudecode.ClaudeCode.connected(c) }.getOrDefault(false) &&
                        !com.arin.claudecode.ClaudeLimit.allLimited(c)
                    val m = w.model.ifBlank { if (key.isNotBlank()) "claude-sonnet-5-5" else "sonnet" }
                    when {
                        // Claude Code login models ("sonnet", "opus", "haiku"), or no key: use the login.
                        login && (key.isBlank() || !m.startsWith("claude-")) -> Target("", m, null, w, com.arin.llm.Provider.ClaudeCode)
                        key.isNotBlank() -> Target(key, if (m.startsWith("claude-")) m else "claude-sonnet-5-5", null, w, com.arin.llm.Provider.Claude)
                        else -> null
                    }
                }
                "chatgpt", "gemini" -> {
                    val p = if (w.apiId == "chatgpt") com.arin.llm.Provider.ChatGPT else com.arin.llm.Provider.Gemini
                    val key = settings.key(p).takeIf { it.isNotBlank() } ?: return null
                    Target(key, w.model.ifBlank { com.arin.llm.DefaultModels.of(p).firstOrNull()?.id.orEmpty() }, null, w, p)
                }
                "free" -> Target("", w.model.ifBlank { com.arin.llm.DefaultModels.of(com.arin.llm.Provider.Free).firstOrNull()?.id.orEmpty() },
                    null, w, com.arin.llm.Provider.Free)
                else -> {
                    val api = settings.savedApis.firstOrNull { it.slotId == w.apiId } ?: return null
                    val fmt = runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI)
                    Target(settings.apiKey(api), w.model.ifBlank { api.model }, com.arin.llm.CustomApi.Endpoint(api.baseUrl, fmt), w)
                }
            }
    }

    private suspend fun targets(c: Context): List<Target> {
        val helper = runCatching { ClaudeHelper.asWriter(c) }.getOrNull()?.let { r ->
            Target(r.key, r.model, r.endpoint, CreditSaver.Worker("helper", r.model), r.provider)
        }
        // The helper is the last one to try, so the boss never has to do the heavy work itself.
        return CreditSaver.workers(c).mapNotNull { w -> targetFor(c, w) }
            .sortedBy { if (CreditSaver.refused(c, it.w, "chat")) 1 else 0 } + listOfNotNull(helper)
    }

    /** Runs [task] on the first worker that accepts it (asking the helper when the worker wants). "" if none could. */
    suspend fun run(c: Context, task: String, boss: String = "your model", system: String = SYSTEM, onStatus: (who: String, text: String) -> Unit = { _, _ -> }): Pair<String, String> =
        runFor(c, task, boss, system, onStatus = onStatus)

    /** One worker's try at a task: who, and what its answer actually was (provenance for the audit and the boss). */
    data class Attempt(val who: String, val provider: String, val model: String, val verdict: Outcome.Verdict,
                       /** Its exact answer (in memory only, for the boss's review; the audit keeps only the redacted reason). */
                       val raw: String = "")

    /**
     * ARIN-PROTECTED: worker-results — what a worker run produced: the answer (if a worker did the work), its outcome
     * class, and every worker's try with who it was and why it didn't work. Failures are never collapsed into "".
     */
    data class WorkResult(val who: String, val text: String, val verdict: Outcome.Verdict, val attempts: List<Attempt>) {
        val ok: Boolean get() = verdict.kind.ok && text.isNotBlank()
        /** For the main model when no worker did it: who answered what (from the real answers only). */
        fun summary(): String = attempts.filterNot { it.verdict.kind.ok }.joinToString("; ") {
            "${it.who}: ${it.verdict.kind.name}" + (if (it.verdict.reason.isNotBlank()) " (\"${it.verdict.reason.take(160)}\")" else "")
        }.ifBlank { "no worker is set up or reachable" }
    }

    /** [run] for a project: the relevant [requirements] of [project] go with the brief. */
    suspend fun runFor(c: Context, task0: String, boss: String = "your model", system: String = SYSTEM, tab: String = "", project: String? = null,
                       requirements: List<String> = emptyList(), ownerTexts: Collection<String> = emptyList(),
                       onStatus: (who: String, text: String) -> Unit = { _, _ -> }): Pair<String, String> =
        runDetailed(c, task0, boss, system, tab, project, requirements, ownerTexts, onStatus).let { it.who to (if (it.ok) it.text else "") }

    /** Like [runFor], with the full outcome (who refused or failed, and why). */
    suspend fun runDetailed(c: Context, task0: String, boss: String = "your model", system: String = SYSTEM, tab: String = "", project: String? = null,
                            requirements: List<String> = emptyList(), ownerTexts: Collection<String> = emptyList(),
                            onStatus: (who: String, text: String) -> Unit = { _, _ -> }): WorkResult {
        // ARIN-PROTECTED: delegation-firewall — the worker gets only the task brief (no transcript, no secrets, none of the
        // person's own words: those go only to the main model).
        val (task, did, hidden) = Delegation.gateFull(c, "worker", task0, tab, project, requirements, ownerTexts)
        val started = System.currentTimeMillis()
        val r = runInner(c, task, boss, system, tab.ifBlank { did }, onStatus)
        Delegation.finish(c, did, when { r.ok -> "done"; r.verdict.kind.refusal -> "refused"; else -> "failed" },
            who = r.who.ifBlank { r.attempts.lastOrNull()?.who.orEmpty() }, role = "worker", verdict = r.verdict,
            ms = System.currentTimeMillis() - started,
            attempts = r.attempts.map { a ->
                org.json.JSONObject().put("who", a.who).put("outcome", a.verdict.kind.name)
                    .put("reason", Delegation.Brief.redact(a.verdict.reason).first.take(200))
            })
        // The worker never saw the hidden values; the code it returns gets them back.
        return r.copy(text = Delegation.Brief.restore(r.text, hidden))
    }

    private suspend fun runInner(c: Context, task: String, boss: String, system: String, scope: String,
                                 onStatus: (who: String, text: String) -> Unit): WorkResult {
        val helper = ClaudeHelper.helper(c).name
        val attempts = mutableListOf<Attempt>()
        val expectsCode = system != SYSTEM || Regex("""(?i)\b(=== FILE|kotlin|code|fix the|implement)\b""").containsMatchIn(task.take(3_000))
        for (t in targets(c)) {
            val me = nice(t.model)
            onStatus(me, "$me is following $boss's instructions")
            val turns = mutableListOf(Turn(true, task))
            var asks = 0
            while (true) {
                val sb = StringBuilder()
                var err: Throwable? = null
                // ARIN-PROTECTED: tab-isolation — each chat/task has its own worker conversation (a session shared by
                // every tab under one constant id could carry one tab's work into another's).
                runCatching {
                    com.arin.llm.Llm.stream(t.provider, t.key, t.model, system, turns.toList(), endpoint = t.endpoint, conv = "worker:$scope")
                        .collect { d -> if (d is Delta.Text) sb.append(d.text) }
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; err = it }
                val text = sb.toString()
                var v = Outcome.classify(text, task, if (text.isBlank()) err else null, expectsCode)
                // The older short-refusal check still counts (a refusal is never taken as finished work).
                if (v.kind.ok && !v.partial && CreditSaver.isRefusal(text))
                    v = Outcome.Verdict(Outcome.Kind.UNCLASSIFIED_REFUSAL, text.trim().lineSequence().first().take(300))
                if (!v.kind.ok) {
                    attempts += Attempt(me, t.provider.name, t.model, v, raw = text.take(4_000))
                    if (v.kind.refusal) CreditSaver.noteRefusal(c, t.w, "chat")
                    onStatus(me, "$me " + (if (v.kind.refusal) "declined (${v.kind.name.lowercase().replace('_', ' ')})" else "couldn't do it (${v.kind.name.lowercase().replace('_', ' ')})") +
                        " — passing $boss's task to the next worker")
                    break
                }
                val q = ClaudeHelper.question(text)
                if (q != null && asks < 3) {
                    asks++
                    onStatus(helper, "$me asked $helper for help — $helper is helping $me")
                    val ans = ClaudeHelper.ask(c, q, source = "chat") { }
                    onStatus(me, "$helper helped; $me is continuing $boss's task")
                    turns += Turn(false, text)
                    turns += Turn(true, "The helper's answer:\n$ans\n\nNow finish the task.")
                    continue
                }
                onStatus(boss, "$me finished; $boss is inspecting $me's work")
                attempts += Attempt(me, t.provider.name, t.model, v)
                return WorkResult(t.model, ClaudeHelper.clean(text).trim(), v, attempts)
            }
        }
        return WorkResult("", "", attempts.lastOrNull()?.verdict ?: Outcome.Verdict(Outcome.Kind.NETWORK_OR_PROVIDER_FAILURE, "no worker is set up or reachable"), attempts)
    }
}
