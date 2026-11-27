package com.arin.selffix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable

/**
 * ARIN-PROTECTED: credit-saver — the person's two modes (only they switch them):
 *  1 = their model + Claude: Claude researches and fixes errors (code-only), their model applies the fix.
 *  2 = their model + an offline model + Claude: as 1, and the offline model (any they installed) filters web pages and
 *      writes titles on the phone, for free.
 * Their model always stays the boss, at full power: only waste is cut (no whole-project resends, no repeated research,
 * no repeated questions, errors cut to their cause, small edits instead of whole files).
 */
object CreditSaver {
    private fun prefs(c: Context) = c.getSharedPreferences("credit_saver", Context.MODE_PRIVATE)

    /** 0 = off, 1 = model + Claude, 2 = model + offline model + Claude. */
    fun mode(c: Context): Int = prefs(c).getInt("mode", 0)
    fun setMode(c: Context, m: Int) = prefs(c).edit().putInt("mode", m).apply()
    fun on(c: Context?): Boolean = c != null && mode(c) > 0

    /** The offline model the person chose for mode 2 (a .gguf file in Arin's Linux), or "" for none. */
    fun offlineModel(c: Context): String = prefs(c).getString("offline", "").orEmpty()
    fun setOfflineModel(c: Context, path: String) = prefs(c).edit().putString("offline", path).apply()

    /** Helper tries Claude Haiku first for an error, then the chosen Claude model (off by default: full power). */
    /** A per-build budget: "research" (web searches, default 15), "consult" (helper consultations, default 8). */
    fun budget(c: Context, name: String, def: Int): Int = prefs(c).getInt("budget_$name", def)
    fun setBudget(c: Context, name: String, n: Int) = prefs(c).edit().putInt("budget_$name", n.coerceIn(1, 100)).apply()

    /** Easy helper questions go to Claude Haiku (uses far less of your Claude limit); hard ones to the model you picked. On by default. */
    fun haikuFirst(c: Context): Boolean = prefs(c).getBoolean("haiku_first", true)
    fun setHaikuFirst(c: Context, on: Boolean) = prefs(c).edit().putBoolean("haiku_first", on).apply()

    /** Daily warning, in thousands of tokens, for the person's own model (0 = off). Only warns, never stops. */
    fun dailyBudgetK(c: Context): Int = prefs(c).getInt("daily_k", 0)
    fun setDailyBudgetK(c: Context, k: Int) = prefs(c).edit().putInt("daily_k", k).apply()

    // ---- Worker mode (experiment): the boss plans and inspects, a worker writes the code with Claude's help ----
    // ARIN-PROTECTED: worker-mode — the person's model stays the boss: it reads the request, gives the worker its
    // directions, inspects the worker's work and approves it before anything is built. The worker (and Claude, helping
    // the worker) only ever see the boss's directions and the code, never the person's chat. Off = the previous method.

    fun workerMode(c: Context?): Boolean = c != null && prefs(c).getBoolean("worker_mode", false)
    fun setWorkerMode(c: Context, on: Boolean) = prefs(c).edit().putBoolean("worker_mode", on).apply()

    /** One worker: a saved API (by its id) with a model id, or the phone's offline model (apiId = "offline"). */
    data class Worker(val apiId: String, val model: String)

    fun workers(c: Context): List<Worker> {
        val raw = prefs(c).getString("workers", null)
        if (raw != null) return raw.split('\n').mapNotNull { l -> l.split('|').takeIf { it.size == 2 && it[0].isNotBlank() }?.let { Worker(it[0], it[1]) } }
        // First time: GPT-6 Luna, then DeepSeek V4.1 Flash, on an Experiential Labs API if one is saved.
        val xl = runCatching { com.arin.data.Settings(c).savedApis.firstOrNull { it.baseUrl.contains("experientiallabs") } }.getOrNull()
        return if (xl == null) emptyList() else listOf(Worker(xl.slotId, "gpt-6-luna"), Worker(xl.slotId, "deepseek-v4.1-flash-1017"))
    }
    fun setWorkers(c: Context, list: List<Worker>) =
        prefs(c).edit().putString("workers", list.joinToString("\n") { it.apiId + "|" + it.model.replace("|", "").trim() }).apply()

