package com.arin.selffix

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arin.builder.ChatMessage
import com.arin.builder.Role
import com.arin.builder.THOUGHT
import com.arin.builder.WebTools
import com.arin.builder.streamModel
import com.arin.chat.ChatViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Arin Update tab's conversation: like any chat, you first discuss the update with your model (it can read Arin's
 * real code, search the internet and ask the AI helper) and hear its view. Nothing is built until you tap
 * "Build this update" or tell it to build. The tab's model remembers the whole discussion (saved on the phone).
 */
object ArinUpdateChat {
    /**
     * One line of the conversation. [action] adds buttons under the newest one: "where" (choose where to build),
     * "install:<apk url>", "resume".
     */
    data class Msg(val fromUser: Boolean, val text: String, val action: String = "", val id: String = java.util.UUID.randomUUID().toString())

    val messages = mutableStateListOf<Msg>()

    /** ARIN-PROTECTED: owner-words-only-to-main — the owner's own messages here (removed from anything sent to the worker or helper). */
    private fun ownerWords(): List<String> = messages.toList().filter { it.fromUser }.map { it.text }

    /** Files waiting to go with the next message (any type, any size: big files stay on the phone). */
    val pending = mutableStateListOf<com.arin.data.Attachment>()
    /** Files attached in this discussion, by message: the model sees them, and they go to the build. */
    private val filesOf = HashMap<String, List<com.arin.data.Attachment>>()
    val allFiles: List<com.arin.data.Attachment> get() = filesOf.values.flatten()
    var reading by mutableStateOf<String?>(null)
        private set

