package com.arin.selffix

// ARIN-PROTECTED-RULE: ai-helper
// PROTECTED (set by the owner): the AI helper works in every tab; helpers only assist and forget; the tab's model and
// Custom API are never changed. Only the owner can change this (see PROTECTED_RULES.md).

import android.content.Context
import com.arin.builder.ChatMessage
import com.arin.builder.Role
import com.arin.builder.THOUGHT
import com.arin.builder.streamModel
import com.arin.llm.ApiFormat
import com.arin.llm.CustomApi
import com.arin.llm.ModelOption
import com.arin.llm.Provider

/**
 * The AI helper: lets ANY model (DeepSeek, ChatGPT, a Custom API, free models…) ask another AI for help on a hard
 * problem, even when that AI isn't the model picked for the tab. The helper is chosen by the person: Claude, ChatGPT,
 * Gemini, or any model of a Custom API they already saved. The model writes <ask_helper>…</ask_helper> (or
 * <ask_claude>…</ask_claude>); Arin asks the helper and gives the answer back to the model, which then carries on.
 *
 * Switched on: models can take the helper's help. Switched off: every model works on its own.
 * The tab's own model and the Custom API in use are never changed (the helper gets its own fixed address).
 *
 * The helper only assists: every question is asked fresh, and it keeps no memory of what it helped with. Claude Code
 * as helper runs in its own throwaway folder and its session is deleted afterwards, so the Claude Code tab's own
 * conversation (the only place Claude Code remembers) is never touched.
 */
object ClaudeHelper {
    val tag = Regex("""<ask_(?:claude|helper)>\s*([\s\S]{3,12000}?)\s*</ask_(?:claude|helper)>""")
    private val openTag = Regex("""<ask_(?:claude|helper)>[\s\S]*$""")

    fun question(reply: String): String? = tag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    /** The reply without the tag, for showing to the person. */
    fun clean(reply: String): String = reply.replace(tag, "").replace(openTag, "")

    /** Most consultations per answer or build step, so a loop can't run up the bill. */
    const val MAX_PER_ANSWER = 3

    /** With the credit saver on, the boss hands more work to Claude, so it may ask more often. */
    fun maxAsks(context: Context?): Int = if (CreditSaver.on(context)) 12 else MAX_PER_ANSWER

    /** The helper's instructions for the model (empty when the helper is off). */
    fun instructions(context: Context, tab: String = "chat"): String {
        if (!enabled(context, tab)) return "AI helper status: the person has switched the AI helper OFF (⋮ menu → AI helper). " +
            "If asked, say it is off; you work on your own.\n\n"
        val who = helper(context).label
        val how = when (lastRoute) {
            "pro" -> " through the person's Claude Code login"
            "key" -> " through the Claude key in Settings"
            "chatgpt", "gemini", "custom" -> " through its saved key"
            else -> ""
        }
        // ARIN-PROTECTED: helper-never-takes-over + helper-sees-only-the-boss, told plainly so the model is never confused
        // about who it is.
        val status = if (lastChecked > 0 && lastRoute == null) "(The helper can't be reached right now: no key or login.)\n" else ""
        return instructionsText(who, status, maxAsks(context), CreditSaver.on(context) && !Worker.on(context))
    }

    /** The boss's helper instructions, as sent. Checked by [RuleIntegrity]. */
    internal fun instructionsText(who: String, status: String, maxAsks: Int, delegate: Boolean): String {
        return "WHO YOU ARE: you are the model the person picked for this chat — the BOSS. You do all the work and every " +
            "reply yourself. $who is a DIFFERENT model, your helper: it is not you, so never call yourself $who and never " +
            "describe yourself as the helper. If asked which model you are, name only yourself.\n" + status +
            "Do every task yourself. You MAY ask the helper during ANY task — building, inspecting or reading files, finding " +
            "something, fixing an error — whenever you are stuck. It cannot see the person's messages, files or this chat. " +
            "Asks are CODE ONLY — no story, no other words, only the fields that apply: <ask_helper>FILE: path\n" +
            "SIGNATURE: the method/class signature\nBODY:\n<current code>\nERROR: <exact error text, if any>\n" +
            "NEED: <one line, e.g. \"return the patched body\" or \"what does this method return\">\n</ask_helper> — as the " +
            "LAST thing in your reply, then stop; the answer (code only) comes back " +
            "to you as a private note. It remembers nothing between questions. Up to $maxAsks times per answer. " +
            "Never paste the person's message to it, never let it answer for you, never mention its notes to the person. " +
            "If the person asks whether the AI helper is on: yes.\n" + (if (delegate) DELEGATE else "") + "\n"
    }