    private val refusal = Regex("(?i)(I can['’]t (help|assist|do that|provide|comply)|I cannot (help|assist|provide|comply|create|write)|I['’]m (sorry|unable)[^.]{0,60}(can['’]t|cannot|unable|not able)|I won['’]t (help|be able)|I am not able to (help|assist)|against (my|the) (guidelines|policy|policies)|I must decline|can['’]t help with that)")

    /** A worker's answer that is a refusal instead of work (short, no code, and says it won't help). */
    fun isRefusal(reply: String): Boolean {
        val r = reply.trim()
        if (r.isEmpty()) return true
        if (r.contains("=== FILE:") || r.contains("=== EDIT:") || r.contains("```") || r.contains("<ask_helper>")) return false
        return r.length < 1_500 && refusal.containsMatchIn(r)
    }

    /** Kinds of task each worker refused, so the next one goes straight to a worker that accepts it. */
    fun noteRefusal(c: Context, w: Worker, kind: String) =
        prefs(c).edit().putBoolean("refused|${w.apiId}|${w.model}|$kind", true).apply()
    fun refused(c: Context, w: Worker, kind: String): Boolean = prefs(c).getBoolean("refused|${w.apiId}|${w.model}|$kind", false)

    // ---- does the server give a cache discount? (read from each reply's usage) ----

    private val cacheHit = ConcurrentHashMap<String, Long>()
    private val cacheTotal = ConcurrentHashMap<String, Long>()
    private fun host(url: String) = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(url)

    /** Called with each reply's "usage": counts cached tokens and the free pool. */
    fun recordUsage(url: String, prompt: Long, cached: Long, completion: Long) {
        val h = host(url)
        cacheTotal.merge(h, prompt, Long::plus)
        cacheHit.merge(h, cached, Long::plus)
        runCatching {
            val c = com.arin.ArinApp.instance
            c.getSharedPreferences("credit_saver", Context.MODE_PRIVATE).edit().putBoolean("cache_ok|$h", cacheHit[h] ?: 0 > 0).apply()
            if (h.contains("experientiallabs")) addFreePool(c, prompt + completion)
        }
    }

    /** True when this server has given cached tokens: then old messages are kept unchanged so the cache keeps working. */
    fun cacheWorks(c: Context, url: String): Boolean = prefs(c).getBoolean("cache_ok|${host(url)}", false)
    fun cacheRate(url: String): Int { val h = host(url); val t = cacheTotal[h] ?: 0; return if (t == 0L) -1 else ((cacheHit[h] ?: 0) * 100 / t).toInt() }

    // ---- Experiential Labs free pool: 1,000,000 tokens a day, resets 00:00 UTC ----

    const val FREE_POOL = 1_000_000L
    private fun utcDay() = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()
    fun addFreePool(c: Context, tokens: Long): Long {
        val p = prefs(c); val d = utcDay()
        val used = (if (p.getString("pool_day", "") == d) p.getLong("pool_used", 0) else 0L) + tokens
        p.edit().putString("pool_day", d).putLong("pool_used", used).apply()
        return used
    }
    fun freePoolUsed(c: Context): Long = prefs(c).let { if (it.getString("pool_day", "") == utcDay()) it.getLong("pool_used", 0) else 0 }

    // ---- stay under the free pool's 10 requests a minute ----

    private val sent = ConcurrentHashMap<String, ArrayDeque<Long>>()
    suspend fun throttle(url: String, perMinute: Int = 9) {
        val h = host(url)
        if (!h.contains("experientiallabs")) return
        while (true) {
            val wait = synchronized(sent) {
                val q = sent.getOrPut(h) { ArrayDeque() }
                val now = System.currentTimeMillis()
                while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
                if (q.size < perMinute) { q.addLast(now); 0L } else 60_000 - (now - q.first()) + 200
            }
            if (wait <= 0) return
            kotlinx.coroutines.delay(wait)
        }
    }

    // ---- tokens used (rough: characters / 4), per day, for the meter and the daily warning ----

