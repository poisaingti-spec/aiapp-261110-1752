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
import com.aiapp.reverse.ReaConfig
import com.aiapp.reverse.ReaTool
import com.aiapp.reverse.RebuildResult
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
                error = e.message ?: "The model request failed."
            } finally {
                answering = false; liveAnswer = ""
            }
        }
    }

    var planSummary by mutableStateOf<String?>(null); private set

    /**
     * One-tap: ask the model to turn [instruction] into concrete edits, apply them on-device, then
     * rebuild and re-sign — the whole process at once. For apps you are authorized to modify.
     */
    fun applyInstruction(instruction: String, provider: ProviderId, model: String, effort: Effort) {
        val r = report ?: return
        val apk = apkFile ?: return
        if (busy || answering) return
        viewModelScope.launch {
            busy = true; error = null; rebuilt = null; planSummary = null
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
                        "The model couldn't express that as an on-device edit. It may need code changes — connect the rea backend."
                    }
                    status = null
                    return@launch
                }
                status = "Applying ${edits.size} edit(s), rebuilding & signing…"
                val outDir = File(getApplication<Application>().cacheDir, "reverse/out")
                outDir.deleteRecursively()
                rebuilt = ReverseEngine.rebuild(getApplication(), apk, edits, outDir)
                status = null
            } catch (e: Exception) {
                error = e.message ?: "Couldn't apply that change."
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