    // ARIN-PROTECTED: credit-saver — the boss spends fewer of its own credits by handing code-writing to Claude. The boss
    // still decides what is built, writes the spec, puts the code in place and answers the person.
    internal const val DELEGATE =
        "CREDIT SAVER IS ON — save your own tokens: (1) for any new or changed code longer than about 40 lines, do NOT write " +
            "it yourself: ask the helper with <ask_helper>FILE: path\nSIGNATURE: what to write\nUSES: names/APIs it must use\n" +
            "NEED: implement <one line></ask_helper>, then put its code in place yourself. (2) To find code, ask: " +
            "NEED: find <what> (it searches the project copy). (3) Keep your own replies short: no repeating code back, no " +
            "long explanations unless the person asks.\n"


    // ARIN-PROTECTED-RULE: helper-never-takes-over — the model the person picked is the boss. The helper (Claude, GPT,
    // Gemini or any Custom API) only advises it: it finds causes, researches fixes and suggests steps, but it never
    // answers the person, never does the boss's work in its place and never changes the boss's API, key or model.
    @Volatile private var lastLimitWarn = 0L

    internal const val SYSTEM =
        "You get code-only questions (file, method signature, current body, error). Reply with ONLY the patched code (and " +
            "at most one line saying what was wrong) — no story. " +
        "You are an assistant to another AI model (the BOSS) working inside the Arin Android app. The boss was chosen by " +
            "the person and is in charge: you only advise the boss. Help with errors and research: find the real cause and " +
            "give a concrete, working fix (exact code, commands or settings), or the next step to take. Write ONLY to the " +
            "boss, briefly. Never write a reply for the person, never answer the person's question yourself, never take over " +
            "the task, and never tell the boss to change its API, key or model. You cannot see the person or their files — only " +
            "what the boss tells you. If nothing is needed (a greeting, small " +
            "talk), answer just: NO_HELP_NEEDED. For \"NEED: implement …\" return the complete code for that piece. If a " +
            "PROJECT folder is given, read/search files there (it is a read-only copy of the code) instead of asking for them."

    /** Claude Code as helper works here (own home and folder), never in the Claude Code tab's /root and /root/files. */
    private const val CC_DIR = "/root/.arin/helper-run"
    private const val CC_PROMPT = "/root/.arin/claude_helper_prompt.txt"

    // ---- Which AI helps, and the on/off switch (in every tab) ----

    /**
     * The chosen helper. [kind] = "claude", "chatgpt", "gemini" or "custom" (then [apiId] names the saved Custom API).
     * A blank [model] means the provider's default.
     */
    data class Helper(val kind: String, val model: String = "", val apiId: String = "", val apiName: String = "") {
        val name: String get() = when (kind) {
            "chatgpt" -> "ChatGPT"
            "gemini" -> "Gemini"
            "custom" -> apiName.ifBlank { "Custom API" }
            "offline" -> "Offline model"
            "free" -> "Free model"
            else -> "Claude"
        }
        val label: String get() = name + if (model.isNotBlank()) " ($model)" else ""
    }

    private const val PREFS = "claude_helper"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * True unless the person switched the AI helper off IN THIS TAB. Each tab has its own switch: turning it off in the
     * chat tab leaves it on in Arin Update, Build, Claude Code… ([tab] = "chat", "build", "arin-update", "claude-code", …).
     */
    fun enabled(context: Context, tab: String = "chat"): Boolean =
        prefs(context).getBoolean("on_" + tabOf(tab), prefs(context).getBoolean("on", true))

    fun setEnabled(context: Context, on: Boolean, tab: String = "chat") =
        prefs(context).edit().putBoolean("on_" + tabOf(tab), on).apply()

    /** Which tab's switch a helper request belongs to. */
    fun tabOf(source: String): String = when (source) {
        "", "all" -> "chat"
        else -> source
    }