    fun addUsage(c: Context, chars: Int): Long {
        val day = java.time.LocalDate.now().toString()
        val p = prefs(c)
        val used = (if (p.getString("day", "") == day) p.getLong("used", 0) else 0L) + chars / 4
        p.edit().putString("day", day).putLong("used", used).apply()
        return used
    }
    fun usedToday(c: Context): Long = prefs(c).let { if (it.getString("day", "") == java.time.LocalDate.now().toString()) it.getLong("used", 0) else 0 }

    // ---- errors cut to their cause ----

    private val errLine = Regex("(?i)(^e: |error:|^\\s*\\* what went wrong|unresolved reference|cannot find symbol|exception|caused by|failed|could not|type mismatch|expected|no value passed)")

    /** The lines that matter in a build/compile log (first real errors and a little context), not the noise. */
    fun trimErrors(log: String, maxLines: Int = 40): String {
        val lines = log.lines()
        val keep = sortedSetOf<Int>()
        lines.forEachIndexed { i, l -> if (errLine.containsMatchIn(l)) { (maxOf(0, i - 1)..minOf(lines.lastIndex, i + 2)).forEach { keep += it } } }
        if (keep.isEmpty()) return log.takeLast(3_000)
        return keep.take(maxLines * 2).map { lines[it] }.distinct().take(maxLines).joinToString("\n").take(6_000)
    }

    /** One error's "fingerprint": the first real error line without paths, line numbers or ids. */
    fun signature(errors: String): String = trimErrors(errors, 6).lines().firstOrNull { errLine.containsMatchIn(it) }.orEmpty()
        .replace(Regex("file:///\\S+"), "").replace(Regex("\\(\\d+:\\d+\\)|:\\d+(:\\d+)?"), "").replace(Regex("\\s+"), " ").trim().take(300)

    // ---- remembered fixes: the same error again → the fix that worked, with no AI ----

    private fun memFile(c: Context) = File(c.filesDir, "fix-memory.json")

    fun rememberedFix(c: Context, sig: String): String? = runCatching {
        if (sig.isBlank()) null else JSONObject(memFile(c).readText()).optString(sig).ifBlank { null }
    }.getOrNull()

    fun rememberFix(c: Context, sig: String, fix: String) {
        if (sig.isBlank() || fix.isBlank()) return
        runCatching {
            val o = runCatching { JSONObject(memFile(c).readText()) }.getOrElse { JSONObject() }
            o.put(sig, fix.take(8_000))
            // Keep the newest 300.
            if (o.length() > 300) o.remove(o.keys().next())
            memFile(c).writeText(o.toString())
        }
    }

    fun forgetFix(c: Context, sig: String) = runCatching {
        val o = JSONObject(memFile(c).readText()); o.remove(sig); memFile(c).writeText(o.toString())
    }

    // ---- research once: the same search isn't done twice ----

    private val researched = ConcurrentHashMap<String, String>()
    fun cachedResearch(q: String): String? = researched[q.trim().lowercase()]
    fun cacheResearch(q: String, r: String) { if (r.isNotBlank()) researched[q.trim().lowercase()] = r }

    // ---- the offline model (mode 2): tiny, strict jobs, checked; plain rules when it's slow or wrong ----

