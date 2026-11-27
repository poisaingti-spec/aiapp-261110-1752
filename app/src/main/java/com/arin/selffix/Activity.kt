package com.arin.selffix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What Arin is doing, in plain words, for each tab: every step (searching, running a command, reading a file, fixing an
 * error, building) and — clearly marked 🟣 — every time it takes help from the AI helper, with the answer's outcome.
 * Sources: "chat", "claude-code", "build", "arin-update", "movie", "install"; "all" shows in every tab.
 */
object Activity {
    data class Entry(val time: Long, val source: String, val text: String)

    val entries = mutableStateListOf<Entry>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

    /** Records a step (repeats of the same line in a row are skipped). Safe from any thread. */
    fun log(source: String, text: String) {
        val t = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(220) ?: return
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            if (entries.lastOrNull { it.source == source }?.text == t) return@launch
            entries += Entry(System.currentTimeMillis(), source, t)
            while (entries.size > 400) entries.removeAt(0)
        }
    }

    fun time(e: Entry): String = fmt.format(java.util.Date(e.time))
}

/**
 * The tab's summary: "Now: …" while something is happening (or the last step), tap to see every recent step.
 * Steps where the AI helper was asked are marked 🟣.
 */
@Composable
fun ActivityBar(source: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); now = System.currentTimeMillis() } }
    val mine = Activity.entries.filter { it.source == source || it.source == "all" }
    val last = mine.lastOrNull() ?: return
    val fresh = now - last.time < 90_000
    if (!fresh && !open) {
        // Quiet when nothing happened lately: one small line to open the history.
        Text(
            "🗒 What Arin did (${mine.size} steps) — tap to see",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(bottom = 4.dp).clickable { open = true },
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp).clickable { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (fresh) "Now: " else "Last: ") + last.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (last.text.startsWith("🟣")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (open) 3 else 1,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open) "  ▲" else "  ▼ steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            // Count only real help (answers), not failed tries — and only while the helper is on in this tab.
            val helperOn = com.arin.selffix.ClaudeHelper.enabled(androidx.compose.ui.platform.LocalContext.current, source)
            val helped = mine.count { it.text.startsWith("🟣") && it.text.contains("answered") }
            val missed = mine.count { it.text.startsWith("🟣") && (it.text.contains("couldn't help") || it.text.contains("isn't connected")) }
            if (helperOn && missed > 0 && helped == 0 && !open) {
                Text("🟣 The AI helper couldn't be reached ($missed tries) — open steps to see why", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
            if (helperOn && helped > 0 && !open) {
                Text("🟣 Took help from the AI helper $helped time${if (helped == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)
            }
            if (open) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    mine.takeLast(60).forEach { e ->
                        Text(
                            Activity.time(e) + "  " + e.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (e.text.startsWith("🟣")) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
