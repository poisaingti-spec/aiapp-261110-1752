package com.arin.claudecode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import com.arin.llm.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The usage meter at the top of each tab.
 * - Claude (Claude Code login): your REAL plan usage — the same "Current session" (5-hour) and "Weekly" numbers the
 *   Claude app shows — from Claude Code's rate-limit reports on every reply, and from Claude's usage address.
 * - Custom API: only that API's own numbers (its balance where the provider reports one).
 * - Anything else: nothing is shown.
 */
object UsageMeter {
    data class Window(val used: Double, val resetsAt: Long) // used 0..1, resetsAt = epoch seconds

    /** Per Claude account ("main" = the Claude Code tab's sign-in). */
    val sessionBy = androidx.compose.runtime.mutableStateMapOf<String, Window>()
    val weeklyBy = androidx.compose.runtime.mutableStateMapOf<String, Window>()
    private val updatedBy = HashMap<String, Long>()
    var lastReplyTokens by mutableStateOf<Long?>(null); private set
    var updated by mutableStateOf(0L); private set
    var customLine by mutableStateOf<String?>(null); private set
    private var customFor = ""
    private var customChecked = 0L

    private fun epoch(v: Long): Long = if (v > 10_000_000_000L) v / 1000 else v

    /** From Claude Code's `rate_limit_event` (stream-json). */
    fun fromRateLimit(info: JSONObject?, account: String = "main") {
        info ?: return
        var session: Window? = sessionBy[account]
        var weekly: Window? = weeklyBy[account]
        val windows = info.optJSONObject("unifiedWindows")
        if (windows != null) {
            windows.optJSONObject("five_hour")?.let { session = Window(it.optDouble("utilization", 0.0), epoch(it.optLong("resetsAt"))) }
            windows.optJSONObject("seven_day")?.let { weekly = Window(it.optDouble("utilization", 0.0), epoch(it.optLong("resetsAt"))) }
        } else if (info.has("utilization")) {
            val w = Window(info.optDouble("utilization", 0.0), epoch(info.optLong("resetsAt")))
            when (info.optString("rateLimitType")) { "five_hour" -> session = w; "seven_day" -> weekly = w }
        } else if (info.has("resetsAt") && info.optString("rateLimitType") == "five_hour") {
            session = session?.copy(resetsAt = epoch(info.optLong("resetsAt")))
        }
        session?.let { sessionBy[account] = it }
        weekly?.let { weeklyBy[account] = it }
        updatedBy[account] = System.currentTimeMillis()
        updated = System.currentTimeMillis()
    }

    /** From a reply's `result`: how many tokens it used (new + cached). */
    fun fromResult(o: JSONObject) {
        val u = o.optJSONObject("usage") ?: return
        lastReplyTokens = u.optLong("input_tokens") + u.optLong("output_tokens") + u.optLong("cache_creation_input_tokens")
    }

    /** Claude's own usage address (what Claude Code's /usage shows). Works when the sign-in allows it. */
    suspend fun refreshClaude(account: String = "main") = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() - (updatedBy[account] ?: 0L) < 60_000 && sessionBy[account] != null) return@withContext
        val app = com.arin.ArinApp.instance
        val path = if (account == "main") ClaudeCode.tokenFile() else ClaudeAccounts.ownTokenFile(app, account)
        val token = runCatching {
            java.io.File(com.arin.terminal.Linux.rootfs(app), path.removePrefix("/")).readText().trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext
        runCatching {
            val c = URL("https://api.anthropic.com/api/oauth/usage").openConnection() as HttpURLConnection
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            if (c.responseCode != 200) return@runCatching
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            fun w(name: String) = o.optJSONObject(name)?.let {
                val iso = it.optString("resets_at")
                val at = runCatching { java.time.Instant.parse(iso).epochSecond }.getOrDefault(0L)
                Window(it.optDouble("utilization", 0.0) / 100.0, at) // this address reports 0–100
            }
            w("five_hour")?.let { sessionBy[account] = it }
            w("seven_day")?.let { weeklyBy[account] = it }
            updatedBy[account] = System.currentTimeMillis()
        }
    }

    /** Only the selected Custom API's own numbers: its balance where the provider reports one. */
    suspend fun refreshCustom(base: String, key: String) = withContext(Dispatchers.IO) {
        val id = base + key.takeLast(6)
        if (id == customFor && System.currentTimeMillis() - customChecked < 120_000) return@withContext
        customFor = id; customChecked = System.currentTimeMillis()
        customLine = runCatching {
            fun get(url: String): JSONObject? {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("Authorization", "Bearer $key")
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                return if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
            }
            when {
                base.contains("deepseek.com") -> get("https://api.deepseek.com/user/balance")?.optJSONArray("balance_infos")?.optJSONObject(0)?.let {
                    "Balance: ${it.optString("total_balance")} ${it.optString("currency")}"
                }
                base.contains("openrouter.ai") -> get("https://openrouter.ai/api/v1/key")?.optJSONObject("data")?.let {
                    val left = if (it.isNull("limit_remaining")) null else it.optDouble("limit_remaining")
                    (if (left != null) "Left: $" + "%.2f".format(left) + " · " else "") + "Used: $" + "%.2f".format(it.optDouble("usage"))
                }
                else -> null
            }
        }.getOrNull()
    }

    fun resetText(epochSec: Long): String {
        if (epochSec <= 0) return ""
        val t = java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneId.systemDefault())
        val now = java.time.ZonedDateTime.now()
        val fmt = if (t.toLocalDate() == now.toLocalDate()) "h:mm a" else "EEE, h:mm a"
        return "Resets " + t.format(java.time.format.DateTimeFormatter.ofPattern(fmt)).replace("AM", "am").replace("PM", "pm")
    }
}

