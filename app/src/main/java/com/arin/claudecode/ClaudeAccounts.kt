package com.arin.claudecode

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arin.llm.ModelOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Several Claude accounts (Claude Code logins), each with its own saved sign-in. The model menu lists each account's
 * models ("Claude Sonnet · Account 2"); the model id carries the account ("sonnet@<id>"), so every chat targets the
 * account picked for it — only the person switches it.
 */
object ClaudeAccounts {
    data class Account(val id: String, val name: String)

    private const val DIR = "/root/.cc_tokens"
    private fun settings(c: Context) = com.arin.data.Settings(c)
    private fun file(c: Context, path: String) = File(com.arin.terminal.Linux.rootfs(c), path.removePrefix("/"))

    fun list(c: Context): List<Account> = runCatching {
        val a = JSONArray(settings(c).secret("CC_ACCOUNTS").ifBlank { "[]" })
        (0 until a.length()).map { a.getJSONObject(it) }.map { Account(it.getString("id"), it.getString("name")) }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Account>) =
        settings(c).setSecret("CC_ACCOUNTS", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name) }).toString())

    fun tokenPath(id: String) = "$DIR/$id"

    /** The account a model id points to ("sonnet@abc" → abc), or null for the main sign-in. */
    fun accountOf(model: String): String? = model.substringAfter('@', "").ifBlank { null }
    fun modelOf(model: String): String = model.substringBefore('@')

    fun name(c: Context, id: String?): String = if (id == null) (list(c).firstOrNull()?.name ?: "Claude") else list(c).firstOrNull { it.id == id }?.name ?: "Claude"

    /**
     * The ACTIVE account: only this one is used — by the Claude Code tab, the helper, workers and every chat — until the
     * owner switches. Each account keeps its own independent sign-in.
     */
    fun active(c: Context): String? = settings(c).secret("CC_ACTIVE").ifBlank { null }?.takeIf { id -> list(c).any { it.id == id } }

    fun setActive(c: Context, id: String) { settings(c).setSecret("CC_ACTIVE", id); makeMain(c, id) }

    /** An account's own token file (for its usage meter), whatever is active. */
    fun ownTokenFile(c: Context, id: String): String =
        if (file(c, tokenPath(id)).let { it.exists() && it.length() > 20 }) tokenPath(id) else ClaudeCode.tokenFile()

    /** The token file a model should use: the ACTIVE account's (else its own account's, else the main sign-in). */
    fun tokenFileFor(c: Context, model: String): String {
        active(c)?.let { a -> if (file(c, tokenPath(a)).let { it.exists() && it.length() > 20 }) return tokenPath(a) }
        val id = accountOf(model) ?: return ClaudeCode.tokenFile()
        return if (file(c, tokenPath(id)).let { it.exists() && it.length() > 20 }) tokenPath(id) else ClaudeCode.tokenFile()
    }

    /** The first time: the existing sign-in becomes "Account 1". */
    fun migrate(c: Context) {
        if (list(c).isNotEmpty()) return
        val main = file(c, ClaudeCode.tokenFile())
        if (!main.exists() || main.length() < 20) return
        val id = "a" + System.currentTimeMillis().toString(36)
        file(c, DIR).mkdirs()
        runCatching { main.copyTo(file(c, tokenPath(id)), overwrite = true) }
        save(c, listOf(Account(id, "Account 1")))
    }

    /** A sign-in just finished (its token is in the main file): keep it as a named account. */
    fun addFromMainToken(c: Context, name: String): Account? {
        val main = file(c, ClaudeCode.tokenFile())
        if (!main.exists() || main.length() < 20) return null
        val token = main.readText().trim()
        val list = list(c)
        // The same token again: nothing new.
        list.firstOrNull { runCatching { file(c, tokenPath(it.id)).readText().trim() == token }.getOrDefault(false) }?.let { return it }
        val id = "a" + System.currentTimeMillis().toString(36)
        file(c, DIR).mkdirs()
        file(c, tokenPath(id)).writeText(token)
        val acc = Account(id, name.ifBlank { "Account ${list.size + 1}" })
        save(c, list + acc)
        return acc
    }

    /** Make an account the main sign-in (used by the Claude Code tab). */
    fun makeMain(c: Context, id: String) {
        val src = file(c, tokenPath(id))
        if (src.exists()) runCatching { src.copyTo(file(c, ClaudeCode.tokenFile()), overwrite = true) }
    }

    /** The email of the Claude account behind a token (when Claude says), to check it's the right account. */
    suspend fun email(c: Context, id: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val token = file(c, tokenPath(id)).readText().trim()
            val con = java.net.URL("https://api.anthropic.com/api/oauth/profile").openConnection() as java.net.HttpURLConnection
            con.setRequestProperty("Authorization", "Bearer $token"); con.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            con.connectTimeout = 15_000; con.readTimeout = 15_000
            if (con.responseCode != 200) return@runCatching null
            JSONObject(con.inputStream.bufferedReader().readText()).optJSONObject("account")?.optString("email")?.ifBlank { null }
        }.getOrNull()
    }

    fun rename(c: Context, id: String, name: String) = save(c, list(c).map { if (it.id == id) it.copy(name = name) else it })

    fun remove(c: Context, id: String) {
        runCatching { file(c, tokenPath(id)).delete() }
        save(c, list(c).filterNot { it.id == id })
        if (settings(c).secret("CC_ACTIVE") == id) list(c).firstOrNull()?.let { setActive(c, it.id) } ?: settings(c).setSecret("CC_ACTIVE", "")
    }

    /** The models in the menu: every account × Sonnet / Opus / Haiku. */
    fun models(c: Context): List<ModelOption> {
        migrate(c)
        val accounts = list(c)
        val base = listOf("sonnet" to "Claude Sonnet", "opus" to "Claude Opus", "haiku" to "Claude Haiku")
        if (accounts.isEmpty()) return base.map { (id, label) -> ModelOption(id, "$label (Claude Code login)") }
        // Only the active account's models are offered (switch accounts in the Claude Code tab).
        active(c)?.let { act -> accounts.firstOrNull { it.id == act }?.let { a -> return base.map { (id, label) -> ModelOption("$id@${a.id}", "$label · ${a.name}") } } }
        return accounts.flatMap { a -> base.map { (id, label) -> ModelOption("$id@${a.id}", "$label · ${a.name}") } }
    }
}