    /** Asks the chosen offline model one short thing. Null when it isn't set up, is slow (20 s) or fails. */
    suspend fun offline(c: Context, prompt: String, maxTokens: Int = 60): String? = withContext(Dispatchers.IO) {
        if (mode(c) != 2) return@withContext null
        val path = offlineModel(c).ifBlank { return@withContext null }
        val file = File(com.arin.terminal.Linux.rootfs(c), path.removePrefix("/"))
        if (!file.exists()) return@withContext null
        // Loading a model takes about its file size in memory: only when the phone has that much free (plus 1.5 GB),
        // otherwise the plain rules do the job — Android would close Arin for using too much memory.
        val loaded = com.arin.llm.LocalModel.isUp() && com.arin.llm.LocalModel.lastModel(c)?.name == file.name
        if (!loaded) {
            val mi = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
            if (mi.availMem < file.length() + 1_500_000_000L) return@withContext null
        }
        withTimeoutOrNull(25_000) {
            if (!com.arin.llm.LocalModel.isUp() || com.arin.llm.LocalModel.lastModel(c)?.name != file.name) {
                if (com.arin.llm.LocalModel.start(c, file, 4096) != null) return@withTimeoutOrNull null
            }
            runCatching {
                val body = JSONObject().put("model", "local").put("max_tokens", maxTokens).put("temperature", 0)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val con = java.net.URL("http://127.0.0.1:${com.arin.llm.LocalModel.PORT}/v1/chat/completions").openConnection() as java.net.HttpURLConnection
                con.requestMethod = "POST"; con.doOutput = true; con.connectTimeout = 5_000; con.readTimeout = 20_000
                con.setRequestProperty("Content-Type", "application/json")
                con.outputStream.use { it.write(body.toString().toByteArray()) }
                JSONObject(con.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content").trim()
            }.getOrNull()
        }
    }

    /**
     * Shortens web text to the paragraphs that matter for [topic]: plain rules first (free, exact), then in mode 2 the
     * offline model only picks paragraph NUMBERS — the chosen paragraphs are copied word for word, never rewritten.
     */
    suspend fun shorten(c: Context, topic: String, text: String, keep: Int = 4): String {
        val paras = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.length > 60 }.distinct()
        if (paras.size <= keep) return text
        val words = topic.lowercase().split(Regex("[^a-z0-9_.]+")).filter { it.length > 3 }.toSet()
        val scored = paras.mapIndexed { i, p -> i to words.count { p.lowercase().contains(it) } }.sortedByDescending { it.second }
        val candidates = scored.take(10).map { it.first }.sorted()
        val picked = offline(c, "Topic: ${topic.take(300)}\n\n" + candidates.joinToString("\n\n") { "[$it] " + paras[it].take(400) } +
            "\n\nReply ONLY with the numbers of the $keep paragraphs most useful for the topic, like: 3, 7, 12")
            ?.let { r -> Regex("\\d+").findAll(r).map { it.value.toInt() }.filter { it in candidates }.distinct().take(keep).toList() }
            ?.takeIf { it.isNotEmpty() }
            ?: scored.take(keep).map { it.first }.sorted()
        return picked.joinToString("\n\n") { paras[it] }
    }

    /** A short title (mode 2: by the offline model; otherwise from the first words). Never uses the person's paid model. */
    suspend fun title(c: Context, firstMessage: String): String {
        val plain = firstMessage.lines().firstOrNull { it.isNotBlank() }.orEmpty().split(Regex("\\s+")).take(6).joinToString(" ").take(48)
        val t = offline(c, "Give a 3 to 6 word title for this message. Reply with the title only.\n\n${firstMessage.take(600)}", 16)
            ?.lines()?.firstOrNull()?.trim('"', '*', ' ', '#')?.take(60)
        return t?.takeIf { it.length in 3..60 && !it.contains('\n') } ?: plain.ifBlank { "New chat" }
    }
}

/** Settings → Credit saver: the two modes (only you switch), any offline model for mode 2, and the options. */
@androidx.compose.runtime.Composable
fun CreditSaverSection(vm: com.arin.chat.ChatViewModel) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var mode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(CreditSaver.mode(c)) }
    var offline by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.offlineModel(c)) }
    var haiku by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.haikuFirst(c)) }
    var budget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.dailyBudgetK(c).takeIf { it > 0 }?.toString().orEmpty()) }
    val models = androidx.compose.runtime.remember { com.arin.llm.LocalModel.downloaded(c) }
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    val dim = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val model = vm.modelLabel()
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text("Your model ($model) stays the boss at full power. Only waste is cut. The helper " +
            "never sees your chat or files — only your model's code-only asks.", style = small, color = dim)
        listOf(
            0 to "Off",
            1 to "1 · $model + Claude",
            2 to "2 · $model + offline model + Claude",
        ).forEach { (m, label) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = mode == m, onClick = { mode = m; CreditSaver.setMode(c, m) })
                androidx.compose.material3.Text(label)
            }
        }
        if (mode == 2) {
            androidx.compose.material3.Text("Offline model (filters web pages and writes titles on your phone, free):", style = small)
            if (models.isEmpty()) androidx.compose.material3.Text("No offline models yet — download one in Settings → Offline models.", style = small, color = dim)
            models.forEach { f ->
                val path = com.arin.llm.LocalModel.linuxPath(c, f)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = offline == path, onClick = { offline = path; CreditSaver.setOfflineModel(c, path) })
                    androidx.compose.material3.Text(f.nameWithoutExtension, style = small)
                }
            }
        }
        if (mode > 0) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text("Helper tries Claude Haiku first (saves Claude's limit; off = full power)", style = small, modifier = androidx.compose.ui.Modifier.weight(1f))
                androidx.compose.material3.Switch(haiku, { haiku = it; CreditSaver.setHaikuFirst(c, it) })
            }
            androidx.compose.material3.OutlinedTextField(budget, { v -> budget = v.filter(Char::isDigit).take(6); CreditSaver.setDailyBudgetK(c, budget.toIntOrNull() ?: 0) },
                label = { androidx.compose.material3.Text("Daily warning, thousand tokens (blank = off)") }, singleLine = true)
            WorkerModeUi(vm)
            androidx.compose.material3.Text("Used today by your model: about ${"%,d".format(CreditSaver.usedToday(c))} tokens. Unlimited tries " +
                "pause only when the same error fails 5 times in a row.", style = small, color = dim)
        }
    }
}