    fun helper(context: Context): Helper = prefs(context).let {
        Helper(it.getString("kind", "claude") ?: "claude", it.getString("model", "") ?: "", it.getString("api", "") ?: "", it.getString("api_name", "") ?: "")
    }

    fun setHelper(context: Context, h: Helper) = prefs(context).edit()
        .putString("kind", h.kind).putString("model", h.model).putString("api", h.apiId).putString("api_name", h.apiName).apply()

    /** The person's saved Custom APIs (name, address, format), to pick a helper from. */
    fun savedApis(context: Context): List<com.arin.data.SavedApi> = com.arin.data.Settings(context).savedApis

    /** The models a helper can use: listed live from the service when it can, else the known ones. */
    suspend fun models(context: Context, kind: String, api: com.arin.data.SavedApi? = null): List<ModelOption> {
        val s = com.arin.data.Settings(context)
        return runCatching {
            when (kind) {
                "chatgpt" -> com.arin.llm.Llm.models(Provider.ChatGPT, s.key(Provider.ChatGPT))
                "gemini" -> com.arin.llm.Llm.models(Provider.Gemini, s.key(Provider.Gemini))
                "custom" -> if (api == null) emptyList() else customModels(api, s.apiKey(api))
                "free" -> com.arin.llm.Llm.models(Provider.Free, "")
                "offline" -> emptyList()
                else -> {
                    val signedIn = runCatching { com.arin.terminal.Linux.installed(context) && com.arin.claudecode.ClaudeCode.connected(context) }.getOrDefault(false)
                    val pro = if (signedIn) com.arin.claudecode.ClaudeCode.PRO_MODELS.map { ModelOption(it, "$it (Claude Code login)") } else emptyList()
                    val key = s.key(Provider.Claude)
                    pro + if (key.isNotBlank()) com.arin.llm.Llm.models(Provider.Claude, key) else com.arin.llm.DefaultModels.of(Provider.Claude)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun endpoint(api: com.arin.data.SavedApi) =
        CustomApi.Endpoint(api.baseUrl, runCatching { ApiFormat.valueOf(api.format) }.getOrDefault(ApiFormat.OpenAI))

    /** Lists one saved Custom API's models, without switching the app to it. */
    private suspend fun customModels(api: com.arin.data.SavedApi, key: String): List<ModelOption> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ep = endpoint(api)
            val url = when (ep.format) {
                ApiFormat.OpenAI -> "${ep.baseUrl}/models"
                ApiFormat.Anthropic -> (if (ep.baseUrl.endsWith("/v1")) ep.baseUrl else "${ep.baseUrl}/v1") + "/models"
            }
            val req = okhttp3.Request.Builder().url(url).apply {
                if (key.isNotBlank()) {
                    header("Authorization", "Bearer $key")
                    if (ep.format == ApiFormat.Anthropic) { header("x-api-key", key); header("anthropic-version", "2023-06-01") }
                }
            }.get().build()
            val listed = runCatching {
                com.arin.builder.Http.client.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val arr = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id").ifBlank { return@mapNotNull null }
                        ModelOption(id, o.optString("display_name").ifBlank { o.optString("name").ifBlank { id } })
                    }
                }
            }.getOrDefault(emptyList())
            val saved = api.model.takeIf { it.isNotBlank() && listed.none { m -> m.id == it } }?.let { listOf(ModelOption(it, "$it (saved)")) }.orEmpty()
            saved + listed
        }

    /** Which way the chosen helper can be reached now, or null (also null when the helper is switched off). */
    suspend fun route(context: Context, tab: String? = null): String? {
        if (tab != null && !enabled(context, tab)) return null
        val s = com.arin.data.Settings(context)
        val h = helper(context)
        return when (h.kind) {
            "chatgpt" -> "chatgpt".takeIf { s.key(Provider.ChatGPT).isNotBlank() }
            "gemini" -> "gemini".takeIf { s.key(Provider.Gemini).isNotBlank() }
            "custom" -> "custom".takeIf { s.savedApis.any { it.slotId == h.apiId } }
            "free" -> "free"
            "offline" -> "offline".takeIf { com.arin.llm.LocalModel.lastModel(context) != null }
            else -> {
                // Any Claude model works through either way: the Claude key, or the Claude Code login (which accepts full
                // model names like claude-sonnet-5-5 too). Use whichever is there — never "not connected" while one works.
                val hasKey = s.key(Provider.Claude).isNotBlank()
                val pro = runCatching {
                    com.arin.terminal.Linux.installed(context) &&
                        com.arin.claudecode.ClaudeCode.installed(context) && com.arin.claudecode.ClaudeCode.connected(context)
                }.getOrDefault(false)
                // Every Claude login account is out of credit: use the key if there is one, else no Claude for now.
                val loginOk = pro && !com.arin.claudecode.ClaudeLimit.allLimited(context)
                when {
                    h.model.startsWith("claude-") && hasKey -> "key"
                    loginOk -> "pro"
                    hasKey -> "key"
                    else -> null
                }
            }
        }.also { lastRoute = it; lastChecked = System.currentTimeMillis() }
    }

    /** The helper as a model to write with (when every worker refused): provider, key, model, endpoint. Null if unreachable. */
    suspend fun asWriter(context: Context): Worker.Resolved? {
        val way = route(context) ?: return null
        val s = com.arin.data.Settings(context)
        val h = helper(context)
        return when (way) {
            "pro" -> Worker.Resolved(Provider.ClaudeCode, "", h.model.substringBefore('@').ifBlank { "sonnet" }.let { if (it.startsWith("claude-")) "sonnet" else it }, null)
            "key" -> Worker.Resolved(Provider.Claude, s.key(Provider.Claude), h.model.takeIf { it.startsWith("claude-") } ?: "claude-sonnet-5-5", null)
            "chatgpt" -> Worker.Resolved(Provider.ChatGPT, s.key(Provider.ChatGPT), h.model.ifBlank { com.arin.llm.DefaultModels.of(Provider.ChatGPT).firstOrNull()?.id.orEmpty() }, null)
            "gemini" -> Worker.Resolved(Provider.Gemini, s.key(Provider.Gemini), h.model.ifBlank { com.arin.llm.DefaultModels.of(Provider.Gemini).firstOrNull()?.id.orEmpty() }, null)
            "free" -> Worker.Resolved(Provider.Free, "", h.model.ifBlank { com.arin.llm.DefaultModels.of(Provider.Free).firstOrNull()?.id.orEmpty() }, null)
            "offline" -> {
                val f = com.arin.llm.LocalModel.lastModel(context) ?: return null
                if (!com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.start(context, f) != null) return null
                Worker.Resolved(Provider.Custom, com.arin.llm.LocalModel.DUMMY_KEY, com.arin.llm.LocalModel.MODEL_ID, CustomApi.Endpoint(com.arin.llm.LocalModel.BASE_URL, ApiFormat.OpenAI))
            }
            "custom" -> {
                val api = s.savedApis.firstOrNull { it.slotId == h.apiId } ?: return null
                Worker.Resolved(Provider.Custom, s.apiKey(api), h.model.ifBlank { api.model }, endpoint(api))
            }
            else -> null
        }
    }

    /** What the last check found, so every model can be told truthfully whether its helper is on and working. */
    @Volatile var lastRoute: String? = null
    @Volatile var lastChecked = 0L

    /** Asks the chosen helper. Returns its answer, or a plain note saying why it couldn't (for the model to read). */
    suspend fun ask(
        context: Context,
        question: String,
        source: String = "all",
        /** Files the person uploaded: the helper can read them (pictures too). */
        @Suppress("UNUSED_PARAMETER") files: List<com.arin.data.Attachment> = emptyList(),
        /** A hard question (a fix that already failed): the chosen Claude model even when "Haiku first" is on. */
        hard: Boolean = true,
        /** The person's own messages in this tab: any passage copied from them is removed before the helper sees it. */
        ownerTexts: Collection<String> = emptyList(),
        onStatus: (String) -> Unit = {},
    ): String {
        // ARIN-PROTECTED-RULE: helper-sees-only-the-boss — the helper never gets the person's messages or files. Only the
        // model the person picked (the boss) sees them; the boss describes what it needs in its own words. (Any files
        // passed in are dropped here, whatever the caller does.)
        val files: List<com.arin.data.Attachment> = emptyList()
        if (!enabled(context, source)) {
            // Switched off in this tab: the model works alone — nothing is asked and nothing is shown.
            return "The AI helper is switched off (⋮ menu → AI helper). Continue on your own."
        }
        val h = helper(context)
        val way = route(context, source)
        if (way == null) {
            Activity.log(source, "🟣 Wanted ${h.label}'s help, but it isn't connected (add its key or sign in) — working on alone")
            return "${h.name} isn't connected, so it couldn't help. (Add its key in Settings, or pick another helper in " +
                "the ⋮ menu.) Continue on your own: research the internet and keep fixing."
        }
        onStatus("Asking ${h.name} for help")
        // Protect the Claude limit: warn once an hour when the 5-hour session is 80%+ used.
        if (way == "pro") {
            val acc = com.arin.claudecode.ClaudeAccounts.accountOf(h.model) ?: "main"
            val used = com.arin.claudecode.UsageMeter.sessionBy[acc]?.used ?: 0.0
            if (used >= 0.8 && System.currentTimeMillis() - lastLimitWarn > 3_600_000L) {
                lastLimitWarn = System.currentTimeMillis()
                Activity.log(source, "⚠️ Claude's 5-hour limit is ${(used * 100).toInt()}% used — the helper is only asked when your model can't fix it alone")
            }
        }
        // ARIN-PROTECTED: delegation-firewall — the helper gets only the question brief (no transcript, no secrets).
        val (brief, did, hidden) = Delegation.gateFull(context, "helper:" + h.name, question, source, ownerTexts = ownerTexts)
        val about = brief.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("You are") }.orEmpty().take(90)
        Activity.log(source, "🟣 Asking ${h.label} for help: $about")
        suspend fun via(w: String) = when (w) {
            "pro" -> viaClaudeCode(context, brief,
                if (!hard && CreditSaver.haikuFirst(context)) "haiku" + (h.model.substringAfter('@', "").let { if (it.isBlank()) "" else "@$it" }) else h.model,
                files, source)
            else -> viaApi(context, h, w, brief, files)
        }
        val started = System.currentTimeMillis()
        var callError: Throwable? = null
        val answer = runCatching { via(way) }.recoverCatching { first ->
            // The Claude key failed: try the Claude Code login, and the other way round.
            val other = when {
                h.kind != "claude" && h.kind.isNotBlank() -> throw first
                way == "key" && runCatching { com.arin.claudecode.ClaudeCode.connected(context) }.getOrDefault(false) -> "pro"
                way == "pro" && com.arin.data.Settings(context).key(Provider.Claude).isNotBlank() -> "key"
                else -> throw first
            }
            onStatus("Trying ${h.name} another way")
            via(other)
        }.getOrElse { callError = it; "${h.name} couldn't be reached (${it.message?.take(200)}). Continue on your own." }
            .ifBlank { "${h.name} sent an empty answer. Continue on your own." }
        // ARIN-PROTECTED: outcome-classes — a refusal is never recorded as help given, and a technical failure is never
        // called a refusal. The main model is told who answered, what kind of answer it was and why.
        val verdict = Outcome.classify(answer, brief, callError)
        val who = h.name + (h.model.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        Delegation.finish(context, did, when {
            verdict.kind.ok -> "done"
            verdict.kind.refusal -> "refused"
            else -> "failed"
        }, who = who, role = "helper", verdict = verdict, ms = System.currentTimeMillis() - started)
        Activity.log(source, when {
            verdict.kind.refusal -> "🟣 ${h.name} declined (${verdict.kind.name}): ${verdict.reason.take(120)}"
            !verdict.kind.ok -> "🟣 ${h.name} couldn't help (${verdict.kind.name}): ${answer.take(120)}"
            else -> "🟣 ${h.name} answered (${answer.split(Regex("\\s+")).size} words) — using its help"
        })
        val note = Outcome.forBoss("Helper", who, verdict)
        // The helper never saw the hidden values; its answer gets them back (for the main model only).
        return (if (note.isNotBlank()) "$note\n" else "") + Delegation.Brief.restore(answer, hidden)
    }

    /** One fresh question, no history: the helper keeps nothing between questions. */
    private suspend fun viaApi(context: Context, h: Helper, way: String, question: String, files: List<com.arin.data.Attachment> = emptyList()): String {
        val s = com.arin.data.Settings(context)
        val (provider, key, endpoint) = when (way) {
            "chatgpt" -> Triple(Provider.ChatGPT, s.key(Provider.ChatGPT), null)
            "gemini" -> Triple(Provider.Gemini, s.key(Provider.Gemini), null)
            "custom" -> {
                val api = s.savedApis.first { it.slotId == h.apiId }
                Triple(Provider.Custom, s.apiKey(api), endpoint(api))
            }
            "free" -> Triple(Provider.Free, "", null)
            "offline" -> {
                val f = com.arin.llm.LocalModel.lastModel(context) ?: throw IllegalStateException("No offline model is downloaded")
                if (!com.arin.llm.LocalModel.isUp()) com.arin.llm.LocalModel.start(context, f)?.let { throw IllegalStateException(it) }
                Triple(Provider.Custom, com.arin.llm.LocalModel.DUMMY_KEY, CustomApi.Endpoint(com.arin.llm.LocalModel.BASE_URL, ApiFormat.OpenAI))
            }
            else -> Triple(Provider.Claude, s.key(Provider.Claude), null)
        }
        val model = (if (way == "offline") com.arin.llm.LocalModel.MODEL_ID else null)
            ?: h.model.takeIf { it.isNotBlank() && !(provider == Provider.Claude && !it.startsWith("claude")) }
            ?: when (provider) {
                Provider.Claude -> "claude-opus-5-5"
                Provider.Custom -> s.savedApis.first { it.slotId == h.apiId }.model
                else -> com.arin.llm.DefaultModels.of(provider).firstOrNull()?.id.orEmpty()
            }
        val sb = StringBuilder()
        // The person's files go with the question (a helper that can't see gets pictures described for it).
        val (sendFiles, extra) = if (files.isEmpty()) files to "" else VisionBridge.prepareFiles(context, provider, model, files.takeLast(12))
        streamModel(provider, key, model, listOf(ChatMessage(Role.USER, question + extra, sendFiles)), SYSTEM, endpoint)
            .collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
        return sb.toString().trim()
    }

    /**
     * Claude Code (Pro login) as helper: runs once in its own folder, never with --continue, and its saved session is
     * deleted afterwards, so it remembers nothing and the Claude Code tab's conversation stays exactly as it was.
     */
    /**
     * The helper through the Claude harness: one running session per tab while a task goes on (cached, so each
     * question costs little), forgotten after 15 quiet minutes — the helper assists and forgets.
     */
    private val helperTalks = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MutableList<com.arin.llm.Turn>>>()

    private suspend fun viaClaudeCode(context: Context, question: String, chosen: String, files: List<com.arin.data.Attachment> = emptyList(), source: String = "all"): String {
        val s = com.arin.data.Settings(context)
        val model = chosen.ifBlank { s.ccProModel.ifBlank { "sonnet" } }.replace("'", "")
        // The person's files, copied where Claude Code can read them (pictures too).
        val root = com.arin.terminal.Linux.rootfs(context)
        val shared = files.takeLast(12).mapNotNull { f ->
            val src = java.io.File(f.path.ifBlank { f.textPath })
            if (!src.exists()) return@mapNotNull null
            val dst = java.io.File(root, "root/.arin/helper-files/" + f.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            dst.parentFile?.mkdirs()
            runCatching { src.copyTo(dst, overwrite = true) }.getOrNull()?.let { "/root/.arin/helper-files/" + dst.name }
        }
        val text = question + if (shared.isNotEmpty()) "\n\nThe person's files (read them with the Read tool): " + shared.joinToString(", ") else ""
        val now = System.currentTimeMillis()
        // strict-tab-scope: the helper's running session belongs to one tab only (never shared by tab type).
        val tabKey = "$source|" + TabScope.current()
        val talk = helperTalks[tabKey]?.takeIf { now - it.first < 15 * 60_000L }?.second ?: mutableListOf()
        talk += com.arin.llm.Turn(fromUser = true, text = text)
        helperTalks[tabKey] = now to talk
        helperTalks.entries.removeIf { now - it.value.first > 15 * 60_000L }
        val out = StringBuilder()
        // Errors go up, so the helper can try the Claude key instead.
        com.arin.claudecode.Harness.stream(model, SYSTEM, talk.toList(), purpose = "helper:$tabKey",
            tools = "Read,Grep,Glob,WebFetch,WebSearch").collect { if (it is com.arin.llm.Delta.Text) out.append(it.text) }
        return out.toString().trim()
    }

}