/**
 * Claude's sign-in page inside Arin, starting completely fresh every time (no saved cookies or logins), so it signs
 * in to exactly the account you type — not whichever account your browser remembers. The code at the end is picked
 * up automatically.
 */
/** Said by [FreshSignInDialog] when the sign-in finished by itself (nothing to paste). */
const val LOCAL_DONE = "__arin_local_signin_done__"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FreshSignInDialog(url: String, onCode: (String) -> Unit, onClose: () -> Unit, onError: (String) -> Unit = {}) {
    // When it closes, forget this sign-in too, so the next fresh page starts empty.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign in to Claude (fresh)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Text(
                    "Use email sign-in for the account you want. Google sign-in may not work inside apps.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // Fresh every time: forget all cookies, storage and cache before loading.
                        WebStorage.getInstance().deleteAllData()
                        WebView(ctx).apply {
                            clearCache(true); clearHistory(); clearFormData()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(false) // sign-in popups open in this same page
                            // Look like normal Chrome: Claude's and Google's pages refuse "in-app" browsers.
                            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) // the security check needs them
                            var sent = false
                            fun check(u: String?) {
                                if (sent || u == null) return
                                // The link's own finish: Claude sends you back to Claude Code on this phone (localhost), which
                                // completes the sign-in itself. Nothing to paste — just close when it's done.
                                if (u.startsWith("http://localhost") || u.startsWith("http://127.0.0.1")) {
                                    if (u.contains("callback") && u.contains("code=")) { sent = true; postDelayed({ onCode(LOCAL_DONE) }, 2500) }
                                    return
                                }
                                val uri = android.net.Uri.parse(u)
                                val code = uri.getQueryParameter("code") ?: return
                                if (!u.contains("callback")) return
                                val state = uri.getQueryParameter("state") ?: uri.fragment.orEmpty()
                                sent = true
                                onCode(if (state.isNotBlank()) "$code#$state" else code)
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                                    if (request.isForMainFrame) onError("The page didn't load: ${error.description}")
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    check(request.url.toString()); return false
                                }
                                override fun onPageFinished(view: WebView, u: String?) {
                                    check(u)
                                    // The page may show the code as text instead: read it from the page.
                                    if (!sent && u?.contains("callback") == true) view.evaluateJavascript("document.body.innerText") { txt ->
                                        val t = txt.trim('"').replace("\\n", " ")
                                        Regex("[A-Za-z0-9_-]{20,}#[A-Za-z0-9_-]{10,}").find(t)?.value?.let { sent = true; onCode(it) }
                                    }
                                }
                            }
                            // Load only AFTER every old cookie is really gone (removal finishes later, in the background),
                            // otherwise Claude still sees the previous account and shows its "Authorize" page.
                            val wv = this
                            CookieManager.getInstance().removeAllCookies {
                                CookieManager.getInstance().removeSessionCookies {
                                    CookieManager.getInstance().flush()
                                    wv.post { wv.loadUrl(url) }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