/** Settings: worker mode on/off and the list of worker models, in the order they are tried. */
@androidx.compose.runtime.Composable
private fun WorkerModeUi(vm: com.arin.chat.ChatViewModel) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.workerMode(c)) }
    var list by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CreditSaver.workers(c)) }
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    val dim = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    fun save(l: List<CreditSaver.Worker>) { list = l; CreditSaver.setWorkers(c, l) }
    fun name(w: CreditSaver.Worker) = if (w.apiId == "offline") "Offline model" else
        (vm.savedApis.firstOrNull { it.slotId == w.apiId }?.name ?: Worker.BUILT_IN.firstOrNull { it.first == w.apiId }?.second ?: "(removed API)") +
            " · " + w.model.ifBlank { "default model" }
    androidx.compose.material3.HorizontalDivider()
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
            androidx.compose.material3.Text("Worker mode (experiment)")
            androidx.compose.material3.Text("Your model stays the boss: it gives directions, inspects and approves. A worker writes " +
                "the code, the commands and the research summaries, with the helper's help — in every tab (chats, REA, Rebuild, " +
                "Build, Arin Update). The worker and helper never see your chat, files or results; Arin runs the commands and " +
                "shortens the results for your model. Off = the previous method.", style = small, color = dim)
        }
        androidx.compose.material3.Switch(on, { on = it; CreditSaver.setWorkerMode(c, it) })
    }
    if (!on) return
    // 1. Main model (the boss): the model picked in each tab.
    androidx.compose.material3.Text("1 · Main model (boss) — inspects and directs. Each tab keeps its own; this one is for the tab you came from:", style = small)
    com.arin.selffix.ModelSwitcher(vm)
    // 2. Middle model (the worker).
    androidx.compose.material3.Text("2 · Middle model (worker) — writes the code and fixes. Tried in this order (if one refuses, the next one gets the task):", style = small)
    if (list.isEmpty()) androidx.compose.material3.Text("None yet — tap + Add worker and pick any model: Claude, ChatGPT, Gemini, Free, offline or any of your APIs.", style = small, color = dim)
    list.forEachIndexed { i, w ->
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.Text("${i + 1}. " + name(w), style = small, modifier = androidx.compose.ui.Modifier.weight(1f))
            if (i > 0) androidx.compose.material3.TextButton({ save(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { androidx.compose.material3.Text("↑") }
            androidx.compose.material3.TextButton({ save(list.toMutableList().apply { removeAt(i) }) }) { androidx.compose.material3.Text("✕") }
        }
    }
    androidx.compose.material3.TextButton({ adding = true }) { androidx.compose.material3.Text("+ Add worker") }
    // 3. Helper (helps the worker; also the boss when it asks).
    androidx.compose.material3.Text("3 · Helper — helps the worker with hard parts and errors:", style = small)
    var hp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ClaudeHelper.helper(c)) }
    val choices = listOf(ClaudeHelper.Helper("claude") to "Claude (login or key)", ClaudeHelper.Helper("chatgpt") to "ChatGPT", ClaudeHelper.Helper("gemini") to "Gemini",
        ClaudeHelper.Helper("free") to "Free models", ClaudeHelper.Helper("offline") to "Offline model (on the phone)") +
        vm.savedApis.map { ClaudeHelper.Helper("custom", "", it.slotId, it.name) to it.name }
    choices.forEach { (h, n) ->
        val sel = hp.kind == h.kind && (h.kind != "custom" || hp.apiId == h.apiId)
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(sel, { hp = h.copy(model = ""); vm.setHelper(hp) })
            androidx.compose.material3.Text(n, style = small)
        }
    }
    var helperModels by androidx.compose.runtime.remember(hp.kind, hp.apiId) { androidx.compose.runtime.mutableStateOf<List<com.arin.llm.ModelOption>?>(null) }
    androidx.compose.runtime.LaunchedEffect(hp.kind, hp.apiId) {
        helperModels = runCatching { ClaudeHelper.models(c, hp.kind, vm.savedApis.firstOrNull { it.slotId == hp.apiId }) }.getOrDefault(emptyList())
    }
    androidx.compose.material3.Text("Helper model:", style = small)
    ModelList(helperModels, hp.model) { m -> hp = hp.copy(model = m); vm.setHelper(hp) }
    androidx.compose.material3.OutlinedTextField(hp.model, { v -> hp = hp.copy(model = v.trim()); vm.setHelper(hp) },
        label = { androidx.compose.material3.Text("Helper model (blank = its default)") }, singleLine = true)
    val used = CreditSaver.freePoolUsed(c)
    if (used > 0 || vm.savedApis.any { it.baseUrl.contains("experientiallabs") })
        androidx.compose.material3.Text("Experiential Labs free pool today: ${"%,d".format(used)} / 1,000,000 tokens (resets 5:30 AM India time). " +
            "Keep \"Keep going on credits\" off there to never pay.", style = small, color = dim)
    if (adding) {
        var api by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(vm.savedApis.firstOrNull()?.slotId ?: "offline") }
        var model by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("gpt-6-luna") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { adding = false },
            title = { androidx.compose.material3.Text("Add a worker") },
            text = {
                androidx.compose.foundation.layout.Column {
                    (Worker.BUILT_IN + vm.savedApis.map { it.slotId to it.name } + ("offline" to "Offline model (on the phone)")).forEach { (id, n) ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.RadioButton(api == id, { api = id; model = "" })
                            androidx.compose.material3.Text(n, style = small)
                        }
                    }
                    if (api != "offline") {
                        val savedApi = vm.savedApis.firstOrNull { it.slotId == api }
                        val kind = if (savedApi != null) "custom" else api
                        var list by androidx.compose.runtime.remember(api) { androidx.compose.runtime.mutableStateOf<List<com.arin.llm.ModelOption>?>(null) }
                        androidx.compose.runtime.LaunchedEffect(api) { list = runCatching { ClaudeHelper.models(c, kind, savedApi) }.getOrDefault(emptyList()) }
                        ModelList(list, model) { model = it }
                        androidx.compose.material3.OutlinedTextField(model, { model = it.trim() },
                            label = { androidx.compose.material3.Text("Model id (or pick above; blank = default)") }, singleLine = true)
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ save(list + CreditSaver.Worker(api, if (api == "offline") "offline" else model)); adding = false }) { androidx.compose.material3.Text("Add") } },
            dismissButton = { androidx.compose.material3.TextButton({ adding = false }) { androidx.compose.material3.Text("Cancel") } },
        )
    }
}


/** Every model an API offers, to tap one (scrolls when the list is long). */
@androidx.compose.runtime.Composable
private fun ModelList(models: List<com.arin.llm.ModelOption>?, selected: String, pick: (String) -> Unit) {
    val small = androidx.compose.material3.MaterialTheme.typography.bodySmall
    when {
        models == null -> androidx.compose.material3.Text("Loading the models…", style = small)
        models.isEmpty() -> androidx.compose.material3.Text("This API didn't list its models — type the model id below.", style = small)
        else -> androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.heightIn(max = 220.dp)) {
            items(models.size) { i ->
                val m = models[i]
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.fillMaxWidth().clickable { pick(m.id) }.padding(vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(m.id == selected, { pick(m.id) })
                    androidx.compose.material3.Text(m.label, style = small)
                }
            }
        }
    }
}
