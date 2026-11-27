package com.arin.selffix

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One place that knows what an install or download is doing right now, in plain words, with real numbers:
 * what it's doing, how many MB of how many, the percentage, the speed and the time left. Fed by:
 *  - Arin's own downloads (Ubuntu, offline models, APKs) through [bytes];
 *  - downloads inside Linux made with `arin-fetch` (it writes what it's fetching; the file's size is watched here);
 *  - the install's output lines (apt, sdkmanager, npm…) through [onLine].
 */
object InstallProgress {
    var active by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var step by mutableStateOf("")
        private set
    var done by mutableLongStateOf(0L)
        private set
    var total by mutableLongStateOf(0L)
        private set
    /** 0..1, or -1 when the size isn't known (a moving bar). */
    var fraction by mutableFloatStateOf(-1f)
        private set
    var speed by mutableLongStateOf(0L)
        private set

    private var poller: Job? = null
    private var autoEnd = false
    private var lastT = 0L
    private var lastB = 0L
    private var aptTotal = 0L
    private var aptDone = 0L

    /** Starts showing an install called [what] (e.g. "Installing Claude Code"). */
    fun begin(context: Context, what: String) {
        com.arin.app.ReplyService.hold(context, "install")
        active = true
        autoEnd = false
        title = what
        step = "Starting…"
        reset()
        aptTotal = 0; aptDone = 0
        ensureFetchTool(context)
        val root = com.arin.terminal.Linux.rootfs(context)
        poller?.cancel()
        poller = com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val now = java.io.File(root, "root/.arin/progress/now")
            while (true) {
                runCatching {
                    if (now.exists()) {
                        val parts = now.readText().trim().split('\t')
                        if (parts.size >= 3) {
                            val label = parts[0]
                            val size = parts[1].toLongOrNull() ?: 0L
                            val path = parts[2].removePrefix("/")
                            val f = java.io.File(root, path)
                            val part = java.io.File(root, "$path.part")
                            val have = maxOf(if (part.exists()) part.length() else 0L, if (f.exists()) f.length() else 0L)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { update("Downloading $label", have, size) }
                        }
                    }
                }
                delay(700)
            }
        }
    }

    fun end() {
        poller?.cancel()
        poller = null
        active = false
        runCatching { com.arin.app.ReplyService.release(com.arin.ArinApp.instance, "install") }
    }

    private fun reset() {
        done = 0; total = 0; fraction = -1f; speed = 0; lastT = 0; lastB = 0
    }

    private fun update(what: String, have: Long, size: Long) {
        if (what != step) { step = what; lastT = 0; lastB = 0; speed = 0 }
        val t = System.currentTimeMillis()
        if (lastT > 0 && t - lastT >= 600 && have >= lastB) {
            val now = (have - lastB) * 1000 / (t - lastT)
            speed = if (speed == 0L) now else (speed * 2 + now) / 3
        }
        if (lastT == 0L || t - lastT >= 600) { lastT = t; lastB = have }
        done = have
        total = size
        fraction = if (size > 0) (have.toFloat() / size).coerceIn(0f, 1f) else -1f
    }

    /** Arin's own downloads (Ubuntu image, offline models, APKs): shows them even when no install was started. */
    fun bytes(what: String, have: Long, size: Long) {
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            if (!active) { active = true; autoEnd = true; title = what; reset() }
            update(what, have, size)
            if (autoEnd && size > 0 && have >= size) { delay(1_500); if (autoEnd) end() }
        }
    }

    private val sizeRe = Regex("""([\d.,]+)\s*(B|kB|KB|MB|GB)\b""")
    private fun toBytes(m: MatchResult): Long {
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
        return (n * when (m.groupValues[2]) { "GB" -> 1e9; "MB" -> 1e6; "kB", "KB" -> 1e3; else -> 1.0 }).toLong()
    }

    /** Reads an install's output line and turns it into plain progress (apt, sdkmanager, npm, our own lines). */
    fun onLine(raw: String) {
        if (!active) return
        val line = raw.trim().replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")
        if (line.isEmpty()) return
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            when {
                line.startsWith("Need to get") -> {
                    sizeRe.find(line)?.let { aptTotal = toBytes(it); aptDone = 0 }
                    update("Downloading system packages", 0, aptTotal)
                }
                line.startsWith("Get:") -> {
                    val got = Regex("""\[([\d.,]+\s*(?:B|kB|MB|GB))]""").find(line)?.groupValues?.get(1)?.let { s -> sizeRe.find(s)?.let { toBytes(it) } } ?: 0
                    aptDone += got
                    val pkg = line.split(' ').getOrNull(3).orEmpty()
                    if (aptTotal > 0) update("Downloading system packages", aptDone, aptTotal)
                    else { step = "Downloading $pkg"; fraction = -1f }
                }
                line.startsWith("Unpacking ") -> { step = "Installing " + line.removePrefix("Unpacking ").substringBefore(' '); fraction = -1f; done = 0; total = 0 }
                line.startsWith("Setting up ") -> { step = "Setting up " + line.removePrefix("Setting up ").substringBefore(' '); fraction = -1f; done = 0; total = 0 }
                Regex("""(\d{1,3})%""").find(line) != null && (line.contains('[') || line.contains("Download", true) || line.contains("Unzipping", true)) -> {
                    val p = Regex("""(\d{1,3})%""").find(line)!!.groupValues[1].toInt().coerceIn(0, 100)
                    val what = line.substringAfter("% ").trim().ifBlank { step }
                    step = what.take(80)
                    fraction = p / 100f
                    done = 0; total = 0
                }
                line.startsWith("npm ") || line.contains("added ") && line.contains("packages") -> { step = "Installing with npm…"; fraction = -1f }
                // Arin's own step lines ("Installing…", "Downloading…", "Unpacking…") say what's happening.
                line.endsWith("…") || line.endsWith("...") -> { step = line.take(100); if (!line.startsWith("Downloading")) { fraction = -1f; done = 0; total = 0 } }
            }
        }
    }

    /** `arin-fetch URL FILE [name]`: a resumable download inside Linux that Arin shows with a progress bar. */
    fun ensureFetchTool(context: Context) {
        runCatching {
            val root = com.arin.terminal.Linux.rootfs(context)
            if (!java.io.File(root, "bin").exists()) return
            java.io.File(root, "root/.arin/progress").mkdirs()
            val f = java.io.File(root, "usr/local/bin/arin-fetch")
            f.parentFile?.mkdirs()
            f.writeText(FETCH)
            f.setExecutable(true, false)
        }
    }

    private const val FETCH = """#!/bin/bash
# arin-fetch URL FILE [name] — resumable download; Arin shows its progress (MB, %, speed, time left).
URL="${'$'}1"; OUT="${'$'}2"; NAME="${'$'}{3:-${'$'}(basename "${'$'}OUT")}"
[ -n "${'$'}URL" ] && [ -n "${'$'}OUT" ] || { echo "usage: arin-fetch URL FILE [name]" >&2; exit 2; }
mkdir -p /root/.arin/progress "${'$'}(dirname "${'$'}OUT")"
TOTAL=${'$'}(curl -sIL --connect-timeout 20 "${'$'}URL" | tr -d '\r' | grep -i '^content-length:' | tail -1 | awk '{print ${'$'}2}')
printf '%s\t%s\t%s\n' "${'$'}NAME" "${'$'}{TOTAL:-0}" "${'$'}OUT" > /root/.arin/progress/now
echo "Downloading ${'$'}NAME…"
ok=1
for i in ${'$'}(seq 1 30); do
  if curl -fL --retry 5 --retry-delay 3 --connect-timeout 30 -C - -sS -o "${'$'}OUT.part" "${'$'}URL"; then ok=0; break; fi
  echo "Download interrupted — resuming (${'$'}i)…"; sleep 3
done
rm -f /root/.arin/progress/now
[ ${'$'}ok = 0 ] && mv -f "${'$'}OUT.part" "${'$'}OUT"
exit ${'$'}ok
"""

    private fun mb(b: Long) = if (b >= 1_000_000_000) "%.2f GB".format(b / 1e9) else "%.1f MB".format(b / 1e6)

    /** "92.1 MB of 184.0 MB · 50% · 2.1 MB/s · about 44 s left", or what's known. */
    fun numbers(): String {
        val parts = mutableListOf<String>()
        if (total > 0) parts += "${mb(done)} of ${mb(total)}" else if (done > 0) parts += mb(done)
        if (fraction >= 0f) parts += "${(fraction * 100).toInt()}%"
        if (speed > 0) parts += "${mb(speed)}/s"
        if (speed > 0 && total > done) {
            val s = (total - done) / speed
            parts += "about " + when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} min ${s % 60} s"; else -> "${s / 3600} h ${(s % 3600) / 60} min" } + " left"
        }
        return parts.joinToString(" · ")
    }
}

/** The full progress: what it's doing, a bar, MB of MB, %, speed and time left. */
@Composable
fun InstallProgressPanel(modifier: Modifier = Modifier) {
    if (!InstallProgress.active) return
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(InstallProgress.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Now: " + InstallProgress.step, style = MaterialTheme.typography.bodyMedium)
        if (InstallProgress.fraction >= 0f) LinearProgressIndicator(progress = { InstallProgress.fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        val n = InstallProgress.numbers()
        if (n.isNotBlank()) Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small floating bar shown on every screen while something installs or downloads. */
@Composable
fun InstallProgressPill(modifier: Modifier = Modifier) {
    if (!InstallProgress.active) return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                InstallProgress.title + " — " + InstallProgress.step,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (InstallProgress.fraction >= 0f) LinearProgressIndicator(progress = { InstallProgress.fraction }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            val n = InstallProgress.numbers()
            if (n.isNotBlank()) Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