/** A thin meter under a tab's title. Only for the Claude Code login (real plan usage) or the selected Custom API. */
@Composable
fun UsageBar(vm: ChatViewModel, claudeLogin: Boolean? = null) {
    // [claudeLogin]: for the Claude Code tab, which has its own sign-in choice.
    val claude = claudeLogin ?: (vm.provider == Provider.ClaudeCode)
    val custom = claudeLogin == null && vm.provider == Provider.Custom
    // The AI helper on Claude (your Claude Code login) uses your Claude limit too: its live meter shows as well.
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    val tab = com.arin.selffix.LocalHelperTab.current
    vm.helperLabel // re-read when the helper changes
    val helperAcc: String? = run {
        if (!vm.helperOn(tab)) return@run null
        val h = com.arin.selffix.ClaudeHelper.helper(ctx0)
        if (h.kind != "claude") return@run null
        val viaKey = h.model.startsWith("claude-") && com.arin.data.Settings(ctx0).key(Provider.Claude).isNotBlank()
        if (viaKey) null else ClaudeAccounts.accountOf(h.model) ?: "main"
    }
    if (!claude && !custom && helperAcc == null) return
    var details by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Which Claude account this tab uses: the one in the chosen model ("sonnet@id"), or the main sign-in.
    val acc = if (claudeLogin != null) "main" else ClaudeAccounts.accountOf(vm.model) ?: "main"
    val accName = if (acc == "main") "" else ClaudeAccounts.name(ctx, acc)
    if (claude) LaunchedEffect(acc) { while (true) { UsageMeter.refreshClaude(acc); delay(5 * 60_000L) } }
    if (helperAcc != null) LaunchedEffect(helperAcc) { while (true) { UsageMeter.refreshClaude(helperAcc); delay(5 * 60_000L) } }
    if (custom) LaunchedEffect(vm.customBaseUrl) { while (true) { UsageMeter.refreshCustom(vm.customBaseUrl, vm.customKeyForMeter()); delay(3 * 60_000L) } }
    val small = MaterialTheme.typography.labelSmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) { // one line under the other (they used to be drawn on top of each other)
    if (claude || custom) Column(Modifier.fillMaxWidth().clickable { details = true }.padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (claude) {
            val s = UsageMeter.sessionBy[acc]
            val w = UsageMeter.weeklyBy[acc]
            if (s == null && w == null) {
                Text("⚡ Claude usage shows after your first reply", style = small, color = dim)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (accName.isNotBlank()) "$accName · " else "") + "Session ${pct(s)}", style = small, color = dim)
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(progress = { (s?.used ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(60.dp).height(4.dp))
                Spacer(Modifier.width(12.dp))
                Text("Week ${pct(w)}", style = small, color = dim)
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(progress = { (w?.used ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(60.dp).height(4.dp))
            }
        } else if (custom) {
            Text("⚡ " + (UsageMeter.customLine ?: (vm.currentSavedApi()?.name ?: "This API") + ": no balance reported by this provider"),
                style = small, color = dim, maxLines = 1)
        }
    }
    // Helper line: the same real numbers for the Claude account the helper uses (unless it's the one already shown).
    if (helperAcc != null && !(claude && helperAcc == acc)) {
        val hs = UsageMeter.sessionBy[helperAcc]
        val hw = UsageMeter.weeklyBy[helperAcc]
        Row(Modifier.fillMaxWidth().clickable { details = true }.padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🟣 Helper Claude" + (if (helperAcc != "main") " · " + ClaudeAccounts.name(ctx, helperAcc) else "") +
                (if (hs == null && hw == null) ": usage shows after it helps" else " · Session ${pct(hs)}"), style = small, color = dim)
            if (hs != null || hw != null) {
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(progress = { (hs?.used ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(50.dp).height(4.dp))
                Spacer(Modifier.width(10.dp))
                Text("Week ${pct(hw)}", style = small, color = dim)
                Spacer(Modifier.width(6.dp))
                LinearProgressIndicator(progress = { (hw?.used ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.width(50.dp).height(4.dp))
            }
        }
    }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false },
        confirmButton = { TextButton(onClick = { details = false }) { Text("OK") } },
        title = { Text(if (claude) "Claude usage" + (if (accName.isNotBlank()) " · $accName" else "") else "API usage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (claude) {
                    Block("Current session", UsageMeter.sessionBy[acc])
                    Block("Weekly limits · All models", UsageMeter.weeklyBy[acc])
                    UsageMeter.lastReplyTokens?.let { Text("Last reply: ${"%,d".format(it)} tokens (cached history not counted)", style = MaterialTheme.typography.bodySmall) }
                    Text("The same numbers as the Claude app: your plan's limit is shared by the Claude app, Claude Code and Arin.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (custom) {
                    Text(UsageMeter.customLine ?: "This provider doesn't report a balance to apps. Check its own website for your usage.")
                }
                if (helperAcc != null && !(claude && helperAcc == acc)) {
                    Text("AI helper (Claude)", style = MaterialTheme.typography.titleSmall)
                    Block("Current session", UsageMeter.sessionBy[helperAcc])
                    Block("Weekly limits · All models", UsageMeter.weeklyBy[helperAcc])
                }
            }
        },
    )
}

private fun pct(w: UsageMeter.Window?) = if (w == null) "–" else if (w.used >= 1.0) "limit reached" else "${(w.used * 100).toInt()}%"

@Composable
private fun Block(title: String, w: UsageMeter.Window?) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (w == null) "not known yet" else if (w.used >= 1.0) "limit reached" else "${(w.used * 100).toInt()}% used", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { (w?.used ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
        if (w != null && w.resetsAt > 0) Text(UsageMeter.resetText(w.resetsAt), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
