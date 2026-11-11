package com.aiapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.aiapp.data.ApiKeys
import com.aiapp.data.ApkInstaller
import com.aiapp.data.Log
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.reverse.ApkEdit
import com.aiapp.reverse.ApkReport
import com.aiapp.reverse.ReaClient
import com.aiapp.reverse.ReaConfig
import com.aiapp.reverse.ReaTool
import com.aiapp.reverse.ReverseAgent
import com.aiapp.reverse.ReverseCiRunner
import com.aiapp.reverse.ReverseEngine
import com.aiapp.reverse.SmaliEngine
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

/**
 * Drives the Reverse Engineering tab as one steerable conversation, like Chat:
 *
 * Load the target (APK, a .zip containing one, or a direct download URL), pick a model, then just
 * say what you want — a question gets a plain answer; a change is planned, and if it needs code the
 * agent automatically escalates to a smali patch; either way GitHub Actions builds and signs the
 * result when you have a GitHub token (on-device otherwise). You can send another message *while it's
 * working* and it is folded in at the next checkpoint — before escalating to a code patch, and again
 * right before the GitHub build is kicked off (that external job itself can't be redirected once
 * started, the same way any tool call can't once dispatched — but the next one always can be).
 *
 * For apps you are authorized to work on: your own apps, open-source apps, security research, modding.
 */
class ReverseViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)
    private val prefs = app.getSharedPreferences("reverse", android.content.Context.MODE_PRIVATE)

    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var report by mutableStateOf<ApkReport?>(null); private set

    private var apkFile: File? = null

    /** The whole conversation about this app: your messages, live progress, and final results. */
    val transcript = mutableStateListOf<ChatMessage>()
    var answering by mutableStateOf(false); private set
    var liveAnswer by mutableStateOf(""); private set

    /** Captured when you send a message while the agent is already working; folded in at the next checkpoint. */
    private var pendingSteer: String? = null

    // remote rea
    var reaUrl by mutableStateOf(prefs.getString("rea_url", "").orEmpty())
    var reaConnected by mutableStateOf(false); private set
    var reaInfo by mutableStateOf<String?>(null); private set
    val reaTools = mutableStateListOf<ReaTool>()
    private var rea: ReaClient? = null

    fun clearError() { error = null }

    // ---------- Loading the target ----------

    fun loadApk(uri: Uri) {
        viewModelScope.launch {
            loading = true; error = null; status = "Copying APK…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val name = queryName(uri) ?: "target.apk"
                val dst = File(dir, if (name.endsWith(".apk", true)) name else "$name.apk")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open that file." }
                    dst.outputStream().use { input.copyTo(it) }
                }
                finishLoad(dst)
            } catch (e: Exception) {
                Log.e("Reverse", "load failed", e)
                error = e.message ?: "Couldn't read that APK."
                status = null
            } finally {
                loading = false
            }
        }
    }

    /** A .zip that contains an APK (e.g. a zipped app) — the first `.apk` entry inside it is used. */
    fun loadZip(uri: Uri) {
        viewModelScope.launch {
            loading = true; error = null; status = "Looking for an APK inside the zip…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val tmpZip = File(dir, "upload.zip")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open that file." }
                    tmpZip.outputStream().use { input.copyTo(it) }
                }
                val dst = File(dir, "from-zip.apk")
                ZipFile(tmpZip).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".apk", true) }
                        ?: throw IllegalStateException("No .apk file found inside that zip.")
                    zip.getInputStream(entry).use { input -> dst.outputStream().use { input.copyTo(it) } }
                }
                tmpZip.delete()
                finishLoad(dst)
            } catch (e: Exception) {
                Log.e("Reverse", "load zip failed", e)
                error = e.message ?: "Couldn't read that zip."
                status = null
            } finally {
                loading = false
            }
        }
    }

    /** A direct download link to an APK (your own hosting, a GitHub release, F-Droid, etc.). */
    fun loadUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) { error = "Paste a direct APK download link first."; return }
        viewModelScope.launch {
            loading = true; error = null; status = "Downloading…"; report = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val file = ApkInstaller.download(ctx, trimmed) { p -> status = "Downloading… ${(p * 100).toInt()}%" }
                finishLoad(file)
            } catch (e: Exception) {
                Log.e("Reverse", "load url failed", e)
                error = e.message ?: "Couldn't download that URL."
                status = null
            } finally {
                loading = false
            }
        }
    }

    private suspend fun finishLoad(dst: File) {
        apkFile = dst
        status = "Decoding on device…"
        report = ReverseEngine.inspect(dst)
        status = null
        val r = report!!
        transcript.add(
            ChatMessage(
                Role.ASSISTANT,
                "Loaded **${r.appLabel.ifBlank { r.packageName }}**. ${r.classCount} classes, ${r.methodCount} methods, " +
                    "${r.permissions.size} permissions, signed with ${r.signature.summary}.\n\n" +
                    "Tell me what you want — ask a question, or describe a change. You can send another message " +
                    "while I'm working and I'll fold it in.",
            ),
        )
    }

    private fun queryName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    // ---------- The conversation ----------

    private val questionLike = Regex("^(how|what|why|where|when|does|is|are|can|could|who|which)\\b", RegexOption.IGNORE_CASE)

    /** One box, like Chat: ask or instruct. Sending again while busy steers the current run instead of starting a new one. */
    fun send(text: String, provider: ProviderId, model: String, effort: Effort) {
        val instruction = text.trim()
        if (instruction.isBlank()) return
        val r = report
        if (r == null) { error = "Load an APK first."; return }
        transcript.add(ChatMessage(Role.USER, instruction))
        if (answering) {
            pendingSteer = ((pendingSteer ?: "") + "\n" + instruction).trim()
            transcript.add(ChatMessage(Role.ASSISTANT, "Got it — I'll factor that in at the next step."))
            return
        }
        viewModelScope.launch {
            answering = true; error = null
            try {
                val isQuestion = instruction.contains('?') || questionLike.containsMatchIn(instruction)
                if (isQuestion) {
                    answerQuestion(r, instruction, provider, model, effort)
                } else {
                    runChange(r, instruction, provider, model, effort)
                }
            } catch (e: Exception) {
                Log.e("Reverse", "agent turn failed", e)
                transcript.add(ChatMessage(Role.ASSISTANT, "That failed: ${e.message}"))
            } finally {
                answering = false; liveAnswer = ""; status = null
            }
        }
    }

    /** Takes and clears any message sent while the agent was working, to fold into the next step. */
    private fun takeSteer(): String? = pendingSteer.also { pendingSteer = null }

    private suspend fun answerQuestion(r: ApkReport, question: String, provider: ProviderId, model: String, effort: Effort) {
        status = "Thinking…"
        liveAnswer = ""
        val context = "DECODED APK (on-device):\n" + r.toBriefing() +
            (if (reaConnected) "\n\nA remote rea backend is connected with ${reaTools.size} tools for deeper analysis." else "")
        Providers.get(provider).stream(keys.get(provider), model, listOf(ChatMessage(Role.USER, "$context\n\nQuestion: $question")), effort, SYSTEM_PROMPT)
            .collect { chunk -> if (!chunk.startsWith(THOUGHT)) liveAnswer += chunk }
        transcript.add(ChatMessage(Role.ASSISTANT, liveAnswer))
    }

    private suspend fun runChange(r: ApkReport, instructionIn: String, provider: ProviderId, model: String, effort: Effort) {
        var instruction = instructionIn
        val apk = apkFile ?: return

        status = "Reading the app's text…"
        val strings = ReverseEngine.readStrings(apk)
        status = "Planning the change…"
        val planReply = collectReply(provider, model, ReverseAgent.buildPrompt(r, strings, instruction), ReverseAgent.SYSTEM, effort)
        val plan = ReverseAgent.parsePlan(planReply)
        var edits = plan.toApkEdits()
        var smaliEdits: Map<String, String> = emptyMap()

        if (edits.isEmpty()) {
            // Fold in anything you sent while that first round was thinking, before going deeper.
            takeSteer()?.let { steer ->
                instruction = "$instruction\n\nAdditional direction from the user: $steer"
                transcript.add(ChatMessage(Role.ASSISTANT, "Incorporating what you just added…"))
            }
            transcript.add(ChatMessage(Role.ASSISTANT, "That needs a code change — looking at the app's logic…"))
            val api = r.minSdk.toIntOrNull() ?: 21
            val work = File(getApplication<Application>().cacheDir, "reverse/smali").apply { deleteRecursively(); mkdirs() }
            val engine = SmaliEngine(work, api)

            status = "Finding the relevant code…"
            val termsReply = collectReply(provider, model, ReverseAgent.termsPrompt(r, instruction), ReverseAgent.TERMS_SYSTEM, effort)
            val terms = ReverseAgent.parseTerms(termsReply, instruction)

            status = "Reading candidate classes (on-device, read-only)…"
            val candidates = engine.search(apk, terms)
            if (candidates.isEmpty()) {
                transcript.add(ChatMessage(Role.ASSISTANT, "Couldn't find code matching that. Try naming the class, SDK, or exact text involved. (searched: ${terms.joinToString()})"))
                return
            }
            status = "Writing the patch with ${provider.label}…"
            val editReply = collectReply(provider, model, ReverseAgent.smaliEditPrompt(instruction, candidates), ReverseAgent.SMALI_SYSTEM, effort)
            smaliEdits = ReverseAgent.parseSmaliEdits(editReply)
            if (smaliEdits.isEmpty()) {
                transcript.add(ChatMessage(Role.ASSISTANT, "Couldn't produce a safe code patch for that. Try a stronger model or a more specific instruction."))
                return
            }
            transcript.add(ChatMessage(Role.ASSISTANT, "Plan: patching ${smaliEdits.size} class(es): ${smaliEdits.keys.joinToString().take(200)}"))
        } else {
            transcript.add(ChatMessage(Role.ASSISTANT, "Plan: ${plan.summary.ifBlank { "${edits.size} edit(s)" }}"))
        }

        // Last checkpoint: anything sent right up to now is folded into the build about to start.
        takeSteer()?.let { steer ->
            transcript.add(ChatMessage(Role.ASSISTANT, "One more thing before building — \"$steer\". Re-planning with that included…"))
            val reReply = collectReply(
                provider, model,
                ReverseAgent.buildPrompt(r, strings, "$instruction\n\nAlso: $steer"),
                ReverseAgent.SYSTEM, effort,
            )
            val rePlan = ReverseAgent.parsePlan(reReply)
            if (rePlan.toApkEdits().isNotEmpty()) { edits = rePlan.toApkEdits(); smaliEdits = emptyMap() }
            transcript.add(ChatMessage(Role.ASSISTANT, "Updated plan: ${rePlan.summary.ifBlank { "${edits.size} edit(s)" }}"))
        }

        val token = githubTokenOf()
        status = "Building…"
        if (token.isNotBlank()) {
            transcript.add(ChatMessage(Role.ASSISTANT, "Building on GitHub Actions…"))
            val runner = ReverseCiRunner(token) { s -> status = s }
            val result = runner.run(apk, r.appLabel.ifBlank { r.packageName }, edits, smaliEdits)
            transcript.add(ChatMessage(Role.ASSISTANT, "Built and signed. Tap below to download and install.", apkInstallUrl = result.apkUrl))
        } else {
            transcript.add(ChatMessage(Role.ASSISTANT, "No GitHub token set, so rebuilding on-device (slower)…"))
            val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
            outDir.deleteRecursively()
            val dexEdits = if (smaliEdits.isNotEmpty()) {
                val api = r.minSdk.toIntOrNull() ?: 21
                SmaliEngine(File(getApplication<Application>().cacheDir, "reverse/smali"), api).buildEdits(apk, smaliEdits)
            } else emptyList()
            val allEdits = edits + dexEdits
            val res = ReverseEngine.rebuild(getApplication(), apk, allEdits, outDir)
            localInstallReady = res.outApk
            transcript.add(ChatMessage(Role.ASSISTANT, "Built and signed on-device (${res.signedWith}). Install it below."))
        }
    }

    /** Set after an on-device build; the screen renders a plain Install button for it (no download needed). */
    var localInstallReady by mutableStateOf<File?>(null); private set

    fun installLocal() {
        localInstallReady?.let { ApkInstaller.install(getApplication(), it) }
    }

    private suspend fun collectReply(provider: ProviderId, model: String, prompt: String, system: String, effort: Effort): String {
        val sb = StringBuilder()
        Providers.get(provider).stream(keys.get(provider), model, listOf(ChatMessage(Role.USER, prompt)), effort, system)
            .collect { chunk -> if (!chunk.startsWith(THOUGHT)) sb.append(chunk) }
        return sb.toString()
    }

    private fun githubTokenOf(): String = keys.getExtra("GITHUB")

    // ---------- remote rea ----------

    fun connectRea() {
        val url = reaUrl.trim()
        if (url.isBlank()) { error = "Enter your rea server URL first."; return }
        prefs.edit().putString("rea_url", url).apply()
        viewModelScope.launch {
            busy = true; error = null; status = "Connecting to rea…"
            try {
                val client = ReaClient(ReaConfig(url))
                reaInfo = client.connect()
                val tools = client.listTools()
                reaTools.clear(); reaTools.addAll(tools)
                rea = client
                reaConnected = true
                status = null
            } catch (e: Exception) {
                Log.e("Reverse", "rea connect failed", e)
                reaConnected = false
                error = "Couldn't reach rea: ${e.message}"
                status = null
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a mobile reverse-engineering assistant inside an Android app. You are given a " +
            "structured, on-device decode of an APK (manifest, permissions, components, native libraries, " +
            "runtime, signature). Help the user understand how the app is built and how to make the change " +
            "they are authorized to make to their own or an open-source app. Be concrete: name the exact " +
            "components, permissions, resources or smali locations involved, and the safest way to apply the " +
            "change and rebuild. If deep class/method decompilation is needed, say which rea tool to run."
    }
}