    fun attach(context: Context, uris: List<android.net.Uri>) {
        val app = context.applicationContext
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            uris.forEach { u ->
                reading = u.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file"
                runCatching { com.arin.builder.importForBuild(app, u) }
                    .onSuccess { pending += it }
                    .onFailure { messages += Msg(false, "⚠️ Couldn't read that file: ${it.message?.take(200)}") }
                reading = null
            }
        }
    }
    var live by mutableStateOf("")
        private set
    private var statusNow by mutableStateOf<String?>(null)
    var status: String?
        get() = statusNow
        private set(v) { statusNow = v; if (v != null) Activity.log("arin-update", v) }
    var busy by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** Messages sent while the model is answering: it reads them at its next step. */
    val steering = mutableStateListOf<String>()

    fun steer(context: Context, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        steering += t
        messages += Msg(true, "↪ $t")
        save(context)
    }
    private var loaded = false
    private var files: List<String>? = null
    private var repo: SelfRepo.Found? = null

    private fun file(context: Context) = java.io.File(context.filesDir, "arin_update_chat.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(file(context).readText())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                messages += Msg(it.optBoolean("u"), it.optString("t"), it.optString("a"), it.optString("id").ifBlank { java.util.UUID.randomUUID().toString() })
            }
        }
    }

    private fun save(context: Context) {
        runCatching {
            val arr = JSONArray()
            messages.forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text).put("a", it.action).put("id", it.id)) }
            file(context).writeText(arr.toString())
        }
    }

    /** Asks the AI helper from this tab's message box: question and answer go into the discussion (the tab's model sees them). */
    fun askHelper(context: Context, vm: ChatViewModel, question: String) {
        val app = context.applicationContext
        val who = vm.helperLabel
        val recent = messages.takeLast(8).joinToString("\n\n") { (if (it.fromUser) "Owner: " else "Developer: ") + it.text.take(1_500) }
        messages += Msg(true, "🟣 Ask $who: $question")
        save(app)
        com.arin.app.ReplyService.hold(app, "helper-update")
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            status = "$who is answering…"
            val a = try { vm.askHelper(question, recent, source = "arin-update") } finally { com.arin.app.ReplyService.release(app, "helper-update") }
            status = null
            messages += Msg(false, "🟣 **$who:**\n\n$a")
            save(app)
        }
    }

    /** Adds a line to the discussion (e.g. "building now"), saved with it. */
    fun note(context: Context, text: String, action: String = "") {
        messages += Msg(false, text, action)
        save(context)
    }

    /** The owner chose "build and install by itself": after the build, the APK is downloaded and the installer opens. */
    var autoInstall by mutableStateOf(false)

    /** A build of the tab ended: say so in the conversation, with the next step's buttons. */
    fun onFinished(context: Context, result: com.arin.builder.BuildResult, session: com.arin.builder.BuildSession?) {
        val app = context.applicationContext
        // Direct download links from a free file site (found after the build), when there are some.
        val links = result.message.substringAfter("📥", "").let { if (it.isBlank()) "" else "\n\n📥" + it }
        val advice = session?.timeline?.toList()?.filterIsInstance<com.arin.builder.TimelineItem.Event>()
            ?.lastOrNull { it.event.text.startsWith("🔎") }?.event?.text?.removePrefix("🔎 How to fix (researched on the internet):")?.trim()
        when {
            result.message.startsWith(com.arin.builder.BuildAgent.CODE_READY) -> {
                val changed = result.message.removePrefix(com.arin.builder.BuildAgent.CODE_READY).split(',').filter { it.isNotBlank() }
                note(app, "✍️ **The code is written.** Changed ${changed.size} file${if (changed.size == 1) "" else "s"}: " +
                    changed.take(12).joinToString(", ") { it.substringAfterLast('/') } + (if (changed.size > 12) ", …" else "") +
                    // ARIN-PROTECTED: explain-after-coding
                    (if (result.explanation.isNotBlank()) "\n\n" + result.explanation.trim() else "") +
                    "\n\n**Where should I build it?**", "where")
            }
            result.success && result.apkUrl != null -> {
                note(app, "✅ **The new version of Arin is built.** Tap **Install** to update — it installs over the Arin you have now " +
                    "(chats and settings stay).$links" +
                    (if (result.explanation.isNotBlank()) "\n\n" + result.explanation.trim() else ""), "install:" + result.apkUrl)
                if (autoInstall) {
                    autoInstall = false
                    com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) { note(app, install(app, result.apkUrl)) }
                }
            }
            result.success && result.phoneInstallPath != null -> {
                note(app, "✅ **The new version of Arin is built on this phone.** Tap **Install** to update — it installs over the Arin you " +
                    "have now. (The changes were saved to Arin's source on GitHub too.)$links", "install:file:" + result.phoneInstallPath)
                if (autoInstall) {
                    autoInstall = false
                    com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) { note(app, installFile(app, result.phoneInstallPath)) }
                }
            }
            result.message == "Stopped." -> note(app, "⏸ **Stopped.** Everything done so far is saved — tap **Resume** to carry on.", "resume")
            else -> note(app, "⚠️ **It stopped:** ${PlainSummary.plainError(result.message)}" +
                (if (!advice.isNullOrBlank()) "\n\n🔎 $advice" else "") + "\n\nEverything done so far is saved.", "resume")
        }
    }

    /** Installs an APK built on this phone, only if it is Arin with the same key. Returns what happened. */
    fun installFile(context: Context, path: String): String {
        val apk = java.io.File(path)
        if (!apk.exists()) return "The built APK isn't on the phone any more. Build it again."
        // ARIN-PROTECTED: only an Arin APK with the same signing key and a version that isn't older.
        SelfUpdateRules.checkApk(context, apk)?.let { return it }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Allow \"Install unknown apps\" for Arin, come back, and tap Install again."
        }
        com.arin.builder.ApkInstaller.install(context, apk)
        return "Opening the installer: it updates the Arin you have now."
    }

    /** Downloads the built APK and opens Android's installer, only if it is Arin with the same key. Returns what happened. */
    suspend fun install(context: Context, url: String, onProgress: (String) -> Unit = {}): String = try {
        val apk = com.arin.builder.ApkInstaller.download(context, url) { p -> onProgress("Downloading the update… ${(p * 100).toInt()}%") }
        // ARIN-PROTECTED: only an Arin APK with the same signing key and a version that isn't older.
        val blocked = SelfUpdateRules.checkApk(context, apk)
        when {
            blocked != null -> blocked
            android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls() -> {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + context.packageName),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Allow \"Install unknown apps\" for Arin, come back, and tap Install again."
            }
            else -> {
                com.arin.builder.ApkInstaller.install(context, apk)
                "⬇️ Downloaded (also saved in Arin's folder). Opening the installer: it updates the Arin you have now."
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        "Couldn't download the update: ${PlainSummary.plainError(e.message ?: "")}"
    }

    fun clear(context: Context) {
        stop()
        messages.clear()
        pending.clear()
        filesOf.clear()
        approvalAsk = null
        approved = false
        save(context)
    }

    fun stop() {
        job?.cancel()
        job = null
        busy = false
        status = null
        if (live.isNotBlank()) messages += Msg(false, live.trim() + "\n\n_(stopped)_")
        live = ""
    }

    private val readTag = Regex("""<read_source>\s*([^<\s]{3,300})\s*</read_source>""")
    private val runTag = Regex("""<run>\s*([\s\S]{1,4000}?)\s*</run>""")
    private val updateTag = Regex("""<update_arin>([\s\S]+?)</update_arin>""")
    private val approvalTag = Regex("""<ask_approval>([\s\S]{3,2000}?)</ask_approval>""")

    /** What the model asked the owner to approve (a change to a protected rule), waiting for Approve or Keep. */
    var approvalAsk by mutableStateOf<String?>(null)
        private set

    /** True once the owner approved changing a protected rule for the next build. */
    var approved by mutableStateOf(false)
        private set

    /** The owner's answer to [approvalAsk]; it goes into the discussion so the model knows. */
    fun answerApproval(context: Context, yes: Boolean) {
        val what = approvalAsk ?: return
        approvalAsk = null
        approved = yes
        messages += Msg(true, if (yes) "✅ I approve this change to a protected rule: $what" else "❌ I don't approve changing the protected rule. Keep it as it is.")
        save(context)
    }

    /** After a build starts, the approval is used up. */
    fun consumeApproval(): Boolean = approved.also { approved = false }

    private fun system(context: Context, fileList: List<String>) = buildString {
        append("You are Arin's own developer, talking with Arin's owner in the Arin Update tab. Arin is the Android AI ")
        append("assistant app (Kotlin, Jetpack Compose, package com.arin, app id com.arin.app) you are running in.\n")
        append("FIRST DISCUSS: understand what the owner wants changed in Arin, give your honest view (how you'd build it, ")
        append("which files change, risks, simpler options), suggest improvements, and ask about anything unclear. Keep ")
        append("track of every point agreed. Do NOT start building on your own.\n")
        append("Build ONLY when the owner says so (\"build\", \"update now\", \"go ahead\"). Then end your reply with ")
        append("<update_arin>a complete, detailed specification of every change agreed in this discussion</update_arin>.\n")
        append("Tools (write ONE as the LAST thing in a reply, then stop; the app sends the result and you continue): ")
        append("<read_source>path/of/a/file</read_source> reads one of Arin's real source files; ")
        append("<search>words</search> searches the internet; <fetch>https://url</fetch> reads a page; ")
        append("<run>command</run> runs a command in Arin's built-in Linux (Ubuntu, as root: apt, git, curl, python, node…) ")
        append("and sends you the output — use Linux, the terminal and GitHub whenever the work needs them.\n")
        append(LinuxGitHub.note(com.arin.data.Settings(context).secret("GITHUB")))
        append(com.arin.terminal.Linux.TOOLS_NOTE).append('\n') // ARIN-PROTECTED: apk-toolkit in every tab
        append("Always research the internet first when you aren't sure how to do something.\n")
        append("PROTECTED RULES (research-first auto-fix, never changing the Custom API, Arin-only updates with the same ")
        append("app id and key, and the AI helper's never-store rule) can only change with the owner's approval. If a ")
        append("requested feature truly needs one changed, explain why and ask first with ")
        append("<ask_approval>which rule, the exact change and why</ask_approval>; wait for the answer before building.\n")
        append(SelfUpdateRules.RULE_FOR_MODEL).append('\n')
        append(AutoFix.RULE_GENERAL).append("\n\n")
        append(ClaudeHelper.instructions(context, "arin-update"))
        append(Worker.instructions(context)) // ARIN-PROTECTED: team-work in every tab
        append(TeamWork.instructions(context)) // ARIN-PROTECTED: team-work in every tab
        append(TaskSummary.RULE) // ARIN-PROTECTED: task-summary in every tab
        if (fileList.isNotEmpty()) {
            append("Arin's source files (read any with <read_source>):\n")
            append(fileList.joinToString("\n").take(12_000)).append('\n')
        }
        append("\nAnswer in plain, friendly English. Keep replies short unless detail is asked for.")
    }

    /** Sends the owner's message; the model answers with the whole discussion in mind. */
    fun send(context: Context, vm: ChatViewModel, text: String, onBuild: (String) -> Unit, consultHelper: Boolean = false) {
        val attachedNow = pending.toList()
        val t = text.trim().ifEmpty { if (attachedNow.isNotEmpty()) "Look at the attached file(s)." else "" }
        if (t.isEmpty() || busy) return
        pending.clear()
        val userMsg = Msg(true, t + if (attachedNow.isNotEmpty()) "\n\n📎 " + attachedNow.joinToString(", ") { it.name } else "")
        if (attachedNow.isNotEmpty()) filesOf[userMsg.id] = attachedNow
        messages += userMsg
        save(context)
        busy = true
        live = ""
        val app = context.applicationContext
        val provider = vm.provider
        val model = vm.model
        val key = vm.key(provider)
        // The tab's own API, fixed for this reply: never switched.
        val endpoint = if (provider == com.arin.llm.Provider.Custom) com.arin.llm.CustomApi.Endpoint(vm.customBaseUrl, vm.customFormat) else null
        val token = vm.githubToken()
        com.arin.app.ReplyService.hold(app, "arin-update")
        job = com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main + TabScope("arin-update")) {
            try {
                if (files == null && token.isNotBlank()) {
                    status = "Looking at Arin's source on GitHub"
                    val found = SelfRepo.resolve(app, token, vm.selfRepo(), vm.selfBranch()) { status = it.take(120) }.getOrNull()
                    repo = found
                    files = found?.let { SelfRepo.fileList(token, it.repo, it.branch) }.orEmpty()
                }
                val sys = system(app, files.orEmpty())
                val turns = messages.toList().map { msg ->
                    val files = filesOf[msg.id].orEmpty()
                    if (files.isEmpty()) ChatMessage(if (msg.fromUser) Role.USER else Role.ASSISTANT, msg.text)
                    else {
                        // Models that can't see get pictures and videos described for them.
                        val (keep, extra) = VisionBridge.prepareFiles(app, provider, model, files) { status = it }
                        ChatMessage(Role.USER, msg.text + extra, keep)
                    }
                }.toMutableList()
                var steps = 0
                // ARIN-PROTECTED: task-summary in every tab — the steps done, for the summary at the end.
                val done = mutableListOf<String>()
                var lastFailed = false
                var helperAsks = 0
                var consulted = false
                // ARIN-PROTECTED: helper-sees-only-the-boss — the helper never sees the owner's messages or files. When a
                // step fails, only the error lines and the developer's (boss's) own last step go to it, and its answer comes
                // back as a private note for the developer, who stays in charge.
                suspend fun guide(errorText: String): String? {
                    if (!consultHelper || !ClaudeHelper.enabled(app, "arin-update")) return null
                    val errLines = errorText.lines().filter { Regex("(?i)(FAILED|error|exception|not found|denied|couldn't|cannot|unable|exit code|warning|at )").containsMatchIn(it) }
                        .take(40).joinToString("\n").take(4_000)
                    val bossStep = turns.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty().take(3_000)
                    val q = "STEP:\n$bossStep\nERROR:\n$errLines\nReturn the fixed step or patched code only."
                    val a = ClaudeHelper.ask(app, q, source = "arin-update", ownerTexts = ownerWords()) { status = it }
                    return a.takeIf { it.isNotBlank() && !it.contains("NO_HELP_NEEDED") && !it.contains("couldn't be reached") && !it.contains("isn't connected") && !it.contains("switched off") }
                }
                var guides = 0
                while (true) {
                    // Directions sent while working: read now.
                    if (steering.isNotEmpty()) {
                        val s = steering.toList(); steering.clear()
                        turns += ChatMessage(Role.USER, "[The owner added while you were working — follow it:]\n" + s.joinToString("\n"))
                    }
                    status = if (steps == 0) "Thinking" else status
                    val sb = StringBuilder()
                    try {
                        streamModel(provider, key, model, turns.toList(), sys, endpoint).collect { chunk ->
                            if (chunk.startsWith(THOUGHT)) return@collect
                            sb.append(chunk)
                            live = cleanTags(sb.toString())
                            status = null
                            // Speed: act on a finished tool step at once, without waiting for the rest of the reply.
                            if (chunk.contains('>') && TeamWork.stepComplete(sb.toString())) throw StepReady()
                        }
                    } catch (e: StepReady) { /* go on with the step now */ }
                    var reply = sb.toString()
                    // ARIN-PROTECTED: team-work in every tab — the boss directs; the worker writes the command without seeing
                    // the owner's messages, Arin's files or any output; Arin runs it and shrinks the result for the boss.
                    val team = TeamWork.on(app)
                    val bossName = Worker.nice(model)
                    if (team && steps < 30) {
                        val direction = TeamWork.doTask(reply)
                        val workerTask = if (direction == null) Worker.task(reply) else null
                        if (direction != null) {
                            status = "$bossName is instructing the worker: " + direction.lineSequence().first().take(80)
                            val (who, tool) = TeamWork.command(app, direction, bossName) { _, s -> status = s.take(120) }
                            val base = TeamWork.clean(reply).trimEnd()
                            if (who.isBlank() || tool.isBlank()) {
                                turns += ChatMessage(Role.ASSISTANT, reply.trim())
                                turns += ChatMessage(Role.USER, "[No worker could do this step. Do this one step yourself, briefly, then go back to giving directions.]")
                                steps++
                                continue
                            }
                            reply = "$base\n[$who wrote:] " + tool.replace(Regex("(?s)<install>(.*?)</install>"), "<run>apt-get install -y $1</run>")
                        } else if (workerTask != null) {
                            // Heavy writing (code, long text) goes to the worker; the boss inspects what comes back.
                            steps++
                            status = "$bossName is instructing the worker: " + workerTask.lineSequence().first().take(80)
                            val wr = Worker.runDetailed(app, TeamWork.safeDirection(workerTask), bossName, tab = "arin-update", ownerTexts = ownerWords()) { _, s -> status = s.take(120) }
                            val who = wr.who
                            val result = if (wr.ok) wr.text else ""
                            turns += ChatMessage(Role.ASSISTANT, reply.trim())
                            turns += ChatMessage(Role.USER, if (result.isBlank()) "[No worker did this task — ${wr.summary()}. Decide the next step: " +
                                "restate a misunderstood task, respect a genuine refusal, or do it yourself, briefly.]"
                                else "[WORKER RESULT from ${Worker.nice(who)} — inspect it; send directions in another <ask_worker> if it's wrong.]\n" + result.take(12_000))
                            continue
                        }
                    }
                    val read = readTag.find(reply)?.groupValues?.get(1)
                    val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val ask = if (ClaudeHelper.enabled(app, "arin-update") && helperAsks < ClaudeHelper.maxAsks(app)) ClaudeHelper.question(reply) else null
                    val cmd = runTag.find(reply)?.groupValues?.get(1)?.trim()
                    if (steps < 30 && (read != null || search != null || fetch != null || ask != null || cmd != null)) {
                        steps++
                        val result = when {
                            cmd != null -> {
                                status = "Running: " + cmd.lineSequence().first().take(60)
                                if (!com.arin.terminal.Linux.installed(app)) "Arin's Linux isn't installed yet (Settings → Linux → Install Linux), so the command couldn't run."
                                else {
                                    LinuxGitHub.sync(app, token)
                                    com.arin.terminal.Linux.ensureToolsFor(app, cmd) { status = it.take(120) } // ARIN-PROTECTED: apk-toolkit in every tab
                                    val r = com.arin.terminal.Shell.run(app, cmd, 15 * 60_000L)
                                    val out = (r.stdout + "\n" + r.stderr).trim()
                                    val code = if (TeamWork.nothingFound(r.exitCode, out)) "0 (nothing found — not an error)" else "${r.exitCode}"
                                    "Command: $cmd\nExit code: $code\nOutput:\n" + if (team) TeamWork.shrink(out) else out.takeLast(12_000)
                                }
                            }
                            read != null -> {
                                status = "Reading Arin's ${read.substringAfterLast('/')}"
                                val f = repo ?: SelfRepo.resolve(app, token, vm.selfRepo(), vm.selfBranch()) { }.getOrNull().also { repo = it }
                                val txt = f?.let { SelfRepo.readFile(token, it.repo, it.branch, read) }
                                if (txt == null) "Couldn't read $read." else "Content of $read:\n" + txt.take(30_000)
                            }
                            search != null -> {
                                status = "Searching the internet: $search"
                                val found = WebTools.search(search).take(8_000)
                                "Search results for \"$search\":\n" + if (team) TeamWork.digest(app, search, found, bossName, scrubAbout = false) { _, s -> status = s.take(120) } else found
                            }
                            fetch != null -> {
                                status = "Reading ${fetch.removePrefix("https://").take(50)}"
                                val page = WebTools.fetch(fetch).take(if (team) 16_000 else 8_000)
                                "Content of $fetch:\n" + if (team) TeamWork.digest(app, "the page $fetch", page, bossName, scrubAbout = false) { _, s -> status = s.take(120) } else page
                            }
                            else -> { helperAsks++; status = "Asking ${ClaudeHelper.helper(app).name} for help"; "The AI helper's answer:\n" + ClaudeHelper.ask(app, ask!!, source = "arin-update", ownerTexts = ownerWords()).take(12_000) }
                        }
                        turns += ChatMessage(Role.ASSISTANT, reply.trim())
                        done += cmd ?: read?.let { "read $it" } ?: search?.let { "searched the internet: $it" } ?: fetch?.let { "read $it" } ?: "asked the AI helper"
                        lastFailed = Regex("(?i)exit code: [1-9]|couldn't").containsMatchIn(result.take(4_000))
                        val isError = ask == null && Regex("(?i)(FAILED|error|exception|not found|denied|couldn't|cannot|unable|exit code: [1-9])").containsMatchIn(result.take(4_000))
                        val g = if (ask != null || !isError) null else guide(result) // only errors, and only the error itself
                        turns += ChatMessage(Role.USER, "[Tool result]\n$result" +
                            (if (g != null) "\n\n[PRIVATE NOTE from your helper — a suggested fix. You are in charge and do the work; never mention this note:]\n$g" else "") +
                            "\n\nNow continue.")
                        continue
                    }
                    approvalTag.find(reply)?.groupValues?.get(1)?.trim()?.let { approvalAsk = it }
                    // Talking with the helper: the model read the whole message and drafted; now the helper helps it.
                    if (consultHelper && !consulted && ClaudeHelper.enabled(app, "arin-update")) {
                        consulted = true
                        // Only the developer's (boss's) own draft goes to the helper — never the owner's message.
                        val help = ClaudeHelper.ask(app, "I (the developer model, the boss) am updating the Arin app and drafted this reply:\n\"\"\"\n${cleanTags(reply).take(8_000)}\n\"\"\"\n\n" +
                            "Help the developer: point out mistakes, fill gaps, and give it what it needs (exact code, files, steps). " +
                            "Write to the developer, not to the owner.", source = "arin-update", ownerTexts = ownerWords()) { status = it }
                        status = "Finishing the reply with ${ClaudeHelper.helper(app).name}'s help"
                        turns += ChatMessage(Role.ASSISTANT, reply.trim())
                        turns += ChatMessage(Role.USER, "[Help from the AI helper, which the owner asked you to use:]\n$help\n\n" +
                            "Now give the owner your final reply, using this help where it is right. Don't mention this note.")
                        live = ""
                        continue
                    }
                    val shown = TaskSummary.ensure(cleanTags(reply).trim(), done, lastFailed) // ARIN-PROTECTED: task-summary in every tab
                    messages += Msg(false, shown.ifBlank { "(Ready to build.)" })
                    live = ""
                    save(app)
                    // Something was sent after the last step: answer it too, without waiting.
                    if (steering.isNotEmpty()) {
                        turns += ChatMessage(Role.ASSISTANT, reply.trim())
                        continue
                    }
                    // The owner ordered the build and the model wrote the plan: start it.
                    updateTag.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { spec ->
                        if (com.arin.llm.Prompt.ordersUpdate(t) || Regex("(?i)\\b(build|go ahead|update now|do it|start)\\b").containsMatchIn(t)) {
                            onBuild(spec + "\n\n" + transcript())
                        }
                    }
                    break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ARIN-PROTECTED: research-first-error-help — search the internet first, then say how to fix it.
                status = "Searching the internet for this error"
                val advice = runCatching {
                    ErrorDoctor.diagnose(app, "the Arin Update discussion (${provider.label}, $model)", e.message ?: e.toString(),
                        ErrorDoctor.Brain(provider, key, model, endpoint)) { status = it.take(120) }
                }.getOrNull()
                messages += Msg(false, "⚠️ The model couldn't answer: ${PlainSummary.plainError(e.message ?: e.toString())}" +
                    (if (!advice.isNullOrBlank()) "\n\n🔎 **I searched the internet for this error.**\n\n$advice" else "") +
                    "\n\nSend your message again to carry on.")
                save(app)
            } finally {
                busy = false
                status = null
                live = ""
                com.arin.app.ReplyService.release(app, "arin-update")
            }
        }
    }

    /** The discussion, for the builder: everything agreed. */
    fun transcript(): String = buildString {
        append("THE DISCUSSION WITH THE OWNER (build everything agreed here):\n")
        messages.forEach { append(if (it.fromUser) "Owner: " else "Developer: ").append(it.text.take(4_000)).append("\n\n") }
    }.let { if (it.length > 30_000) it.take(6_000) + "\n[…]\n" + it.takeLast(24_000) else it }

    private fun cleanTags(s: String) = Worker.clean(TeamWork.clean(ClaudeHelper.clean(s))).replace(Worker.RESULT_TAG, "")
        .replace(readTag, "").replace(updateTag, "").replace(approvalTag, "").replace(runTag, "")
        .replace(WebTools.searchTag, "").replace(WebTools.fetchTag, "")
        .replace(Regex("""<(read_source|update_arin|search|fetch|ask_approval|run)>[\s\S]*$"""), "")

    /** The reply already holds a complete tool step: stop reading and act on it now (speed). */
    private class StepReady : Exception()
}
