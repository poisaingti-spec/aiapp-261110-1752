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
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.reverse.ApkEdit
import com.aiapp.reverse.ApkReport
import com.aiapp.reverse.ReaClient
import com.aiapp.reverse.ReverseAgent
import com.aiapp.reverse.SmaliEngine
import com.aiapp.reverse.ReaConfig
import com.aiapp.reverse.ReaTool
import com.aiapp.reverse.RebuildResult
import com.aiapp.reverse.ReverseCiRunner
import com.aiapp.reverse.ReverseEngine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the Reverse Engineering tab: load an APK, decode it on the phone, ask the model about it,
 * optionally connect to a remote `rea` backend for deep analysis, and rebuild a re-signed APK with
 * authorized edits.
 */
class ReverseViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)
    private val prefs = app.getSharedPreferences("reverse", android.content.Context.MODE_PRIVATE)

    var busy by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var report by mutableStateOf<ApkReport?>(null); private set
    var rebuilt by mutableStateOf<RebuildResult?>(null); private set
    /** Set when the rebuild ran on GitHub Actions instead of on-device (apktool decode/patch/sign). */
    var ciResult by mutableStateOf<ReverseCiRunner.CiResult?>(null); private set
    var installingCi by mutableStateOf(false); private set

    private var apkFile: File? = null

    /** The model Q&A transcript for the loaded APK. */
    val transcript = mutableStateListOf<ChatMessage>()
    var answering by mutableStateOf(false); private set
    var liveAnswer by mutableStateOf(""); private set

    // remote rea
    var reaUrl by mutableStateOf(prefs.getString("rea_url", "").orEmpty())
    var reaConnected by mutableStateOf(false); private set
    var reaInfo by mutableStateOf<String?>(null); private set
    val reaTools = mutableStateListOf<ReaTool>()
    private var rea: ReaClient? = null

    fun clearError() { error = null }

    fun loadApk(uri: Uri) {
        viewModelScope.launch {
            busy = true; error = null; status = "Copying APK…"; report = null; rebuilt = null; transcript.clear()
            try {
                val ctx = getApplication<Application>()
                val dir = File(ctx.cacheDir, "reverse").apply { mkdirs() }
                val name = queryName(uri) ?: "target.apk"
                val dst = File(dir, if (name.endsWith(".apk", true)) name else "$name.apk")
                ctx.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Can't open that file." }
                    dst.outputStream().use { input.copyTo(it) }
                }
                apkFile = dst
                status = "Decoding on device…"
                report = ReverseEngine.inspect(dst)
                status = null
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "operation failed", e)
                error = e.message ?: "Couldn't read that APK."
                status = null
            } finally {
                busy = false
            }
        }
    }

    private fun queryName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull()

    /** Ask the selected chat model a question about the decoded APK. */
    fun ask(question: String, provider: ProviderId, model: String, effort: Effort) {
        val r = report ?: return
        if (answering) return
        transcript.add(ChatMessage(Role.USER, question))
        viewModelScope.launch {
            answering = true; liveAnswer = ""; error = null
            try {
                val key = keys.get(provider)
                val system = SYSTEM_PROMPT
                val context = "DECODED APK (on-device):\n" + r.toBriefing() +
                    (if (reaConnected) "\n\nA remote rea backend is connected with ${reaTools.size} tools for deeper analysis." else "")
                val messages = listOf(ChatMessage(Role.USER, "$context\n\nQuestion: $question"))
                Providers.get(provider).stream(key, model, messages, effort, system).collect { chunk ->
                    if (chunk.startsWith(THOUGHT)) return@collect
                    liveAnswer += chunk
                }
                transcript.add(ChatMessage(Role.ASSISTANT, liveAnswer))
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "operation failed", e)
                error = e.message ?: "The model request failed."
            } finally {
                answering = false; liveAnswer = ""
            }
        }
    }

    var planSummary by mutableStateOf<String?>(null); private set

    /**
     * One-tap: ask the model to turn [instruction] into concrete edits, then build the result.
     *
     * With a GitHub token, the model's job stops at deciding the edits — GitHub Actions does the
     * rest (apktool decode/patch/rebuild, zipalign, apksigner), the same split the Build tab uses
     * for whole apps. Without a token, this falls back to the on-device rebuild.
     */
    fun applyInstruction(instruction: String, provider: ProviderId, model: String, effort: Effort, githubToken: String) {
        val r = report ?: return
        val apk = apkFile ?: return
        if (busy || answering) return
        viewModelScope.launch {
            busy = true; error = null; rebuilt = null; ciResult = null; planSummary = null
            try {
                status = "Reading the app's text…"
                val strings = ReverseEngine.readStrings(apk)
                status = "Planning the change with ${provider.label}…"
                val prompt = ReverseAgent.buildPrompt(r, strings, instruction)
                val reply = StringBuilder()
                Providers.get(provider).stream(keys.get(provider), model, listOf(ChatMessage(Role.USER, prompt)), effort, ReverseAgent.SYSTEM)
                    .collect { chunk -> if (!chunk.startsWith(THOUGHT)) reply.append(chunk) }
                val plan = ReverseAgent.parsePlan(reply.toString())
                planSummary = plan.summary.ifBlank { null }
                val edits = plan.toApkEdits()
                if (edits.isEmpty()) {
                    error = plan.summary.ifBlank {
                        "The model couldn't express that as an edit. It may need code changes — try \"Change the code\", or connect the rea backend."
                    }
                    status = null
                    return@launch
                }
                if (githubToken.isNotBlank()) {
                    val runner = ReverseCiRunner(githubToken) { status = it }
                    ciResult = runner.run(apk, r.appLabel.ifBlank { r.packageName }, edits, emptyMap())
                } else {
                    status = "Applying ${edits.size} edit(s), rebuilding & signing on-device…"
                    val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
                    outDir.deleteRecursively()
                    rebuilt = ReverseEngine.rebuild(getApplication(), apk, edits, outDir)
                }
                status = null
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "operation failed", e)
                error = e.message ?: "Couldn't apply that change."
                status = null
            } finally {
                busy = false
            }
        }
    }

    private suspend fun collectReply(provider: ProviderId, model: String, prompt: String, system: String, effort: Effort): String {
        val sb = StringBuilder()
        Providers.get(provider).stream(keys.get(provider), model, listOf(ChatMessage(Role.USER, prompt)), effort, system)
            .collect { chunk -> if (!chunk.startsWith(THOUGHT)) sb.append(chunk) }
        return sb.toString()
    }

    /**
     * A code/logic change via smali. The model only picks search terms and writes the patch; finding
     * candidate classes is a quick on-device read. Applying the patch — reassembling the DEX,
     * rebuilding, zipaligning and signing — runs on GitHub Actions (apktool + the Android SDK build
     * tools already on its runners), not on the phone, when a GitHub token is set. Without a token,
     * it falls back to the slower, experimental on-device reassembly. For apps you are authorized to
     * modify; use a strong model.
     */
    fun applyCodeChange(instruction: String, provider: ProviderId, model: String, effort: Effort, githubToken: String) {
        val r = report ?: return
        val apk = apkFile ?: return
        if (busy || answering) return
        viewModelScope.launch {
            busy = true; error = null; rebuilt = null; ciResult = null; planSummary = null
            try {
                val api = r.minSdk.toIntOrNull() ?: 21
                val work = File(getApplication<Application>().cacheDir, "reverse/smali").apply { deleteRecursively(); mkdirs() }
                val engine = SmaliEngine(work, api)

                status = "Finding the relevant code…"
                val termsReply = collectReply(provider, model, ReverseAgent.termsPrompt(r, instruction), ReverseAgent.TERMS_SYSTEM, effort)
                val terms = ReverseAgent.parseTerms(termsReply, instruction)

                status = "Reading candidate classes (on-device, read-only)…"
                val candidates = engine.search(apk, terms)
                if (candidates.isEmpty()) {
                    error = "Couldn't find code matching that. Try naming the class, SDK, or exact text involved. (searched: ${terms.joinToString()})"
                    status = null; return@launch
                }

                status = "Writing the patch with ${provider.label}…"
                val editReply = collectReply(provider, model, ReverseAgent.smaliEditPrompt(instruction, candidates), ReverseAgent.SMALI_SYSTEM, effort)
                val smaliEdits = ReverseAgent.parseSmaliEdits(editReply)
                if (smaliEdits.isEmpty()) {
                    error = "The model didn't produce a safe code patch for that. Try a stronger model or a more specific instruction."
                    status = null; return@launch
                }
                planSummary = "Patching ${smaliEdits.size} class(es): ${smaliEdits.keys.joinToString().take(200)}"

                if (githubToken.isNotBlank()) {
                    val runner = ReverseCiRunner(githubToken) { status = it }
                    ciResult = runner.run(apk, r.appLabel.ifBlank { r.packageName }, emptyList(), smaliEdits)
                } else {
                    status = "Reassembling on-device, rebuilding & signing (slow)…"
                    val dexEdits = engine.buildEdits(apk, smaliEdits)
                    if (dexEdits.isEmpty()) {
                        error = "The edited classes weren't found in the app's DEX files."
                        status = null; return@launch
                    }
                    val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
                    outDir.deleteRecursively()
                    rebuilt = ReverseEngine.rebuild(getApplication(), apk, dexEdits, outDir)
                }
                status = null
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "operation failed", e)
                error = e.message ?: "Couldn't apply that code change."
                status = null
            } finally {
                busy = false
            }
        }
    }

    /** Rebuild the loaded APK with authorized manifest/version/rename edits, then re-sign. */
    fun rebuild(newLabel: String?, newVersionName: String?, newVersionCode: Int?, newPackage: String?) {
        val apk = apkFile ?: return
        viewModelScope.launch {
            busy = true; error = null; status = "Rebuilding & re-signing…"; rebuilt = null
            try {
                val edits = buildList {
                    newLabel?.takeIf { it.isNotBlank() }?.let { add(ApkEdit.Rename(it)) }
                    newPackage?.takeIf { it.isNotBlank() }?.let { add(ApkEdit.Repackage(it)) }
                    if (newVersionName != null || newVersionCode != null) add(ApkEdit.SetVersion(newVersionName, newVersionCode))
                }
                if (edits.isEmpty()) { error = "No changes specified."; return@launch }
                val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
                outDir.deleteRecursively()
                rebuilt = ReverseEngine.rebuild(getApplication(), apk, edits, outDir)
                status = null
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "operation failed", e)
                error = e.message ?: "Rebuild failed."
                status = null
            } finally {
                busy = false
            }
        }
    }

    fun install() {
        val out = rebuilt?.outApk ?: return
        com.aiapp.data.ApkInstaller.install(getApplication(), out)
    }

    /** Downloads the GitHub-built APK, then opens Android's installer. */
    fun installCi() {
        val url = ciResult?.apkUrl ?: return
        viewModelScope.launch {
            installingCi = true; error = null
            try {
                val app = getApplication<Application>()
                val file = com.aiapp.data.ApkInstaller.download(app, url) { }
                com.aiapp.data.ApkInstaller.install(app, file)
            } catch (e: Exception) {
                com.aiapp.data.Log.e("Reverse", "CI APK install failed", e)
                error = e.message ?: "Couldn't download the built APK."
            } finally {
                installingCi = false
            }
        }
    }

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
                com.aiapp.data.Log.e("Reverse", "rea connect failed", e)
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
